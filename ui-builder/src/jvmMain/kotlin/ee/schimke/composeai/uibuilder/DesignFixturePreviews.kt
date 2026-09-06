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
 * One function per fixture, because a `@Preview` is discovered by name. Each frame is declared as a
 * device spec restating that fixture's own `environment` — its dp size and, as `dpi`, its density
 * times 160 — because the annotation cannot read the document.
 *
 * The `dpi` is the load-bearing half. `UiBuilderRenderer` composes a document at the density the
 * document names and falls back to the surrounding platform's only when it names none, so a design
 * pinning `density: 1.0` inside the renderer's default 2.625x frame draws its 1600dp of chrome into
 * the top-left 1600px of a 4200px capture and leaves the rest empty — the same pixels, most of the
 * image wasted, and most of the visual diff's area with it. Declaring the frame at the design's own
 * density makes the two agree by construction. `DesignFixturesTest` holds them together.
 */
@Preview(device = "spec:width=1280dp,height=900dp,dpi=160")
@Composable
fun DesignNewDesignDialogPreview() = DesignFixture("ui-builder-new-design-dialog")

@Preview(device = "spec:width=1280dp,height=900dp,dpi=160")
@Composable
fun DesignNewDesignScreenPreview() = DesignFixture("ui-builder-new-design-screen")

@Preview(device = "spec:width=1280dp,height=900dp,dpi=160")
@Composable
fun DesignShortcutsDialogPreview() = DesignFixture("ui-builder-shortcuts-dialog")

@Preview(device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun DesignLayersIssuesPreview() = DesignFixture("ui-builder-layers-issues")

@Preview(device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun DesignCodePanePreview() = DesignFixture("ui-builder-code-pane")

@Preview(device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun DesignScreenDockPreview() = DesignFixture("ui-builder-screen-dock")

@Preview(device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun DesignThemeDockPreview() = DesignFixture("ui-builder-theme-dock")

@Preview(device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun DesignTalkDockPreview() = DesignFixture("ui-builder-talk-dock")

@Preview(device = "spec:width=412dp,height=915dp,dpi=160")
@Composable
fun DesignMobilePreview() = DesignFixture("ui-builder-mobile")

@Preview(device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun DesignNativeBothPreview() = DesignFixture("ui-builder-native-both")

@Preview(device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun DesignMenusPreview() = DesignFixture("ui-builder-menus")

@Preview(device = "spec:width=1280dp,height=900dp,dpi=160")
@Composable
fun DesignPacksDialogPreview() = DesignFixture("ui-builder-packs-dialog")

@Preview(device = "spec:width=1600dp,height=900dp,dpi=160")
@Composable
fun DesignInsertPacksPreview() = DesignFixture("ui-builder-insert-packs")

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
