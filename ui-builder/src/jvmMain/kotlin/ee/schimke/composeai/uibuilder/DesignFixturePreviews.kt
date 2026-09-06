package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The editor's own screens, drawn as designs in the editor's own catalog.
 *
 * Each preview replays one operations fixture from `docs/design/fixtures/ui-builder/designs/`
 * through [UiBuilderReducer] and renders it with [UiBuilderSurface] — the renderer the Wasm canvas
 * and the production export use — so a committed design is rendered by the pipeline, diffed by the
 * preview workflow, and can be opened on a live server unchanged. `DesignFixturesTest` keeps the
 * same files valid against the catalog they pin and exportable as Compose.
 *
 * One function per fixture, because a `@Preview` is discovered by name: the frame size is the
 * fixture's own `environment`, restated here because the annotation cannot read it.
 */
@Preview(widthDp = 1280, heightDp = 900)
@Composable
fun DesignNewDesignDialogPreview() = DesignFixture("ui-builder-new-design-dialog")

@Preview(widthDp = 1280, heightDp = 900)
@Composable
fun DesignNewDesignScreenPreview() = DesignFixture("ui-builder-new-design-screen")

@Preview(widthDp = 1280, heightDp = 900)
@Composable
fun DesignShortcutsDialogPreview() = DesignFixture("ui-builder-shortcuts-dialog")

@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun DesignLayersIssuesPreview() = DesignFixture("ui-builder-layers-issues")

@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun DesignCodePanePreview() = DesignFixture("ui-builder-code-pane")

@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun DesignScreenDockPreview() = DesignFixture("ui-builder-screen-dock")

@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun DesignThemeDockPreview() = DesignFixture("ui-builder-theme-dock")

@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun DesignTalkDockPreview() = DesignFixture("ui-builder-talk-dock")

@Preview(widthDp = 412, heightDp = 915)
@Composable
fun DesignMobilePreview() = DesignFixture("ui-builder-mobile")

@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun DesignNativeBothPreview() = DesignFixture("ui-builder-native-both")

@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun DesignMenusPreview() = DesignFixture("ui-builder-menus")

@Composable
private fun DesignFixture(designId: String) {
  UiBuilderSurface(document = designFixtureDocument(designId), editorOverlay = false)
}

/** The fixture `designs/<designId>.json`, replayed once per process. */
internal fun designFixtureDocument(designId: String): UiBuilderDocument =
  designFixtureDocuments.getOrPut(designId) {
    UiBuilderReducer.replay(
        Json.parseToJsonElement(previewResource("/designs/$designId.json")).jsonObject
      )
      .document
  }

private val designFixtureDocuments = HashMap<String, UiBuilderDocument>()
