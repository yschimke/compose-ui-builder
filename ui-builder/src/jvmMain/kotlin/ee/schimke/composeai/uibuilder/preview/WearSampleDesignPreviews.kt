package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable

/**
 * The upstream Wear sample screens, each drawn the two ways the editor shows a Wear design.
 *
 * Five designs transcribed from Android's own samples — the ComposeStarter greeting and list from
 * `android/wear-os-samples`, and Jetcaster's library, episode and queue screens from
 * `android/compose-samples` — and written up with what the transcription found in
 * [`UI_BUILDER_WEAR_SAMPLES.md`](../../../../../../../../../docs/design/UI_BUILDER_WEAR_SAMPLES.md).
 *
 * - `…SmallPreview` / `…LargePreview` are the **device previews**: one screenful on the 192dp and
 *   240dp rounds, the frames the editor's variant strip draws, so a row that fits one and clips on
 *   the other is visible here.
 * - `…SmallExtentPreview` / `…LargeExtentPreview` are the **editing canvas**: the design's whole
 *   extent, unrolled, which is what an author selects and drops onto.
 *
 * The third view, real Wear Compose on Android, is the generated Kotlin — wear-m3-catalog compiles
 * and renders these same documents under Robolectric.
 */
@WearPreviewSmallRound
@Composable
fun WearStarterGreetingSmallPreview() = SizedDesignFixture("wear-starter-greeting", watchDp = 192)

@WearPreviewLargeRound
@Composable
fun WearStarterGreetingLargePreview() = SizedDesignFixture("wear-starter-greeting", watchDp = 240)

@WearPreviewSmallRoundExtent
@Composable
fun WearStarterGreetingSmallExtentPreview() =
  ExtentDesignFixture("wear-starter-greeting", watchDp = 192)

@WearPreviewLargeRoundExtent
@Composable
fun WearStarterGreetingLargeExtentPreview() =
  ExtentDesignFixture("wear-starter-greeting", watchDp = 240)

@WearPreviewSmallRound
@Composable
fun WearStarterListSmallPreview() = SizedDesignFixture("wear-starter-list", watchDp = 192)

@WearPreviewLargeRound
@Composable
fun WearStarterListLargePreview() = SizedDesignFixture("wear-starter-list", watchDp = 240)

@WearPreviewSmallRoundExtent
@Composable
fun WearStarterListSmallExtentPreview() = ExtentDesignFixture("wear-starter-list", watchDp = 192)

@WearPreviewLargeRoundExtent
@Composable
fun WearStarterListLargeExtentPreview() = ExtentDesignFixture("wear-starter-list", watchDp = 240)

@WearPreviewSmallRound
@Composable
fun JetcasterWearLibrarySmallPreview() = SizedDesignFixture("jetcaster-wear-library", watchDp = 192)

@WearPreviewLargeRound
@Composable
fun JetcasterWearLibraryLargePreview() = SizedDesignFixture("jetcaster-wear-library", watchDp = 240)

@WearPreviewSmallRoundExtent
@Composable
fun JetcasterWearLibrarySmallExtentPreview() =
  ExtentDesignFixture("jetcaster-wear-library", watchDp = 192)

@WearPreviewLargeRoundExtent
@Composable
fun JetcasterWearLibraryLargeExtentPreview() =
  ExtentDesignFixture("jetcaster-wear-library", watchDp = 240)

@WearPreviewSmallRound
@Composable
fun JetcasterWearEpisodeSmallPreview() = SizedDesignFixture("jetcaster-wear-episode", watchDp = 192)

@WearPreviewLargeRound
@Composable
fun JetcasterWearEpisodeLargePreview() = SizedDesignFixture("jetcaster-wear-episode", watchDp = 240)

@WearPreviewSmallRoundExtent
@Composable
fun JetcasterWearEpisodeSmallExtentPreview() =
  ExtentDesignFixture("jetcaster-wear-episode", watchDp = 192)

@WearPreviewLargeRoundExtent
@Composable
fun JetcasterWearEpisodeLargeExtentPreview() =
  ExtentDesignFixture("jetcaster-wear-episode", watchDp = 240)

@WearPreviewSmallRound
@Composable
fun JetcasterWearQueueSmallPreview() = SizedDesignFixture("jetcaster-wear-queue", watchDp = 192)

@WearPreviewLargeRound
@Composable
fun JetcasterWearQueueLargePreview() = SizedDesignFixture("jetcaster-wear-queue", watchDp = 240)

@WearPreviewSmallRoundExtent
@Composable
fun JetcasterWearQueueSmallExtentPreview() =
  ExtentDesignFixture("jetcaster-wear-queue", watchDp = 192)

@WearPreviewLargeRoundExtent
@Composable
fun JetcasterWearQueueLargeExtentPreview() =
  ExtentDesignFixture("jetcaster-wear-queue", watchDp = 240)
