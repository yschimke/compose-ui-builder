package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The two surfaces a component pack adds to the editor, as pictures the next change is diffed on.
 *
 * ## The settings panel
 *
 * [ComponentPacksPanelPreview] is the body of the **Component packs** dialog with two packs the
 * host admitted — one on, one off. It is the same composable the dialog shows, rendered without the
 * dialog's chrome so the rows are the picture rather than the frame.
 *
 * ## The placeholder
 *
 * [PackPlaceholderPreview] is a Material 3 column holding a Confetti `SessionCard` beside the
 * catalog's own text. The canvas cannot link Confetti's classes, so the pack node is drawn as a
 * named, captioned outline — the same honest shape `wear-m3`'s native-only components take (see
 * `WearNativeOnlyPlaceholderPreview`) — and the picture of the real component comes from the native
 * lane, compiled against the `confetti-mobile` bundle. A row here that started drawing the card
 * would be a regression, not an improvement.
 */
@Preview(widthDp = 500, heightDp = 300)
@Composable
fun ComponentPacksPanelPreview() {
  MaterialTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
      ComponentPacksPanel(
        packs = previewPacks,
        enabledPacks = setOf("confetti-mobile"),
        onToggle = {},
        modifier = Modifier.padding(16.dp),
      )
    }
  }
}

@Preview(widthDp = 360, heightDp = 320)
@Composable
fun PackPlaceholderPreview() {
  CompositionLocalProvider(
    LocalUiBuilderNativeOnly provides
      previewPacks.packs.flatMapTo(mutableSetOf()) { it.componentIds }
  ) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
      UiBuilderSurface(document = packPlaceholderDocument, editorOverlay = false)
    }
  }
}

private val previewPacks =
  UiBuilderComponentPacks(
    listOf(
      UiBuilderComponentPack(
        id = "confetti-mobile",
        label = "Confetti Mobile",
        platform = UiBuilderCatalogPlatform.MOBILE,
        nativeCatalog = "confetti-mobile",
        componentIds =
          listOf(
            "confetti-mobile/session-card",
            "confetti-mobile/speaker-row",
            "confetti-mobile/conference-header",
          ),
        notes =
          "3 of 41 components in confetti-mobile's record, drawn on the canvas as placeholders " +
            "and rendered natively against the confetti-mobile bundle.",
      ),
      UiBuilderComponentPack(
        id = "jetnews",
        label = "Jetnews",
        platform = UiBuilderCatalogPlatform.MOBILE,
        nativeCatalog = "jetnews",
        componentIds = listOf("jetnews/post-card-top", "jetnews/post-list-divider"),
      ),
    )
  )

private val packPlaceholderDocument: UiBuilderDocument by lazy {
  fun text(value: String) = buildJsonObject {
    put("type", "string")
    put("value", value)
  }
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "pack-placeholder-preview",
    title = "Pack placeholder",
    revision = 1,
    catalogPin =
      buildJsonObject {
        put("systemId", "m3-catalog")
        put("catalogRevision", "candidate")
        put("capabilityDigest", "candidate")
        put("nativeRuntimeId", "candidate")
      },
    environment =
      buildJsonObject {
        put("widthDp", 360)
        put("heightDp", 320)
        put("density", 1.0)
        put("theme", "light")
        put("locale", "en-US")
        put("fontScale", 1.0)
        put("layoutDirection", "ltr")
        put("windowPosture", "flat")
        put("animations", "settled")
        put("networkAccess", false)
      },
    stateVariables = JsonObject(emptyMap()),
    roots = listOf("column"),
    nodes =
      linkedMapOf(
        "column" to
          UiBuilderNode(
            id = "column",
            componentId = "layout/column",
            modifiers =
              JsonArray(
                listOf(
                  buildJsonObject {
                    put("type", "padding")
                    put("startDp", 16)
                    put("topDp", 16)
                    put("endDp", 16)
                    put("bottomDp", 16)
                  }
                )
              ),
            slots = mapOf("children" to listOf("heading", "session", "speaker")),
          ),
        "heading" to
          UiBuilderNode(
            id = "heading",
            componentId = "m3/text",
            properties =
              JsonObject(
                mapOf(
                  "text" to text("Thursday"),
                  "style" to
                    buildJsonObject {
                      put("type", "typographyToken")
                      put("value", "titleLarge")
                    },
                )
              ),
          ),
        "session" to
          UiBuilderNode(
            id = "session",
            componentId = "confetti-mobile/session-card",
            properties =
              JsonObject(
                mapOf(
                  "title" to text("Compose everywhere"),
                  "label" to text("Compose everywhere"),
                )
              ),
            slots = mapOf("content" to listOf("speaker-name")),
          ),
        "speaker-name" to
          UiBuilderNode(
            id = "speaker-name",
            componentId = "m3/text",
            properties = JsonObject(mapOf("text" to text("Jane Doe · 10:30"))),
          ),
        "speaker" to
          UiBuilderNode(
            id = "speaker",
            componentId = "confetti-mobile/speaker-row",
            properties = JsonObject(mapOf("label" to text("Jane Doe"))),
          ),
      ),
  )
}
