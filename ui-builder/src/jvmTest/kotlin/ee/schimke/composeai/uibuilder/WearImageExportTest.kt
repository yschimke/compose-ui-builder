package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `asset/image` on a Wear screen, the one borrowed foundation component (beside `layout/box`,
 * `layout/column`, `layout/row`) that isn't a container.
 *
 * Generated source can't carry the asset bytes, and the project symbol (`R.drawable.…`,
 * `Res.drawable.…`) is the receiver's, so the frame is filled with the theme's raised ground and a
 * comment names the line to replace. This matches the catalog's `assetKey` notes (*"exports as
 * Image(painter = ColorPainter(...))"*) and `ScreenDocumentProjection` on mobile.
 */
class WearImageExportTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  private fun properties(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (name, value) ->
      putJsonObject(name) {
        put("type", "string")
        put("value", JsonPrimitive(value))
      }
    }
  }

  private fun screenWith(node: UiBuilderNode): UiBuilderDocument {
    val base = wearScreenUiBuilderDocument("activity", pin, environment)
    val list = base.nodes.getValue("wear-list")
    return base.copy(
      nodes =
        base.nodes +
          ("wear-list" to list.copy(slots = mapOf("items" to listOf(node.id)))) +
          (node.id to node)
    )
  }

  private fun image(vararg pairs: Pair<String, String>) =
    UiBuilderNode(id = "photo", componentId = "asset/image", properties = properties(*pairs))

  private fun export(document: UiBuilderDocument): WearScreenCodeExporter.Result =
    WearScreenCodeExporter.export(document)

  private fun source(document: UiBuilderDocument): String =
    assertIs<WearScreenCodeExporter.Result.Emitted>(export(document)).source

  @Test
  fun `an image generates Image with a stand-in painter and the imports that resolve it`() {
    val source = source(screenWith(image("assetKey" to "hero")))

    assertTrue("import androidx.compose.foundation.Image" in source, source)
    assertTrue("import androidx.compose.ui.graphics.painter.ColorPainter" in source, source)
    // Wear's `MaterialTheme`, not mobile's: the colour is read off the Wear colour scheme, which
    // is the one the generated file's theme provides.
    assertTrue("import androidx.wear.compose.material3.MaterialTheme" in source, source)
    assertTrue(
      "painter = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHigh)," in source,
      source,
    )
    // The author is told which line to replace and which asset it stands for. Without the key,
    // a file with three images has three identical placeholders and no way to tell them apart.
    assertTrue("The asset `hero` is bytes in the design" in source, source)
  }

  /** Only what the design set: untouched `contentScale` and `alignment` are omitted. */
  @Test
  fun `scale and alignment are written when authored and omitted when not`() {
    val bare = source(screenWith(image("assetKey" to "hero")))
    assertTrue("contentScale" !in bare, bare)
    assertTrue("alignment" !in bare, bare)
    assertTrue("import androidx.compose.ui.layout.ContentScale" !in bare, bare)
    assertTrue("import androidx.compose.ui.Alignment" !in bare, bare)

    val authored =
      source(
        screenWith(
          image(
            "assetKey" to "hero",
            "contentScale" to "fillBounds",
            "alignment" to "bottomEnd",
            "contentDescription" to "A runner",
          )
        )
      )
    assertTrue("contentScale = ContentScale.FillBounds," in authored, authored)
    assertTrue("alignment = Alignment.BottomEnd," in authored, authored)
    assertTrue("import androidx.compose.ui.layout.ContentScale" in authored, authored)
    assertTrue("import androidx.compose.ui.Alignment" in authored, authored)
    assertTrue("""contentDescription = "A runner",""" in authored, authored)
  }

  /** No description authored is `null` stated, not omitted: `Image`'s parameter has no default. */
  @Test
  fun `an undescribed image states a null description rather than leaving it out`() {
    val source = source(screenWith(image("assetKey" to "hero")))

    assertTrue("contentDescription = null," in source, source)
  }

  /**
   * A value outside the catalog's `allowedValues` (e.g. `ContentScale.Squish`) is refused by name
   * rather than written through.
   */
  @Test
  fun `a scale or alignment the catalog does not allow is refused`() {
    val scale = export(screenWith(image("assetKey" to "hero", "contentScale" to "squish")))
    val scaleReasons = assertIs<WearScreenCodeExporter.Result.Refused>(scale).reasons
    assertTrue(scaleReasons.any { "squish" in it && "fillBounds" in it }, "$scaleReasons")

    val alignment = export(screenWith(image("assetKey" to "hero", "alignment" to "middle")))
    val alignmentReasons = assertIs<WearScreenCodeExporter.Result.Refused>(alignment).reasons
    assertTrue(alignmentReasons.any { "middle" in it && "centerStart" in it }, "$alignmentReasons")
  }

  /** The key is the whole node; an image without one stands in for nothing. */
  @Test
  fun `an image with no asset key is refused`() {
    val reasons =
      assertIs<WearScreenCodeExporter.Result.Refused>(export(screenWith(image()))).reasons

    assertTrue(reasons.any { "assetKey" in it }, "$reasons")
  }
}
