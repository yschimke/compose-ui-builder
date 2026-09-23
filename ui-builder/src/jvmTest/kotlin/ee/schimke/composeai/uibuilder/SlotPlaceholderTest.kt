package ee.schimke.composeai.uibuilder

import androidx.compose.ui.geometry.Rect
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSlotInspection
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The empty-slot placeholder: where it is drawn, which slots get one, and that the region it is
 * drawn at is the region a drop hits.
 *
 * Both halves are pinned here because they are one computation: the placeholder a reader aims at
 * and the slot a release lands in are the reducer's own region, so "what you see is what you hit"
 * is a property a test can hold it to.
 */
class SlotPlaceholderTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document
  private val state = reducer.initial(document)

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun pixel(rect: Rect) =
    UiBuilderPixelBounds(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)

  private fun nodeBounds(vararg pairs: Pair<String, Rect>): Map<String, UiBuilderPixelBounds> =
    pairs.associate { (id, rect) ->
      id to pixel(rect)
    }

  /** A slot's union as the renderer would report it — the children it measured. */
  private fun slot(parent: String, name: String, bounds: Rect) =
    UiBuilderSlotInspection(
      parentNodeId = parent,
      slotName = name,
      childNodeIds = document.nodes.getValue(parent).slots[name].orEmpty(),
      measuredChildNodeIds = document.nodes.getValue(parent).slots[name].orEmpty(),
      bounds = pixel(bounds),
    )

  private fun placeholders(
    slots: List<UiBuilderSlotInspection>,
    bounds: Map<String, UiBuilderPixelBounds>,
  ) = reducer.slotPlaceholders(state, slots, bounds)

  @Test
  fun `an empty single-slot container invites a drop anywhere in itself`() {
    val found =
      placeholders(
        slots = emptyList(),
        bounds = nodeBounds("ph-empty-box" to Rect(0f, 0f, 100f, 50f)),
      )

    val box = found.single { it.target.nodeId == "ph-empty-box" }
    assertEquals(ParentSlot("ph-empty-box", "children"), box.target)
    assertEquals(pixel(Rect(0f, 0f, 100f, 50f)), box.bounds)
  }

  @Test
  fun `only the recommended slot of a many-slot container is drawn`() {
    // The scaffold declares topBar, snackbarHost and content; all three are empty, and all three
    // would claim the scaffold's box. Only content is recommended, so only content is drawn.
    val found =
      placeholders(
        slots = emptyList(),
        bounds = nodeBounds("ph-scaffold" to Rect(0f, 0f, 320f, 640f)),
      )

    // The fixture's empty Box invites a drop too; this is about the scaffold's slots.
    val scaffold = found.single { it.target.nodeId == "ph-scaffold" }
    assertEquals("content", scaffold.target.slot)
    assertEquals(pixel(Rect(0f, 0f, 320f, 640f)), scaffold.bounds)
  }

  @Test
  fun `a populated slot has no placeholder, and the empty content is the body below the bar`() {
    // The top bar is filled; content is empty. Its region is the scaffold minus the top bar's
    // strip, which is both what the placeholder draws and what a drop there hits.
    val found =
      placeholders(
        slots = listOf(slot("ph-scaffold", "topBar", Rect(0f, 0f, 320f, 64f))),
        bounds =
          nodeBounds(
            "ph-scaffold" to Rect(0f, 0f, 320f, 640f),
            "ph-topbar" to Rect(0f, 0f, 320f, 64f),
          ),
      )

    val scaffold = found.single { it.target.nodeId == "ph-scaffold" }
    assertEquals("content", scaffold.target.slot)
    assertEquals(pixel(Rect(0f, 64f, 320f, 640f)), scaffold.bounds)
  }

  @Test
  fun `a populated container has no placeholder`() {
    // The column holds a text, so its children slot is not empty — and it is the only slot.
    val found =
      placeholders(
        slots = listOf(slot("ph-column", "children", Rect(0f, 0f, 100f, 50f))),
        bounds =
          nodeBounds(
            "ph-column" to Rect(0f, 0f, 100f, 50f),
            "ph-a" to Rect(0f, 0f, 100f, 24f),
          ),
      )

    assertTrue(found.isEmpty(), "a populated slot has nothing to invite: $found")
  }

  @Test
  fun `a drop in an empty scaffold's body lands in content, not in whichever slot came first`() {
    val plan =
      reducer.catalogDropPlan(
        state,
        "layout/box",
        emptyList(),
        nodeBounds("ph-scaffold" to Rect(0f, 0f, 320f, 640f)),
        pointX = 160f,
        pointY = 320f,
      )

    // All three slots claim the scaffold and only content is recommended, so the tie is decided by
    // the catalog rather than by enumeration order.
    assertEquals(ParentSlot("ph-scaffold", "content"), plan?.target)
  }

  @Test
  fun `an empty content below a populated bar is the body, not the whole scaffold`() {
    // The top bar's strip is taken out of the content's region: a point in the body resolves to
    // content, and a point in the strip does not — which is the inference this pins.
    val bounds =
      nodeBounds(
        "ph-scaffold" to Rect(0f, 0f, 320f, 640f),
        "ph-topbar" to Rect(0f, 0f, 320f, 64f),
      )
    val slots = listOf(slot("ph-scaffold", "topBar", Rect(0f, 0f, 320f, 64f)))

    assertEquals(
      ParentSlot("ph-scaffold", "content"),
      reducer.catalogDropPlan(state, "layout/box", slots, bounds, 160f, 320f)?.target,
    )
    assertTrue(
      reducer.catalogDropPlan(state, "layout/box", slots, bounds, 160f, 20f)?.target !=
        ParentSlot("ph-scaffold", "content"),
      "the top bar's strip is not part of the content's region",
    )
  }

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "slot-placeholder-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Placeholder fixture",
                "catalogPin": {
                  "systemId": "m3-catalog",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 320, "heightDp": 640, "density": 1.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {}
              },
              {
                "operationId": "scaffold",
                "type": "insertNode",
                "parent": null,
                "node": {"id": "ph-scaffold", "componentId": "layout/scaffold"}
              },
              {
                "operationId": "topbar",
                "type": "insertNode",
                "parent": {"nodeId": "ph-scaffold", "slot": "topBar"},
                "node": {"id": "ph-topbar", "componentId": "layout/box"}
              },
              {
                "operationId": "empty-box",
                "type": "insertNode",
                "parent": null,
                "node": {"id": "ph-empty-box", "componentId": "layout/box"}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": null,
                "node": {"id": "ph-column", "componentId": "layout/column"}
              },
              {
                "operationId": "a",
                "type": "insertNode",
                "parent": {"nodeId": "ph-column", "slot": "children"},
                "node": {
                  "id": "ph-a",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "A"}}
                }
              }
            ]
          }
          """
            .trimIndent()
        )
        .jsonObject
  }
}
