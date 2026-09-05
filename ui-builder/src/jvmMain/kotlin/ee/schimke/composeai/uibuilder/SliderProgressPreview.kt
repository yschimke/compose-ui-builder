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
@Preview(widthDp = 160, heightDp = 56)
@Composable
fun CatalogSliderProgressPreview() {
  UiBuilderSurface(document = sliderProgressPreviewDocument, editorOverlay = false)
}

private val PREVIEWED_CONTROLS =
  listOf(
    "m3/slider" to emptyList<Pair<String, String>>(),
    "m3/progress-indicator" to emptyList(),
    "m3/progress-indicator" to listOf("variant" to "circular"),
    // Circular for the indeterminate cell: at the frozen first frame Material's linear form has not
    // yet moved its bar, so it draws an empty track — an honest still that reads as a broken
    // component, where the circular one draws its starting arc.
    "m3/progress-indicator" to listOf("variant" to "circular", "indeterminate" to "true"),
  )

internal val sliderProgressPreviewDocument: UiBuilderDocument by lazy {
  val reducer =
    UiBuilderEditorReducer(editorChromePreviewCatalog, actorId = "preview", clientId = "preview")
  PREVIEWED_CONTROLS.foldIndexed(
      reducer.initial(sliderProgressPreviewBase, selectedNodeId = sliderProgressPreviewCellId(0))
    ) { index, state, (componentId, properties) ->
      val selected =
        reducer.reduce(state, UiBuilderEditorEvent.SelectNode(sliderProgressPreviewCellId(index)))
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

private val sliderProgressPreviewBase: UiBuilderDocument by lazy {
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "slider-progress-preview",
    title = "Slider and progress",
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
          "widthDp" to JsonPrimitive(160),
          "heightDp" to JsonPrimitive(56),
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
    roots = listOf(SLIDER_PROGRESS_PREVIEW_ROOT_ID),
    nodes =
      mapOf(
        SLIDER_PROGRESS_PREVIEW_ROOT_ID to
          UiBuilderNode(
            id = SLIDER_PROGRESS_PREVIEW_ROOT_ID,
            componentId = "m3/surface",
            properties = JsonObject(emptyMap()),
            modifiers =
              JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
            slots = mapOf("content" to listOf(SLIDER_PROGRESS_PREVIEW_ROW_ID)),
          ),
        SLIDER_PROGRESS_PREVIEW_ROW_ID to
          UiBuilderNode(
            id = SLIDER_PROGRESS_PREVIEW_ROW_ID,
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
            slots =
              mapOf("children" to PREVIEWED_CONTROLS.indices.map(::sliderProgressPreviewCellId)),
          ),
      ) +
        PREVIEWED_CONTROLS.indices.associate { index ->
          val cellId = sliderProgressPreviewCellId(index)
          cellId to
            UiBuilderNode(
              id = cellId,
              componentId = "layout/box",
              properties = JsonObject(emptyMap()),
              // A width per cell, because a slider and a linear indicator both fill whatever they
              // are given: unconstrained, the first one takes the row and the rest of the evidence
              // is off the frame.
              modifiers =
                JsonArray(
                  listOf(
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("size"),
                        "widthDp" to JsonPrimitive(if (index >= 2) 48 else 96),
                        "heightDp" to JsonPrimitive(48),
                      )
                    )
                  )
                ),
              slots = mapOf("children" to emptyList()),
            )
        },
  )
}

private fun sliderProgressPreviewCellId(index: Int): String =
  "slider-progress-preview-cell-${index + 1}"

private const val SLIDER_PROGRESS_PREVIEW_ROOT_ID = "slider-progress-preview-surface"

private const val SLIDER_PROGRESS_PREVIEW_ROW_ID = "slider-progress-preview-row"
