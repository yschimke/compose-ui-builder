package ee.schimke.composeai.uibuilder

import androidx.compose.ui.geometry.Size
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The documents the component menu's thumbnails draw.
 *
 * A thumbnail here is not a baked picture: it is the component inserted into an empty frame by the
 * same reducer the row's Add uses, rendered small. So what has to be true of it is what has to be
 * true of an insert — it validates, it holds the component, and it carries whatever the component
 * arrives holding — and that is what these assert. How it *looks* is the chrome previews' job.
 */
class CatalogThumbnailTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)

  @Test
  fun `every component in the catalog draws its own insert`() {
    // All of them, and that is worth asserting rather than assuming: `layout/box` accepts every
    // role and `AnyContent`, so even a Scaffold — a whole screen — lands in the frame and draws.
    // A component that stops drawing loses its picture in the panel and nothing else complains,
    // so this is the thing that complains.
    val undrawn =
      catalog.components.map { it.componentId }.filter { reducer.previewDocument(it) == null }

    assertEquals(emptyList(), undrawn.sorted(), "components whose thumbnail could not be built")
  }

  @Test
  fun `a thumbnail document is a valid document holding the component`() {
    catalog.components.forEach { component ->
      val document = reducer.previewDocument(component.componentId) ?: return@forEach

      assertEquals(
        emptyList(),
        CapabilityValidator(catalog).validate(document).issues,
        component.componentId,
      )
      assertTrue(
        document.nodes.values.any { it.componentId == component.componentId },
        "${component.componentId}'s own thumbnail does not contain it",
      )
    }
  }

  @Test
  fun `a variant's thumbnail carries the variant`() {
    val outlined =
      EditorCatalogVariant("m3/card", "variant", "outlined", "Outlined", default = false)

    val document = assertNotNull(reducer.previewDocument("m3/card", outlined))

    val card = document.nodes.values.single { it.componentId == "m3/card" }
    assertEquals("outlined", card.properties["variant"]?.let(::literalValue))
    // And it is a different picture from the default one, which is the only reason to draw it.
    assertTrue(
      document != reducer.previewDocument("m3/card"),
      "the outlined card's thumbnail is the filled card's thumbnail",
    )
  }

  @Test
  fun `the same request returns the same document`() {
    // The panel asks for these on every frame of a scroll, so they are cached; a cache that
    // returned a fresh document each time would make every scroll a recomposition of every row.
    assertTrue(
      reducer.previewDocument("m3/button") === reducer.previewDocument("m3/button"),
      "thumbnail documents are not cached",
    )
  }

  @Test
  fun `a thumbnail insert cannot disturb the design being edited`() {
    // The frame is this reducer's own, and asking for a thumbnail is a question, not an edit. If
    // it ever became one, it would submit operations to the collaboration session.
    val document =
      UiBuilderReducer.replay(
          kotlinx.serialization.json.Json.parseToJsonElement(
              resource("/jetcaster-discover-operations-v1.json")
            )
            .let { kotlinx.serialization.json.JsonObject(it.jsonObjectOrEmpty()) }
        )
        .document
    val before = reducer.initial(document, selectedNodeId = "discover-grid")

    reducer.previewDocument("m3/card")
    val after = reducer.initial(document, selectedNodeId = "discover-grid")

    assertEquals(before.document.revision, after.document.revision)
    assertEquals(before.operationSequence, after.operationSequence)
  }

  @Test
  fun `a small component is cropped and magnified no more than twice`() {
    val transform =
      thumbnailContentTransform(
        contentBounds = UiBuilderPixelBounds(x = 76f, y = 52f, width = 24f, height = 24f),
        tileSize = Size(44f, 33f),
        fallbackScale = .25f,
      )

    assertEquals(1.375f, transform.scale)
    // A 24px square fits the tile height (33px), leaving 11px across. That spare space is split,
    // not left as a visibly lopsided gutter beside every icon-shaped component.
    assertEquals(-99f, transform.translation.x)
    assertEquals(-71.5f, transform.translation.y)
  }

  @Test
  fun `an absent inspection keeps the full frame thumbnail`() {
    val transform = thumbnailContentTransform(null, Size(44f, 33f), fallbackScale = .25f)

    assertEquals(.25f, transform.scale)
    assertEquals(androidx.compose.ui.geometry.Offset.Zero, transform.translation)
  }

  @Test
  fun `a margin keeps a wide component off the tile's edges`() {
    val transform =
      thumbnailContentTransform(
        contentBounds = UiBuilderPixelBounds(x = 40f, y = 40f, width = 96f, height = 48f),
        tileSize = Size(104f, 72f),
        fallbackScale = .5f,
        margin = 6f,
      )

    // 92px of usable width over a 96px button: it fits inside the margin rather than touching
    // both sides of the tile, and stays centred.
    assertEquals(92f / 96f, transform.scale)
    assertEquals(6f, 40f * transform.scale + transform.translation.x, 0.001f)
  }

  @Test
  fun `bounds read back a fraction of a pixel off are the same bounds`() {
    val measured = UiBuilderPixelBounds(x = 40f, y = 40f, width = 96f, height = 48f)

    assertTrue(sameThumbnailBounds(measured, measured.copy(x = 39.98f, width = 96.01f)))
    assertTrue(!sameThumbnailBounds(measured, measured.copy(y = 42f)))
    assertTrue(!sameThumbnailBounds(measured, null))
    assertTrue(sameThumbnailBounds(null, null))
  }

  @Test
  fun `an empty container sketches the arrangement its name promises`() {
    assertEquals(ContainerSchematic.Stacked, emptyContainerSchematic("layout/column"))
    assertEquals(ContainerSchematic.Stacked, emptyContainerSchematic("layout/flow-column"))
    assertEquals(ContainerSchematic.SideBySide, emptyContainerSchematic("layout/row"))
    assertEquals(ContainerSchematic.SideBySide, emptyContainerSchematic("layout/flow-row"))
    assertEquals(ContainerSchematic.Grid, emptyContainerSchematic("layout/lazy-grid"))
    assertEquals(ContainerSchematic.Layered, emptyContainerSchematic("layout/box"))
    assertEquals(ContainerSchematic.Layered, emptyContainerSchematic("layout/supporting-pane"))
  }

  private fun literalValue(encoded: kotlinx.serialization.json.JsonElement): String? =
    (encoded as? kotlinx.serialization.json.JsonObject)
      ?.get("value")
      ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
      ?.content

  private fun kotlinx.serialization.json.JsonElement.jsonObjectOrEmpty() =
    (this as? kotlinx.serialization.json.JsonObject).orEmpty()

  private fun kotlinx.serialization.json.JsonObject?.orEmpty() =
    this ?: emptyMap<String, kotlinx.serialization.json.JsonElement>()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
