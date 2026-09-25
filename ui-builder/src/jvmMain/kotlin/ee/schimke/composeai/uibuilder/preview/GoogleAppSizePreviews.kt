package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.canvas.UiBuilderCanvasAddonApi
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface

/**
 * The five Google-app sample designs, drawn at three window sizes each.
 *
 * `DesignFixturePreviews.kt` frames every fixture at the environment the design pins, which is what
 * keeps a capture from landing in a corner of its image. These are the other question: what the
 * *same document* does when the window is not the one it was authored in. The frame is the window —
 * `AdaptiveSupportingPaneScaffold` computes its size class from the frame's own constraints — so a
 * tablet design collapsing here is `androidx.compose.material3.adaptive`'s answer rather than this
 * repository's.
 *
 * Expanded, medium and compact, named after the width size class each one lands in, and matching
 * the device presets the designs carry in `environment.exportDevices`.
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

/** [designId] drawn on its catalog's canvas, filling the preview's frame. */
@UiBuilderCanvasAddonApi
@Composable
fun SizedDesignFixture(designId: String) {
  PinnedCatalog(designFixtureDocument(designId)) {
    UiBuilderSurface(document = designFixtureDocument(designId), editorOverlay = false)
  }
}

/**
 * The design's whole extent, measured the way the editor's canvas measures it: against an unbounded
 * height, so the stadium is as tall as its content — never shorter than one screenful, which the
 * round frame enforces itself — rather than stretched to the preview's frame.
 *
 * The extent previews used [SizedDesignFixture], which hands the surface the frame's full 760dp. A
 * scaffold fills the height it is given, so a one-item screen came out as a 760dp stadium with its
 * edge button pinned to the bottom: a picture of the preview frame, not of the design. Here the
 * frame only bounds the capture; what is below the content is the preview's background.
 *
 * And `unrolled`, which [SizedDesignFixture] never asked for: those previews were the lazy device
 * mode drawn into a tall frame — scroll indicator, rows scaling at the bottom edge — rather than
 * the canvas's unrolled stadium they were named after.
 */
@UiBuilderCanvasAddonApi
@Composable
fun ExtentDesignFixture(designId: String) {
  PinnedCatalog(designFixtureDocument(designId)) {
    Box(Modifier.fillMaxSize().wrapContentHeight(Alignment.Top, unbounded = true)) {
      UiBuilderSurface(
        document = designFixtureDocument(designId),
        editorOverlay = false,
        unrolled = true,
      )
    }
  }
}
