package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.SettledPreview
import ee.schimke.composeai.uibuilder.editor.EditorCanvasView
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The canvas in its device view, with a row far down a long list selected.
 *
 * Three things only this state shows, and all of them are the feature: the frame stays the phone's
 * height and its list has been scrolled to the selected row; the list comes out beside the frame
 * whole, every row of it drawn and selectable; and the connectors run from the few rows the device
 * shows to the whole list, so the eye reads the two as one thing.
 */
// Settled: the frame scrolls to the selection and the pop-out measures its length a frame or two
// after the first composition, and the first frame shows neither.
@SettledPreview
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderDeviceViewPopOutPreview() {
  UiBuilderEditor(
    document = deviceViewPreviewDocument,
    catalog = editorChromePreviewCatalog,
    initialSelectedNodeId = "episode-$DEVICE_VIEW_PREVIEW_SELECTED",
    initialCanvasView = EditorCanvasView.Device,
    // The editing canvas alone, so the frame and its pop-out have the width to be read at.
    openDefaultPreview = false,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

/**
 * The same on a watch: the real round screen, its list scrolled to the selected row, and that list
 * drawn whole beside it under the design's Wear theme.
 */
@SettledPreview
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderWearDeviceViewPopOutPreview() {
  UiBuilderEditor(
    document = wearDeviceViewPreviewDocument,
    catalog = editorChromePreviewWearCatalog,
    initialSelectedNodeId = "row-$WEAR_DEVICE_VIEW_PREVIEW_SELECTED",
    initialCanvasView = EditorCanvasView.Device,
    openDefaultPreview = false,
    exportHost = PREVIEW_EXPORT_HOST,
  )
}

private const val WEAR_DEVICE_VIEW_PREVIEW_ROWS = 8

private const val WEAR_DEVICE_VIEW_PREVIEW_SELECTED = 6

/** A round screen: a header over a transforming list of buttons, a few watches tall. */
private val wearDeviceViewPreviewDocument: UiBuilderDocument by lazy {
  val rows =
    (1..WEAR_DEVICE_VIEW_PREVIEW_ROWS).joinToString(",") { index ->
      val after =
        if (index == 1) "\"afterNodeId\": \"header\"," else "\"afterNodeId\": \"row-${index - 1}\","
      """
      {"operationId": "row-$index", "type": "insertNode",
       "parent": {"nodeId": "list", "slot": "items"}, $after
       "node": {"id": "row-$index", "componentId": "wear-m3/button",
                "properties": {"variant": {"type": "enum", "value": "filled-tonal"}},
                "modifiers": [{"type": "fillMaxWidth"}]}},
      {"operationId": "row-$index-label", "type": "insertNode",
       "parent": {"nodeId": "row-$index", "slot": "content"},
       "node": {"id": "row-$index-label", "componentId": "wear-m3/text",
                "properties": {"text": {"type": "string", "value": "Playlist $index"}}}}
      """
    }
  UiBuilderReducer.replay(
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "wear-device-view-preview",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Playlists",
                "catalogPin": {
                  "systemId": "wear-m3",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {}
              },
              {"operationId": "screen", "type": "insertNode", "parent": null,
               "node": {"id": "screen", "componentId": "wear-m3/screen-scaffold",
                        "properties": {"timeText": {"type": "string", "value": "10:10"}}}},
              {"operationId": "list", "type": "insertNode",
               "parent": {"nodeId": "screen", "slot": "content"},
               "node": {"id": "list", "componentId": "wear-m3/transforming-lazy-column",
                        "properties": {"verticalSpacingDp": {"type": "float", "value": 4}},
                        "modifiers": [{"type": "fillMaxSize"}]}},
              {"operationId": "header", "type": "insertNode",
               "parent": {"nodeId": "list", "slot": "items"},
               "node": {"id": "header", "componentId": "wear-m3/list-header",
                        "properties": {"text": {"type": "string", "value": "Playlists"}}}},
              $rows
            ]
          }
          """
        )
        .jsonObject
    )
    .document
}

private const val DEVICE_VIEW_PREVIEW_EPISODES = 12

private const val DEVICE_VIEW_PREVIEW_SELECTED = 11

/** A phone screen: a title bar over a lazy column of episode cards, twice the frame's height. */
private val deviceViewPreviewDocument: UiBuilderDocument by lazy {
  val episodes =
    (1..DEVICE_VIEW_PREVIEW_EPISODES).joinToString(",") { index ->
      val after = if (index == 1) "" else "\"afterNodeId\": \"episode-${index - 1}\","
      """
      {"operationId": "episode-$index", "type": "insertNode",
       "parent": {"nodeId": "episodes", "slot": "items"}, $after
       "node": {"id": "episode-$index", "componentId": "m3/card",
                "properties": {"containerColor": {"type": "colorToken", "value": "surfaceContainer"}},
                "modifiers": [{"type": "fillMaxWidth"}]}},
      {"operationId": "episode-$index-column", "type": "insertNode",
       "parent": {"nodeId": "episode-$index", "slot": "content"},
       "node": {"id": "episode-$index-column", "componentId": "layout/column",
                "modifiers": [{"type": "padding", "startDp": 16, "topDp": 12,
                               "endDp": 16, "bottomDp": 12}]}},
      {"operationId": "episode-$index-title", "type": "insertNode",
       "parent": {"nodeId": "episode-$index-column", "slot": "children"},
       "node": {"id": "episode-$index-title", "componentId": "m3/text",
                "properties": {
                  "text": {"type": "string", "value": "Episode $index"},
                  "style": {"type": "typographyToken", "value": "titleMedium"}}}},
      {"operationId": "episode-$index-podcast", "type": "insertNode",
       "parent": {"nodeId": "episode-$index-column", "slot": "children"},
       "afterNodeId": "episode-$index-title",
       "node": {"id": "episode-$index-podcast", "componentId": "m3/text",
                "properties": {
                  "text": {"type": "string", "value": "Android Developers Backstage"},
                  "style": {"type": "typographyToken", "value": "bodyMedium"},
                  "color": {"type": "colorToken", "value": "onSurfaceVariant"}}}}
      """
    }
  UiBuilderReducer.replay(
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "device-view-preview",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Episodes",
                "catalogPin": {
                  "systemId": "m3-catalog",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 360, "heightDp": 640, "density": 2.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {}
              },
              {"operationId": "surface", "type": "insertNode", "parent": null,
               "node": {"id": "surface", "componentId": "m3/surface",
                        "properties": {
                          "containerColor": {"type": "colorToken", "value": "background"}},
                        "modifiers": [{"type": "fillMaxSize"}]}},
              {"operationId": "screen", "type": "insertNode",
               "parent": {"nodeId": "surface", "slot": "content"},
               "node": {"id": "screen", "componentId": "layout/scaffold",
                        "modifiers": [{"type": "fillMaxSize"}]}},
              {"operationId": "title-bar", "type": "insertNode",
               "parent": {"nodeId": "screen", "slot": "topBar"},
               "node": {"id": "title-bar", "componentId": "layout/row",
                        "modifiers": [{"type": "fillMaxWidth"},
                                      {"type": "padding", "startDp": 24, "topDp": 20,
                                       "endDp": 24, "bottomDp": 12}]}},
              {"operationId": "title", "type": "insertNode",
               "parent": {"nodeId": "title-bar", "slot": "children"},
               "node": {"id": "title", "componentId": "m3/text",
                        "properties": {
                          "text": {"type": "string", "value": "Latest episodes"},
                          "style": {"type": "typographyToken", "value": "titleLarge"}}}},
              {"operationId": "episodes", "type": "insertNode",
               "parent": {"nodeId": "screen", "slot": "content"},
               "node": {"id": "episodes", "componentId": "layout/lazy-column",
                        "properties": {
                          "contentPadding": {"type": "padding", "startDp": 16, "topDp": 8,
                                             "endDp": 16, "bottomDp": 16},
                          "verticalSpacingDp": {"type": "float", "value": 12.0}},
                        "modifiers": [{"type": "fillMaxSize"}]}},
              $episodes
            ]
          }
          """
        )
        .jsonObject
    )
    .document
}
