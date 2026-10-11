package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.SettledPreview
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
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
 * The editor's own screens, drawn as designs in the editor's own catalog. Each preview replays one
 * operations fixture from `docs/design/fixtures/ui-builder/designs/` through [UiBuilderReducer] and
 * renders it with [UiBuilderSurface]; `DesignFixturesTest` keeps the files valid and exportable.
 *
 * One function per fixture (previews are discovered by name), each with a device spec restating the
 * fixture's `environment`. The `dpi` (density × 160) matters: the renderer composes at the
 * document's density, so a mismatched frame would waste most of the capture. `DesignFixturesTest`
 * holds them together.
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

// Settled, as `WearPreviewSmallRound` is: the real `ScreenScaffold` grows its edge button in, so
// a first-frame capture of a Wear design has none.
@SettledPreview
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignGoogleHomeWearPreview() = DesignFixture("google-home-wear")

@SettledPreview
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignWearStarterGreetingPreview() = DesignFixture("wear-starter-greeting")

@SettledPreview
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignWearStarterListPreview() = DesignFixture("wear-starter-list")

@SettledPreview
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignJetcasterWearLibraryPreview() = DesignFixture("jetcaster-wear-library")

@SettledPreview
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignJetcasterWearEpisodePreview() = DesignFixture("jetcaster-wear-episode")

@SettledPreview
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignJetcasterWearQueuePreview() = DesignFixture("jetcaster-wear-queue")

@Composable
private fun DesignFixture(designId: String) {
  PinnedCatalog(designFixtureDocument(designId)) {
    UiBuilderSurface(document = designFixtureDocument(designId), editorOverlay = false)
  }
}

/**
 * [content] under the canvas adapters, frame geometry and platform of the catalog [document] pins,
 * as the editor provides them. Without them a `wear-m3` root (drawn via `frame/round-screen`)
 * renders as "Unsupported component". Limited to non-mobile catalogs, whose renders are the
 * visual-diff baseline without adapters.
 */
@Composable
internal fun PinnedCatalog(document: UiBuilderDocument, content: @Composable () -> Unit) {
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
internal fun designFixtureDocument(designId: String): UiBuilderDocument =
  designFixtureDocuments.getOrPut(designId) {
    UiBuilderReducer.replay(
        Json.parseToJsonElement(previewResource("/designs/$designId.json")).jsonObject
      )
      .document
  }

private val designFixtureDocuments = HashMap<String, UiBuilderDocument>()

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun DesignDocsTabletPreview() = DesignFixture("google-docs-tablet")
