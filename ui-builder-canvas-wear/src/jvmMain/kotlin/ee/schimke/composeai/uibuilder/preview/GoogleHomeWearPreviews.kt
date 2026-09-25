package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * `google-home-wear` — a Wear design fixture — drawn with the Wear canvas this module adds. It used
 * to live beside the other design fixtures in `:ui-builder`, which no longer draws Wear.
 */
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignGoogleHomeWearPreview() = DesignFixture("google-home-wear")

/**
 * The Wear screen at the two round sizes it is checked on, matching its own `exportDevices`.
 *
 * A Wear list's hard question is where it wraps, and 48dp is a quarter of the small round's width —
 * so a row that fits one and not the other is the common case rather than a corner one. The canvas
 * draws the long-screenshot stadium rather than real Wear Compose (`androidx.wear.compose` is an
 * Android AAR the Wasm renderer cannot link), which is why the generated Kotlin is the other half
 * of reading this: it carries `Modifier.transformedHeight` and `SurfaceTransformation` on every
 * row.
 */
@WearPreviewSmallRound
@Composable
fun GoogleHomeWearSmallPreview() = SizedDesignFixture("google-home-wear")

@WearPreviewLargeRound
@Composable
fun GoogleHomeWearLargePreview() = SizedDesignFixture("google-home-wear")

/**
 * The same screen as a long screenshot, which is the only way to see a Wear list's whole extent.
 *
 * A round frame shows one screenful; this design is roughly five. The frame is taller than any
 * watch on purpose — `UiBuilderSurface` draws the design's full extent when given the room, which
 * is the stadium the editor's canvas uses — so scrolling is a thing you read rather than a thing
 * you have to operate.
 *
 * The dashed boxes are honest: `UiBuilderRenderer` draws six of this catalog's components and the
 * rest fall through to the undrawn-component placeholder, while its capability fixture declares
 * twenty-three of them `supported` (#907). The generated Kotlin is the half that is complete.
 */
@WearPreviewSmallRoundExtent
@Composable
fun GoogleHomeWearSmallExtentPreview() = ExtentDesignFixture("google-home-wear")

@WearPreviewLargeRoundExtent
@Composable
fun GoogleHomeWearLargeExtentPreview() = ExtentDesignFixture("google-home-wear")

/** The Wear samples transcribed from Android's own (see `UI_BUILDER_WEAR_SAMPLES.md`). */
@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignWearStarterGreetingPreview() = DesignFixture("wear-starter-greeting")

@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignWearStarterListPreview() = DesignFixture("wear-starter-list")

@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignJetcasterWearLibraryPreview() = DesignFixture("jetcaster-wear-library")

@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignJetcasterWearEpisodePreview() = DesignFixture("jetcaster-wear-episode")

@Preview(device = "spec:width=192dp,height=192dp,dpi=320")
@Composable
fun DesignJetcasterWearQueuePreview() = DesignFixture("jetcaster-wear-queue")

@Composable private fun DesignFixture(designId: String) = SizedDesignFixture(designId)
