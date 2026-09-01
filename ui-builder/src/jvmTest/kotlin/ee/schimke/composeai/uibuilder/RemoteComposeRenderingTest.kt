package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.protocol.RcCustomLayout
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcNoArg
import ee.schimke.composeai.rcplayer.protocol.RcOpcodes
import ee.schimke.composeai.rcplayer.protocol.RcRootLayout
import ee.schimke.composeai.rcplayer.protocol.RcTextData
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

@OptIn(ExperimentalComposeUiApi::class)
class RemoteComposeRenderingTest {
  @Test
  fun `named Remote Compose custom component renders its UI Builder slot`() {
    val document = remoteComposeDocument(width = 32, height = 32)
    val rendered = renderComposeScene(32, 32, Density(1f)) { UiBuilderSurface(document) }
    val pixels = Bitmap().apply { allocN32Pixels(32, 32) }
    rendered.readPixels(pixels)

    assertEquals(0xffff0000.toInt(), pixels.getColor(16, 16))
  }

  @Test
  fun `render before and after evidence`() {
    val output = Path.of("build", "evidence", "remote-compose")
    Files.createDirectories(output)
    listOf(
        "before.png" to remoteComposeDocument(320, 180, "remote-compose/not-yet-supported"),
        "after.png" to remoteComposeDocument(320, 180),
      )
      .forEach { (name, document) ->
        val image = renderComposeScene(320, 180, Density(1f)) { UiBuilderSurface(document) }
        val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG, 100))
        Files.write(output.resolve(name), data.bytes)
      }
  }

  private fun remoteComposeDocument(
    width: Int,
    height: Int,
    componentId: String = "remote-compose/document",
  ): UiBuilderDocument {
    val nested =
      RcDocument(
        header = RcHeader(RcVersion(0, 1, 0), legacyWidth = width, legacyHeight = height),
        operations =
          listOf(
            RcTextData(1, "hero.card"),
            RcRootLayout(10),
            RcCustomLayout(
              componentId = 20,
              animationId = 0,
              configId = 1,
              properties = emptyList(),
            ),
            RcNoArg(RcOpcodes.CONTAINER_END),
            RcNoArg(RcOpcodes.CONTAINER_END),
          ),
      )
    val remote =
      UiBuilderNode(
        id = "remote",
        componentId = componentId,
        properties =
          JsonObject(
            mapOf(
              "documentBase64" to property(Base64.Default.encode(RcDocumentCodec.encode(nested)))
            )
          ),
        modifiers = JsonArray(listOf(buildJsonObject { put("type", "fillMaxSize") })),
        slots = mapOf("hero.card" to listOf("slot-fill")),
      )
    val fill =
      UiBuilderNode(
        id = "slot-fill",
        componentId = "shape/linear-gradient",
        properties =
          JsonObject(
            mapOf(
              "startColor" to property("#FFFF0000"),
              "endColor" to property("#FFFF0000"),
            )
          ),
        modifiers = JsonArray(listOf(buildJsonObject { put("type", "fillMaxSize") })),
      )
    return UiBuilderDocument(
      schema = "ui-builder/document/v1",
      id = "remote-compose-render-test",
      title = "Remote Compose render test",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment =
        buildJsonObject {
          put("widthDp", width)
          put("heightDp", height)
          put("density", 1)
          put("fontScale", 1)
          put("theme", "light")
        },
      stateVariables = JsonObject(emptyMap()),
      roots = listOf(remote.id),
      nodes = mapOf(remote.id to remote, fill.id to fill),
    )
  }

  private fun property(value: String) = buildJsonObject {
    put("type", "string")
    put("value", JsonPrimitive(value))
  }
}
