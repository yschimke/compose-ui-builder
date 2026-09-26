package ee.schimke.composeai.uibuilder.preview

import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.preview.SettledPreview

/**
 * The round-watch frames, named rather than spelled out at every use.
 *
 * `androidx.wear.compose.ui.tooling.preview` ships exactly these — `@WearPreviewSmallRound`,
 * `@WearPreviewLargeRound`, `@WearPreviewDevices` — and the generated Kotlin a Wear design exports
 * uses them, because that code compiles against Wear Compose. This module cannot: that artifact is
 * an Android AAR, and `:ui-builder` is a Kotlin Multiplatform module whose JVM target has no
 * Android classpath. It is the same wall that stops the canvas drawing real Wear Compose.
 *
 * So these are the local equivalents, carrying the same geometry under the same names, and the
 * point of them is that the geometry is written once. A `spec:` string repeated at each preview is
 * four chances to mistype a density, and the reader has to know that `dpi=320` is 2.0× before the
 * annotation means anything.
 *
 * Multipreview — an annotation class annotated with `@Preview` — is Compose tooling's own
 * mechanism, so a renderer that reads `@Preview` transitively sees these.
 */
/**
 * Settled, because the real `ScreenScaffold` grows its edge button in: it animates the button's
 * height up as the list reaches its end, so a capture of the first frame has no button at all.
 * Robolectric's native render of the same design advances the clock two seconds before capturing
 * for the same reason (`WearSampleDesignRoundTripTest` in wear-m3-catalog).
 */
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@SettledPreview
annotation class WearPreviewSmallRound

@Preview(device = "spec:width=240dp,height=240dp,dpi=320")
@SettledPreview
annotation class WearPreviewLargeRound

/**
 * Both round sizes at once, which is the comparison a Wear list exists to be checked against: 48dp
 * is a quarter of the small round's width, so a row that fits one and not the other is the common
 * case rather than a corner one.
 */
@WearPreviewSmallRound @WearPreviewLargeRound annotation class WearPreviewDevices

/**
 * The same two frames given room to draw the design's whole extent rather than one screenful.
 *
 * A round frame answers "does this row fit?". A Wear list's other question is how far it runs, and
 * `UiBuilderSurface` draws the full extent when the frame allows it — the stadium the editor's
 * canvas uses.
 *
 * **No size, deliberately.** A preview that names none is wrapped: the renderer composes it into
 * its sandbox and crops the capture to what was drawn, so the image IS the stadium. A fixed 760dp
 * frame made every extent 760dp tall, a one-screen design included, with transparency below. The
 * width is not the frame's either — the stadium is drawn at the watch the fixture puts the design
 * on (`ExtentDesignFixture`'s `watchDp`), at the design's own 2x.
 *
 * The cost is the sandbox's height: 800dp, fixed by compose-ai-tools' discovery, so an extent
 * taller than that is cut. The sample designs are kept under it.
 */
@Preview annotation class WearPreviewSmallRoundExtent

@Preview annotation class WearPreviewLargeRoundExtent
