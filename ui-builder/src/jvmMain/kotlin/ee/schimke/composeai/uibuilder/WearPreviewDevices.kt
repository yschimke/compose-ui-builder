package ee.schimke.composeai.uibuilder

import androidx.compose.ui.tooling.preview.Preview

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
@Preview(device = "spec:width=192dp,height=192dp,dpi=320") annotation class WearPreviewSmallRound

@Preview(device = "spec:width=240dp,height=240dp,dpi=320") annotation class WearPreviewLargeRound

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
 * canvas uses. 760dp is about five screenfuls at 192dp.
 */
@Preview(device = "spec:width=192dp,height=760dp,dpi=320")
annotation class WearPreviewSmallRoundExtent

@Preview(device = "spec:width=240dp,height=760dp,dpi=320")
annotation class WearPreviewLargeRoundExtent
