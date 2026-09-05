package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `m3/checkbox` and `m3/switch`, on and off, as the palette inserts and the inspector edits them.
 *
 * Four cells from two components: both arrive checked — that is their starter content — and the two
 * off states are one `checked` edit each, which is the same round trip an operator makes and the
 * one that would break if the property stopped reaching the renderer.
 */
@Preview(widthDp = 120, heightDp = 44)
@Composable
fun CatalogSelectionControlsPreview() {
  UiBuilderSurface(document = selectionControlPreviewDocument, editorOverlay = false)
}

private val PREVIEWED_CONTROLS =
  listOf(
    "m3/checkbox" to emptyList<Pair<String, String>>(),
    "m3/checkbox" to listOf("checked" to "false"),
    "m3/switch" to emptyList(),
    "m3/switch" to listOf("checked" to "false"),
  )

internal val selectionControlPreviewDocument: UiBuilderDocument by lazy {
  val reducer =
    UiBuilderEditorReducer(editorChromePreviewCatalog, actorId = "preview", clientId = "preview")
  PREVIEWED_CONTROLS.foldIndexed(
      reducer.initial(selectionControlPreviewBase, selectedNodeId = selectionPreviewCellId(0))
    ) { index, state, (componentId, properties) ->
      val selected =
        reducer.reduce(state, UiBuilderEditorEvent.SelectNode(selectionPreviewCellId(index)))
      val target = reducer.dropTarget(selected, componentId) ?: return@foldIndexed selected
      val inserted =
        reducer.reduce(selected, UiBuilderEditorEvent.InsertComponent(componentId, target))
      val nodeId = inserted.selectedNodeId ?: return@foldIndexed inserted
      properties.fold(inserted) { edited, (name, value) ->
        reducer.reduce(edited, UiBuilderEditorEvent.CommitProperty(nodeId, name, value))
      }
    }
    .document
}

private val selectionControlPreviewBase: UiBuilderDocument by lazy {
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "selection-controls-preview",
    title = "Selection controls",
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
          "widthDp" to JsonPrimitive(120),
          "heightDp" to JsonPrimitive(44),
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
    roots = listOf(SELECTION_PREVIEW_ROOT_ID),
    nodes =
      mapOf(
        SELECTION_PREVIEW_ROOT_ID to
          UiBuilderNode(
            id = SELECTION_PREVIEW_ROOT_ID,
            componentId = "m3/surface",
            properties = JsonObject(emptyMap()),
            modifiers =
              JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
            slots = mapOf("content" to listOf(SELECTION_PREVIEW_ROW_ID)),
          ),
        SELECTION_PREVIEW_ROW_ID to
          UiBuilderNode(
            id = SELECTION_PREVIEW_ROW_ID,
            componentId = "layout/row",
            properties =
              JsonObject(
                mapOf(
                  "horizontalSpacingDp" to
                    JsonObject(
                      mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(16))
                    ),
                  "verticalAlignment" to
                    JsonObject(
                      mapOf("type" to JsonPrimitive("enum"), "value" to JsonPrimitive("center"))
                    ),
                )
              ),
            modifiers = JsonArray(emptyList()),
            slots = mapOf("children" to PREVIEWED_CONTROLS.indices.map(::selectionPreviewCellId)),
          ),
      ) +
        PREVIEWED_CONTROLS.indices.associate { index ->
          val cellId = selectionPreviewCellId(index)
          cellId to
            UiBuilderNode(
              id = cellId,
              componentId = "layout/box",
              properties = JsonObject(emptyMap()),
              modifiers = JsonArray(emptyList()),
              slots = mapOf("children" to emptyList()),
            )
        },
  )
}

private fun selectionPreviewCellId(index: Int): String = "selection-preview-cell-${index + 1}"

private const val SELECTION_PREVIEW_ROOT_ID = "selection-preview-surface"

private const val SELECTION_PREVIEW_ROW_ID = "selection-preview-row"
