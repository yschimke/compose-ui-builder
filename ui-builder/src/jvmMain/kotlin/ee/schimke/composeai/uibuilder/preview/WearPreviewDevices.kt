package ee.schimke.composeai.uibuilder.preview

import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.SettledPreview

/**
 * The round-watch frames, named rather than spelled out at every use: local equivalents of
 * `androidx.wear.compose.ui.tooling.preview`'s `@WearPreviewSmallRound`, `@WearPreviewLargeRound`
 * and `@WearPreviewDevices` (an Android AAR this KMP module can't use), with the same geometry.
 * Multipreview annotations, so any renderer reading `@Preview` sees them.
 */
/**
 * Settled, because the real `ScreenScaffold` animates the edge button in; the first frame has no
 * button. (Robolectric's native render advances two seconds for the same reason; see
 * `WearSampleDesignRoundTripTest` in wear-m3-catalog.)
 */
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@SettledPreview
annotation class WearPreviewSmallRound

@Preview(device = "spec:width=240dp,height=240dp,dpi=320")
@SettledPreview
annotation class WearPreviewLargeRound

/**
 * Both round sizes at once: 48dp is a quarter of the small round's width, so rows often fit one and
 * not the other.
 */
@WearPreviewSmallRound @WearPreviewLargeRound annotation class WearPreviewDevices

/**
 * The same two frames given room to draw the design's whole extent (the editor's stadium).
 *
 * **No size, deliberately**: an unsized preview is wrapped and cropped to what was drawn, so the
 * image is the stadium. The width comes from the fixture's watch (`ExtentDesignFixture`'s
 * `watchDp`) at the design's 2x. The sandbox height (800dp, fixed by compose-ai-tools' discovery)
 * cuts taller extents, so samples stay under it.
 */
@Preview annotation class WearPreviewSmallRoundExtent

@Preview annotation class WearPreviewLargeRoundExtent
