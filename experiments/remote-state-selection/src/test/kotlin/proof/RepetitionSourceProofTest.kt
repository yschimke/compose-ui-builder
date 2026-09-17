@file:Suppress("RestrictedApi")

package proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.creation.compose.capture.RemoteCreationDisplayInfo
import androidx.compose.remote.creation.compose.capture.captureSingleRemoteDocument
import androidx.compose.remote.player.core.platform.AndroidRemoteContext
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import proof.repetition.remote.RemoteRepeatedContent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepetitionSourceProofTest {
  @Test fun realFunctionsAndLoopsAtDensityOne() = verify(1)

  @Test fun realFunctionsAndLoopsAtDensityTwo() = verify(2)

  private fun verify(density: Int) = runTest {
    val captured =
      captureSingleRemoteDocument(
        ApplicationProvider.getApplicationContext<Context>(),
        creationDisplayInfo =
          RemoteCreationDisplayInfo(100 * density, 120 * density, 160 * density, 1f),
      ) {
        RemoteRepeatedContent()
      }
    val core =
      CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(captured.bytes.inputStream()))
      }
    val bitmap = Bitmap.createBitmap(100 * density, 120 * density, Bitmap.Config.ARGB_8888)
    val context =
      AndroidRemoteContext().apply {
        useCanvas(Canvas(bitmap))
        this.density = density.toFloat()
      }
    core.initializeContext(context)
    val output = File("build/repetition-source-evidence").apply { mkdirs() }
    File(output, "remote-$density.rc").writeBytes(captured.bytes)
    fun paint() {
      bitmap.eraseColor(0)
      core.paint(context, 0)
      core.paint(context, 0)
    }
    fun save(name: String) {
      File(output, "$name-$density.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
    }
    paint()
    save("remote-initial")
    // Compare every pixel with the independently generated JSON/player artifact.
    val expected =
      BitmapFactory.decodeFile(
        File("../../docs/design/evidence/ui-builder-repetition-proof/rows-$density.png").path
      )
    assertNotNull("independent JSON/player reference", expected)
    assertEquals(expected.width, bitmap.width)
    assertEquals(expected.height, bitmap.height)
    val actualPixels = IntArray(bitmap.width * bitmap.height)
    val expectedPixels = IntArray(actualPixels.size)
    bitmap.getPixels(actualPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    expected.getPixels(expectedPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    assertArrayEquals(
      "creation-compose and JSON must draw the same layout",
      expectedPixels,
      actualPixels,
    )
    for (row in 0..2) {
      for ((name, x, color) in
        listOf(
          Triple("green", 28 + row * 8, 0xff00ff00.toInt()),
          Triple("red", 12, 0xffff0000.toInt()),
        )) {
        assertTrue(
          "$name click in row $row",
          core.onClick(context, x.toFloat() * density, (10f + row * 24) * density),
        )
        paint()
        assertEquals(
          "$name selects shared state in row $row",
          color,
          bitmap.getPixel(30 * density, 78 * density),
        )
        save("remote-row-$row-$name")
      }
    }
  }
}
