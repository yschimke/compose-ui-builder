package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.UiBuilderCanvasAddonApi
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvasAdapterIds
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.frameGeometry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun DesignGmailTabletPreview() = DesignFixture("google-gmail-tablet")

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun DesignPhotosTabletPreview() = DesignFixture("google-photos-tablet")

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun DesignCalendarTabletPreview() = DesignFixture("google-calendar-tablet")

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun DesignKeepTabletPreview() = DesignFixture("google-keep-tablet")

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun DesignPlayTabletPreview() = DesignFixture("google-play-tablet")

@Composable
private fun DesignFixture(designId: String) {
  PinnedCatalog(designFixtureDocument(designId)) {
    UiBuilderSurface(document = designFixtureDocument(designId), editorOverlay = false)
  }
}

/**
 * [content] under the canvas adapters, frame geometry and platform of the catalog [document] pins,
 * the way the editor provides them from the catalog it serves.
 *
 * A preview is the host here, and a host that declares nothing gets nothing: a `wear-m3` screen
 * root has no drawing of its own — its catalog names `frame/round-screen` for it — so every Wear
 * fixture rendered as the red "Unsupported component: wear-m3/screen-scaffold" box and a black
 * frame, while the tests that compose the same documents (`WearCatalogAdapters`) drew them
 * correctly. Limited to catalogs that are not mobile: the phone and tablet fixtures have always
 * rendered without their catalog's adapters, and those renders are the visual-diff baseline.
 */
@UiBuilderCanvasAddonApi
@Composable
fun PinnedCatalog(document: UiBuilderDocument, content: @Composable () -> Unit) {
  val systemId =
    (document.catalogPin["systemId"] as? JsonPrimitive)?.contentOrNull ?: return content()
  val catalog =
    pinnedCatalogs.getOrPut(systemId) {
      UiBuilderDocument::class
        .java
        .getResource("/$systemId-capabilities-v1.json")
        ?.readText()
        ?.let { CapabilityCatalogParser.parse(it) }
    }
  if (catalog == null || catalog.platform == UiBuilderCatalogPlatform.MOBILE) return content()
  CompositionLocalProvider(
    LocalUiBuilderCanvasAdapters provides catalog.canvasAdapterIds,
    LocalUiBuilderFrameGeometry provides catalog.frameGeometry,
    LocalUiBuilderCatalogPlatform provides catalog.platform.wireValue,
    content = content,
  )
}

private val pinnedCatalogs = HashMap<String, CapabilityCatalog?>()

/** The fixture `designs/<designId>.json`, replayed once per process. */
@UiBuilderCanvasAddonApi
fun designFixtureDocument(designId: String): UiBuilderDocument =
  designFixtureDocuments.getOrPut(designId) {
    UiBuilderReducer.replay(
        Json.parseToJsonElement(previewResource("/designs/$designId.json")).jsonObject
      )
      .document
  }

private val designFixtureDocuments = HashMap<String, UiBuilderDocument>()
