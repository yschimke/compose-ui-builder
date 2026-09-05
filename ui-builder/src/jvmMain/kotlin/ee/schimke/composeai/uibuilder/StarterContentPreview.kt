package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the palette actually puts on the canvas, for six containers in a row.
 *
 * The document is not authored: every child under the row is produced by the same `InsertComponent`
 * the palette dispatches, through the same reducer, against the same catalog. So this preview is a
 * picture of the product's behaviour rather than of a fixture somebody kept in step with it — seed
 * a component badly and this render changes, which is the point of diffing it.
 *
 * Rendered before and after [StarterContent] landed, it is the before/after evidence for it: the
 * before frame is six containers holding a placeholder or nothing at all, and the after frame is
 * six containers holding what a person would have drawn.
 */
@Preview(widthDp = 1280, heightDp = 260)
@Composable
fun StarterContentInsertPreview() {
  UiBuilderSurface(document = starterContentPreviewDocument, editorOverlay = false)
}

/** The components worth showing: one of each shape starter content has an opinion about. */
private val PREVIEWED_COMPONENT_IDS =
  listOf(
    "m3/icon-button",
    "m3/button",
    "m3/filter-chip",
    "m3/card",
    "m3/search-bar",
    "layout/lazy-column",
  )

internal val starterContentPreviewDocument: UiBuilderDocument by lazy {
  val reducer =
    UiBuilderEditorReducer(editorChromePreviewCatalog, actorId = "preview", clientId = "preview")
  // One cell per component, so each insert is measured against the same box and a container that
  // fills what it is given — a card, a list — cannot swallow the row.
  PREVIEWED_COMPONENT_IDS.foldIndexed(starterContentPreviewBase) { index, document, componentId ->
    val cellId = starterPreviewCellId(index)
    val state = reducer.initial(document, selectedNodeId = cellId)
    val target = reducer.dropTarget(state, componentId) ?: return@foldIndexed document
    reducer.reduce(state, UiBuilderEditorEvent.InsertComponent(componentId, target)).document
  }
}

/** One surface holding one row: the frame the inserts land in, and nothing else. */
private val starterContentPreviewBase: UiBuilderDocument by lazy {
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "starter-content-preview",
    title = "Starter content",
    revision = 0,
    catalogPin =
      JsonObject(
        mapOf(
          "systemId" to JsonPrimitive("m3-catalog"),
          "catalogRevision" to JsonPrimitive("candidate"),
          "capabilityDigest" to JsonPrimitive("candidate"),
          "nativeRuntimeId" to JsonPrimitive("candidate"),
        )
      ),
    environment =
      JsonObject(
        mapOf(
          "widthDp" to JsonPrimitive(1280),
          "heightDp" to JsonPrimitive(260),
          "density" to JsonPrimitive(1.0),
          "theme" to JsonPrimitive("light"),
          "dynamicColor" to JsonPrimitive(false),
          "locale" to JsonPrimitive("en-US"),
          "fontScale" to JsonPrimitive(1.0),
          "layoutDirection" to JsonPrimitive("ltr"),
          "windowPosture" to JsonPrimitive("flat"),
          "browserZoomPercent" to JsonPrimitive(100),
          "fixedTime" to JsonPrimitive("2024-05-16T12:00:00Z"),
          "animations" to JsonPrimitive("settled"),
          "networkAccess" to JsonPrimitive(false),
        )
      ),
    stateVariables = JsonObject(emptyMap()),
    roots = listOf(STARTER_PREVIEW_ROOT_ID),
    nodes =
      mapOf(
        STARTER_PREVIEW_ROOT_ID to
          UiBuilderNode(
            id = STARTER_PREVIEW_ROOT_ID,
            componentId = "m3/surface",
            properties = JsonObject(emptyMap()),
            modifiers =
              JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
            slots = mapOf("content" to listOf(STARTER_PREVIEW_ROW_ID)),
          ),
        STARTER_PREVIEW_ROW_ID to
          UiBuilderNode(
            id = STARTER_PREVIEW_ROW_ID,
            componentId = "layout/row",
            properties =
              JsonObject(
                mapOf(
                  "horizontalSpacingDp" to starterPreviewNumber(16),
                  "verticalAlignment" to starterPreviewEnum("top"),
                )
              ),
            modifiers = JsonArray(listOf(starterPreviewPadding(24))),
            slots =
              mapOf("children" to PREVIEWED_COMPONENT_IDS.indices.map(::starterPreviewCellId)),
          ),
      ) +
        PREVIEWED_COMPONENT_IDS.indices.associate { index ->
          val cellId = starterPreviewCellId(index)
          cellId to
            UiBuilderNode(
              id = cellId,
              componentId = "layout/box",
              properties = JsonObject(emptyMap()),
              modifiers =
                JsonArray(
                  listOf(
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("size"),
                        "widthDp" to JsonPrimitive(STARTER_PREVIEW_CELL_WIDTH_DP),
                        "heightDp" to JsonPrimitive(STARTER_PREVIEW_CELL_HEIGHT_DP),
                      )
                    )
                  )
                ),
              slots = mapOf("children" to emptyList()),
            )
        },
  )
}

private fun starterPreviewEnum(value: String): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("enum"), "value" to JsonPrimitive(value)))

private fun starterPreviewNumber(value: Int): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(value)))

private fun starterPreviewPadding(dp: Int): JsonObject =
  JsonObject(
    mapOf(
      "type" to JsonPrimitive("padding"),
      "startDp" to JsonPrimitive(dp),
      "topDp" to JsonPrimitive(dp),
      "endDp" to JsonPrimitive(dp),
      "bottomDp" to JsonPrimitive(dp),
    )
  )

private fun starterPreviewCellId(index: Int): String = "starter-preview-cell-${index + 1}"

private const val STARTER_PREVIEW_CELL_WIDTH_DP = 190

private const val STARTER_PREVIEW_CELL_HEIGHT_DP = 200

private const val STARTER_PREVIEW_ROOT_ID = "starter-preview-surface"
private const val STARTER_PREVIEW_ROW_ID = "starter-preview-row"
