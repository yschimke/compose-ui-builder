package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The four components that had a renderer and an emitter and no capability, as the palette inserts
 * them.
 *
 * Four cells rather than five: `m3/tab` is not dropped on its own, it arrives inside the tab row,
 * which is exactly what its starter content is for.
 *
 * Built through the reducer for the same reason the other component previews are: it is a picture
 * of what an insert produces, so seeding one of these badly changes this render rather than going
 * unnoticed.
 */
@Preview(widthDp = 460, heightDp = 120)
@Composable
fun CatalogUnadvertisedComponentsPreview() {
  UiBuilderSurface(document = unadvertisedComponentPreviewDocument, editorOverlay = false)
}

private val PREVIEWED_COMPONENT_IDS =
  listOf("m3/center-aligned-top-app-bar", "m3/primary-tab-row", "m3/list-item", "shape/colour-dot")

internal val unadvertisedComponentPreviewDocument: UiBuilderDocument by lazy {
  val reducer =
    UiBuilderEditorReducer(editorChromePreviewCatalog, actorId = "preview", clientId = "preview")
  PREVIEWED_COMPONENT_IDS.foldIndexed(
      reducer.initial(unadvertisedPreviewBase, selectedNodeId = unadvertisedPreviewCellId(0))
    ) { index, state, componentId ->
      val selected =
        reducer.reduce(state, UiBuilderEditorEvent.SelectNode(unadvertisedPreviewCellId(index)))
      val target = reducer.dropTarget(selected, componentId) ?: return@foldIndexed selected
      reducer.reduce(selected, UiBuilderEditorEvent.InsertComponent(componentId, target))
    }
    .document
}

private val unadvertisedPreviewBase: UiBuilderDocument by lazy {
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "unadvertised-components-preview",
    title = "Newly advertised components",
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
          "heightDp" to JsonPrimitive(120),
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
    roots = listOf(UNADVERTISED_PREVIEW_ROOT_ID),
    nodes =
      mapOf(
        UNADVERTISED_PREVIEW_ROOT_ID to
          UiBuilderNode(
            id = UNADVERTISED_PREVIEW_ROOT_ID,
            componentId = "m3/surface",
            properties = JsonObject(emptyMap()),
            modifiers =
              JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
            slots = mapOf("content" to listOf(UNADVERTISED_PREVIEW_COLUMN_ID)),
          ),
        UNADVERTISED_PREVIEW_COLUMN_ID to
          UiBuilderNode(
            id = UNADVERTISED_PREVIEW_COLUMN_ID,
            componentId = "layout/column",
            properties =
              JsonObject(
                mapOf(
                  "verticalSpacingDp" to
                    JsonObject(
                      mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(12))
                    )
                )
              ),
            modifiers = JsonArray(emptyList()),
            slots =
              mapOf("children" to PREVIEWED_COMPONENT_IDS.indices.map(::unadvertisedPreviewCellId)),
          ),
      ) +
        PREVIEWED_COMPONENT_IDS.indices.associate { index ->
          val cellId = unadvertisedPreviewCellId(index)
          cellId to
            UiBuilderNode(
              id = cellId,
              componentId = "layout/box",
              properties = JsonObject(emptyMap()),
              modifiers =
                JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxWidth"))))),
              slots = mapOf("children" to emptyList()),
            )
        },
  )
}

private fun unadvertisedPreviewCellId(index: Int): String = "unadvertised-preview-cell-${index + 1}"

private const val UNADVERTISED_PREVIEW_ROOT_ID = "unadvertised-preview-surface"

private const val UNADVERTISED_PREVIEW_COLUMN_ID = "unadvertised-preview-column"
