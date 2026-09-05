package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The dialog, and the four faces of the two pickers, as the palette actually inserts them.
 *
 * Built by the reducer rather than authored, for the reason [StarterContentInsertPreview] is: a
 * fixture kept in step by hand stops being evidence the first time nobody remembers to update it.
 * Each cell is one `InsertComponent` followed by the property edits an operator would make in the
 * inspector — which is how the two typed modes get here without a second component id.
 *
 * It is also the determinism check with a picture attached. Both pickers default to the clock —
 * `rememberDatePickerState` opens on the current month, `rememberTimePickerState` starts at the
 * current time — so if the pinning ever came out, this render would start changing on its own and
 * the visual diff would say so on the next pull request that touched anything.
 */
@Preview(widthDp = 460, heightDp = 460)
@Composable
fun CatalogDialogAndPickersPreview() {
  UiBuilderSurface(document = dialogAndPickerPreviewDocument, editorOverlay = false)
}

/** One cell each: what to insert, and what to set on it once it is in. */
private val PREVIEWED_CELLS =
  listOf(
    PreviewCell("m3/dialog", row = 0, widthDp = 340, heightDp = 620),
    PreviewCell("m3/date-picker", row = 0, widthDp = 360, heightDp = 620),
    PreviewCell(
      "m3/date-picker",
      row = 0,
      widthDp = 360,
      heightDp = 620,
      properties = listOf("mode" to "input"),
    ),
    // A row of its own each: `TimePicker` lays its hour and minute fields out *beside* the clock
    // when it has the width for it, so the dial is about 500dp wide and the two pickers sharing a
    // row overflowed the frame and drew over each other.
    PreviewCell("m3/time-picker", row = 1, widthDp = 520, heightDp = 340),
    PreviewCell(
      "m3/time-picker",
      row = 2,
      widthDp = 520,
      heightDp = 140,
      properties = listOf("mode" to "input", "is24Hour" to "false"),
    ),
  )

private data class PreviewCell(
  val componentId: String,
  val row: Int,
  val widthDp: Int,
  val heightDp: Int,
  val properties: List<Pair<String, String>> = emptyList(),
)

internal val dialogAndPickerPreviewDocument: UiBuilderDocument by lazy {
  val reducer =
    UiBuilderEditorReducer(editorChromePreviewCatalog, actorId = "preview", clientId = "preview")
  // One editor state across every cell, moving the selection between them. Re-initialising per
  // cell looked equivalent and was not: a fresh state restarts the operation sequence, the sequence
  // names the inserted node, and the second date picker asked for an id the first one already had.
  // The reducer rejected it, correctly, and the cell rendered empty.
  PREVIEWED_CELLS.foldIndexed(
      reducer.initial(dialogAndPickerPreviewBase, selectedNodeId = pickerPreviewCellId(0))
    ) { index, state, cell ->
      val selected =
        reducer.reduce(state, UiBuilderEditorEvent.SelectNode(pickerPreviewCellId(index)))
      val target = reducer.dropTarget(selected, cell.componentId) ?: return@foldIndexed selected
      val inserted =
        reducer.reduce(selected, UiBuilderEditorEvent.InsertComponent(cell.componentId, target))
      val nodeId = inserted.selectedNodeId ?: return@foldIndexed inserted
      cell.properties.fold(inserted) { edited, (name, value) ->
        reducer.reduce(edited, UiBuilderEditorEvent.CommitProperty(nodeId, name, value))
      }
    }
    .document
}

private val dialogAndPickerPreviewBase: UiBuilderDocument by lazy {
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "dialog-and-pickers-preview",
    title = "Dialog and pickers",
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
          "widthDp" to JsonPrimitive(460),
          "heightDp" to JsonPrimitive(460),
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
    roots = listOf(PICKER_PREVIEW_ROOT_ID),
    nodes =
      mapOf(
        PICKER_PREVIEW_ROOT_ID to
          UiBuilderNode(
            id = PICKER_PREVIEW_ROOT_ID,
            componentId = "m3/surface",
            properties = JsonObject(emptyMap()),
            modifiers =
              JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
            slots = mapOf("content" to listOf(PICKER_PREVIEW_ROW_ID)),
          ),
        PICKER_PREVIEW_ROW_ID to
          UiBuilderNode(
            id = PICKER_PREVIEW_ROW_ID,
            componentId = "layout/column",
            properties = JsonObject(mapOf("verticalSpacingDp" to pickerPreviewNumber(16))),
            modifiers = JsonArray(emptyList()),
            slots =
              mapOf(
                "children" to
                  PREVIEWED_CELLS.map(PreviewCell::row).distinct().map(::pickerPreviewRowId)
              ),
          ),
      ) +
        PREVIEWED_CELLS.map(PreviewCell::row).distinct().associate { row ->
          pickerPreviewRowId(row) to
            UiBuilderNode(
              id = pickerPreviewRowId(row),
              componentId = "layout/row",
              properties =
                JsonObject(
                  mapOf(
                    "horizontalSpacingDp" to pickerPreviewNumber(16),
                    "verticalAlignment" to pickerPreviewEnum("top"),
                  )
                ),
              modifiers = JsonArray(emptyList()),
              slots =
                mapOf(
                  "children" to
                    PREVIEWED_CELLS.indices
                      .filter { PREVIEWED_CELLS[it].row == row }
                      .map(::pickerPreviewCellId)
                ),
            )
        } +
        PREVIEWED_CELLS.mapIndexed { index, cell ->
            val cellId = pickerPreviewCellId(index)
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
                          "widthDp" to JsonPrimitive(cell.widthDp),
                          "heightDp" to JsonPrimitive(cell.heightDp),
                        )
                      )
                    )
                  ),
                slots = mapOf("children" to emptyList()),
              )
          }
          .toMap(),
  )
}

private fun pickerPreviewEnum(value: String): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("enum"), "value" to JsonPrimitive(value)))

private fun pickerPreviewNumber(value: Int): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(value)))

private fun pickerPreviewCellId(index: Int): String = "picker-preview-cell-${index + 1}"

private fun pickerPreviewRowId(row: Int): String = "picker-preview-row-${row + 1}"

private const val PICKER_PREVIEW_ROOT_ID = "picker-preview-surface"

private const val PICKER_PREVIEW_ROW_ID = "picker-preview-rows"
