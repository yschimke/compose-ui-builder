package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A tab row whose selection is a state variable, drawn at the value that variable holds.
 *
 * Wired into the preview lane so the next change to how a bound selection is read is diffed rather
 * than noticed: the variable starts at `1`, so this frame is `Discover` selected and its indicator
 * under `Discover`. Before the row and the tab read state, both drew the literal the design was
 * saved with — the indicator sat under `Home` however the variable moved, which is what a tab row
 * exists not to do.
 */
@Preview(widthDp = 360, heightDp = 96)
@Composable
fun TabRowStateSelectionPreview() {
  UiBuilderSurface(document = tabRowSelectionPreviewDocument, editorOverlay = false)
}

private val TAB_LABELS = listOf("Home", "Discover")

internal val tabRowSelectionPreviewDocument: UiBuilderDocument by lazy {
  val tabIds = TAB_LABELS.indices.map { "tab-$it" }
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "tab-row-selection-preview",
    title = "Tab row selection",
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
          "widthDp" to JsonPrimitive(360),
          "heightDp" to JsonPrimitive(96),
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
    stateVariables =
      JsonObject(
        mapOf(
          "selectedTab" to
            JsonObject(
              mapOf(
                "valueType" to JsonPrimitive("int"),
                "initialValue" to JsonPrimitive(1),
              )
            )
        )
      ),
    roots = listOf("root"),
    nodes =
      buildMap {
        put(
          "root",
          UiBuilderNode(
            id = "root",
            componentId = "m3/primary-tab-row",
            properties =
              JsonObject(
                mapOf(
                  "selectedIndex" to
                    JsonObject(
                      mapOf(
                        "type" to JsonPrimitive("state"),
                        "variable" to JsonPrimitive("selectedTab"),
                      )
                    )
                )
              ),
            slots = mapOf("tabs" to tabIds),
          ),
        )
        TAB_LABELS.forEachIndexed { index, label ->
          val id = tabIds[index]
          put(
            id,
            UiBuilderNode(
              id = id,
              componentId = "m3/tab",
              properties =
                JsonObject(
                  mapOf(
                    "selected" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("stateEquals"),
                          "variable" to JsonPrimitive("selectedTab"),
                          "value" to JsonPrimitive(index),
                        )
                      )
                  )
                ),
              eventBindings =
                JsonObject(
                  mapOf(
                    "click" to
                      JsonArray(
                        listOf(
                          JsonObject(
                            mapOf(
                              "type" to JsonPrimitive("set"),
                              "variable" to JsonPrimitive("selectedTab"),
                              "value" to JsonPrimitive(index),
                            )
                          )
                        )
                      )
                  )
                ),
              slots = mapOf("text" to listOf("$id-label")),
            ),
          )
          put(
            "$id-label",
            UiBuilderNode(
              id = "$id-label",
              componentId = "m3/text",
              properties =
                JsonObject(mapOf("text" to JsonObject(mapOf("value" to JsonPrimitive(label))))),
            ),
          )
        }
      },
  )
}
