package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.editor.withEnvironmentOverrides
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.JsonPrimitive

/**
 * The six Google-app sample designs, drawn at three window sizes each (expanded, medium, compact,
 * matching their `environment.exportDevices`). Unlike `DesignFixturePreviews.kt`, which uses each
 * design's own environment, these show the same document in other windows;
 * `AdaptiveSupportingPaneScaffold` computes its size class from the frame.
 */
@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun GmailExpandedPreview() = SizedDesignFixture("google-gmail-tablet")

@Preview(device = "spec:width=841dp,height=701dp,dpi=160")
@Composable
fun GmailMediumPreview() = SizedDesignFixture("google-gmail-tablet")

@Preview(device = "spec:width=411dp,height=914dp,dpi=160")
@Composable
fun GmailCompactPreview() = SizedDesignFixture("google-gmail-tablet")

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun PhotosExpandedPreview() = SizedDesignFixture("google-photos-tablet")

@Preview(device = "spec:width=841dp,height=701dp,dpi=160")
@Composable
fun PhotosMediumPreview() = SizedDesignFixture("google-photos-tablet")

@Preview(device = "spec:width=411dp,height=914dp,dpi=160")
@Composable
fun PhotosCompactPreview() = SizedDesignFixture("google-photos-tablet")

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun CalendarExpandedPreview() = SizedDesignFixture("google-calendar-tablet")

@Preview(device = "spec:width=841dp,height=701dp,dpi=160")
@Composable
fun CalendarMediumPreview() = SizedDesignFixture("google-calendar-tablet")

@Preview(device = "spec:width=411dp,height=914dp,dpi=160")
@Composable
fun CalendarCompactPreview() = SizedDesignFixture("google-calendar-tablet")

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun KeepExpandedPreview() = SizedDesignFixture("google-keep-tablet")

@Preview(device = "spec:width=841dp,height=701dp,dpi=160")
@Composable
fun KeepMediumPreview() = SizedDesignFixture("google-keep-tablet")

@Preview(device = "spec:width=411dp,height=914dp,dpi=160")
@Composable
fun KeepCompactPreview() = SizedDesignFixture("google-keep-tablet")

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun PlayExpandedPreview() = SizedDesignFixture("google-play-tablet")

@Preview(device = "spec:width=841dp,height=701dp,dpi=160")
@Composable
fun PlayMediumPreview() = SizedDesignFixture("google-play-tablet")

@Preview(device = "spec:width=411dp,height=914dp,dpi=160")
@Composable
fun PlayCompactPreview() = SizedDesignFixture("google-play-tablet")

@Composable
internal fun SizedDesignFixture(designId: String, watchDp: Int? = null) {
  val document = designFixtureDocument(designId).onWatch(watchDp)
  PinnedCatalog(document) { UiBuilderSurface(document = document, editorOverlay = false) }
}

/**
 * The design's whole extent, measured as the editor canvas does: against unbounded height (at least
 * one screenful for a round frame) and `unrolled`, rather than stretched to the preview frame. The
 * frame only bounds the capture.
 */
@Composable
internal fun ExtentDesignFixture(designId: String, watchDp: Int? = null) {
  val document = designFixtureDocument(designId).onWatch(watchDp)
  PinnedCatalog(document) {
    Box(Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true)) {
      UiBuilderSurface(
        document = document,
        editorOverlay = false,
        unrolled = true,
      )
    }
  }
}

/**
 * This design on a round watch of [watchDp], as the editor's device strip shows it: the document's
 * frame width is overridden (as `variantPanes` does), since Wear screens draw at the document's
 * diameter.
 */
private fun UiBuilderDocument.onWatch(watchDp: Int?): UiBuilderDocument =
  if (watchDp == null) this
  else
    withEnvironmentOverrides(
      mapOf("widthDp" to JsonPrimitive(watchDp), "heightDp" to JsonPrimitive(watchDp))
    )

/**
 * The Wear screen at its two checked round sizes (matching its `exportDevices`), drawn by the
 * port's real `ScreenScaffold`; 48dp is a quarter of the small width, so wrap differences are
 * common.
 */
@WearPreviewSmallRound
@Composable
fun GoogleHomeWearSmallPreview() = SizedDesignFixture("google-home-wear", watchDp = 192)

@WearPreviewLargeRound
@Composable
fun GoogleHomeWearLargePreview() = SizedDesignFixture("google-home-wear", watchDp = 240)

/**
 * The same screen as a long screenshot, the only way to see a Wear list's whole extent: the frame
 * is taller than any watch so `UiBuilderSurface` draws the full stadium.
 *
 * Dashed boxes mark components the renderer doesn't draw though the capability fixture declares
 * them `supported` (see #907); the generated Kotlin is complete.
 */
@WearPreviewSmallRoundExtent
@Composable
fun GoogleHomeWearSmallExtentPreview() = ExtentDesignFixture("google-home-wear", watchDp = 192)

@WearPreviewLargeRoundExtent
@Composable
fun GoogleHomeWearLargeExtentPreview() = ExtentDesignFixture("google-home-wear", watchDp = 240)

@Preview(device = "spec:width=1280dp,height=800dp,dpi=160")
@Composable
fun DocsExpandedPreview() = SizedDesignFixture("google-docs-tablet")

@Preview(device = "spec:width=841dp,height=701dp,dpi=160")
@Composable
fun DocsMediumPreview() = SizedDesignFixture("google-docs-tablet")

@Preview(device = "spec:width=411dp,height=914dp,dpi=160")
@Composable
fun DocsCompactPreview() = SizedDesignFixture("google-docs-tablet")
