@file:Suppress("RestrictedApi")

package proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.FloatExpression
import androidx.compose.remote.core.operations.layout.Component
import androidx.compose.remote.core.operations.layout.LayoutComponent
import androidx.compose.remote.core.operations.layout.managers.StateLayout
import androidx.compose.remote.core.types.IntegerConstant
import androidx.compose.remote.creation.compose.capture.*
import androidx.compose.remote.creation.compose.layout.RemoteComposable
import androidx.compose.remote.player.core.platform.AndroidRemoteContext
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import proof.generated.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionProofTest {
  @Test
  fun integer() = verify(10, listOf(20 to 1, 30 to 2, 10 to 0), 3) { IntegerRemoteRemoteContent() }

  @Test fun boolean() = verify(0, listOf(1 to 1, 0 to 0), 3) { BooleanRemoteRemoteContent() }

  @Test
  fun decimal() =
    verify(-1.5f, listOf(2.5f to 1, 8f to 2, -1.5f to 0), 3) { DecimalRemoteRemoteContent() }

  @Test
  fun adjacentDecimal() =
    verify(1f, listOf(Float.fromBits(1f.toRawBits() + 1) to 1, 0.5f to 2, 1f to 0), 3) {
      AdjacentDecimalRemoteRemoteContent()
    }

  @Test
  fun noFallback() =
    verify(10, listOf(20 to 1, 30 to 2, 10 to 0), 3, emptyFallback = true) {
      NoFallbackRemoteRemoteContent()
    }

  @Test
  fun manyCases() =
    verify(
      10,
      (20..130 step 10).mapIndexed { index, value -> value to index + 1 } + (999 to 13),
      14,
    ) {
      ManyCasesRemoteRemoteContent()
    }

  @Test
  fun integerExtremes() =
    verify(Int.MIN_VALUE, listOf(Int.MAX_VALUE to 1, 0 to 2, Int.MIN_VALUE to 0), 3) {
      ExtremesRemoteRemoteContent()
    }

  @Test fun plainLayoutRootAtDensityOne() = verifyPlainRoot(1)

  @Test fun plainLayoutRootAtDensityTwo() = verifyPlainRoot(2)

  private fun verifyPlainRoot(density: Int) = runTest {
    val size = 360 * density
    val captured =
      captureSingleRemoteDocument(
        ApplicationProvider.getApplicationContext<Context>(),
        creationDisplayInfo = RemoteCreationDisplayInfo(size, size, 160 * density, 1f),
      ) {
        generated.uibuilder.StatefulPreviewRemoteContent()
      }
    val core =
      CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(captured.bytes.inputStream()))
      }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val context =
      AndroidRemoteContext().apply {
        useCanvas(Canvas(bitmap))
        this.density = density.toFloat()
      }
    core.initializeContext(context)
    fun flatten(operations: List<Operation>): List<Operation> = operations.flatMap {
      listOf(it) + if (it is Component) flatten(it.list) else emptyList()
    }
    val layout = flatten(core.operations).filterIsInstance<StateLayout>().single()
    val output = java.io.File("build/plain-root-evidence").apply { mkdirs() }
    java.io.File(output, "density-$density.rc").writeBytes(captured.bytes)
    for ((step, expected) in listOf(0, 1, 2, 0).withIndex()) {
      core.paint(context, 0)
      core.paint(context, 0)
      assertEquals(
        "selected branch after $step clicks at density $density",
        expected,
        layout.currentLayoutIndex,
      )
      val box = layout.parent as LayoutComponent
      assertEquals(24f * density, box.paddingLeft, .01f)
      assertEquals(312f * density, layout.getLayout(expected).width, .01f)
      java.io.File(output, "density-$density-$step.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      if (step < 3) assertTrue("recorded click action", core.onClick(context, size / 2f, size / 2f))
    }
  }

  private fun verify(
    initial: Number,
    changes: List<Pair<Number, Int>>,
    branches: Int,
    emptyFallback: Boolean = false,
    content: @Composable @RemoteComposable () -> Unit,
  ) = runTest {
    val androidContext = ApplicationProvider.getApplicationContext<Context>()
    val captured =
      captureSingleRemoteDocument(
        androidContext,
        creationDisplayInfo = RemoteCreationDisplayInfo(300, 300, 160, 1f),
        content = content,
      )
    val core =
      CoreDocument().apply {
        initFromBuffer(RemoteComposeBuffer.fromInputStream(captured.bytes.inputStream()))
      }
    val context =
      AndroidRemoteContext().apply {
        useCanvas(Canvas(Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)))
        density = 1f
      }
    core.initializeContext(context)
    fun flatten(operations: List<Operation>): List<Operation> = operations.flatMap {
      listOf(it) + if (it is Component) flatten(it.list) else emptyList()
    }
    val operations = flatten(core.operations)
    // State is deliberately local in generated source. Read its actual recorded ID from the
    // document, then use the same override API as Remote value-change actions and host updates.
    val variableId =
      if (initial is Float)
        operations
          .filterIsInstance<FloatExpression>()
          .single { it.mSrcValue.contentEquals(floatArrayOf(initial)) }
          .id
      else
        operations
          .filterIsInstance<IntegerConstant>()
          .first { context.getInteger(it.id) == initial.toInt() }
          .id
    val layout = operations.filterIsInstance<StateLayout>().single()
    core.paint(context, 0)
    assertEquals(0, layout.currentLayoutIndex)
    assertEquals(branches, layout.childrenComponents.size)
    // Component coordinates are relative to the padded content origin. Padding belongs to
    // the retained Box and is applied by its paint/layout path, not to StateLayout's local x/y.
    val box = layout.parent as LayoutComponent
    assertEquals("authored start padding", 8f, box.paddingLeft, 0.01f)
    assertEquals("authored top padding", 8f, box.paddingTop, 0.01f)
    assertEquals("authored end padding", 8f, box.paddingRight, 0.01f)
    assertEquals("authored bottom padding", 8f, box.paddingBottom, 0.01f)
    for ((value, expected) in changes) {
      if (value is Float) context.overrideFloat(variableId, value)
      else context.overrideInteger(variableId, value.toInt())
      core.paint(context, 0)
      core.paint(context, 0)
      assertEquals("state $value", expected, layout.currentLayoutIndex)
      val expectedSize = if (emptyFallback && expected == branches - 1) 0f else 40f + expected * 10f
      assertEquals(
        "selected child width at $value",
        expectedSize,
        layout.getLayout(expected).width,
        0.01f,
      )
      assertEquals(
        "selected child height at $value",
        expectedSize,
        layout.getLayout(expected).height,
        0.01f,
      )
    }
  }
}
