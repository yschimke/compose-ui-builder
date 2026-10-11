package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable

/**
 * The upstream Wear sample screens, each drawn the two ways the editor shows a Wear design. Five
 * designs transcribed from Android's samples (ComposeStarter's greeting and list from
 * `android/wear-os-samples`; Jetcaster's library, episode and queue from
 * `android/compose-samples`), written up in
 * [`UI_BUILDER_WEAR_SAMPLES.md`](../../../../../../../../../docs/design/UI_BUILDER_WEAR_SAMPLES.md).
 *
 * - `…SmallPreview` / `…LargePreview`: the **device previews**, one screenful on the 192dp and
 *   240dp rounds.
 * - `…SmallExtentPreview` / `…LargeExtentPreview`: the **editing canvas**, the whole extent
 *   unrolled.
 *
 * Real Wear Compose on Android is checked via the generated Kotlin, which wear-m3-catalog renders
 * under Robolectric.
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
