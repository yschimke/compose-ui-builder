# The Wear screen in the UI builder

`remote-m3` answered "can the builder author a Wear **widget**" with a fake host container that the
generated code erases. This is the same question for a Wear **screen**, and the answer is a fake
container too — but a different kind of fake, for a reason worth being exact about.

## Why the widget trick does not transfer unchanged

`remote-m3/widget-container-*` stands in for `androidx.glance.wear.composable.WearWidgetContainer`,
which the *launcher* draws around widget content from `WearWidgetParams`. The author does not call
it, cannot choose its padding, and its geometry is a fixed 216×76dp or 216×124dp rounded rect. So
the canvas can reproduce it exactly, and `WearWidgetCodeExporter` names it nowhere: erasing the
scaffold on export is not a loss, it is the correct output.

`androidx.wear.compose.material3.ScreenScaffold` is the opposite on every count.

| | Widget container | Screen scaffold |
| --- | --- | --- |
| Who draws it | The launcher | The author's own code |
| On export | Erased | **Emitted** |
| Geometry | A fixed rounded rect | Derived from the round screen at layout time |
| Canvas fidelity | Exact | An approximation, stated as one |

So `wear-m3/screen-scaffold` is faked only in the **drawing**. The generated Kotlin calls the real
`ScreenScaffold`, and [`WearScreenCodeExporter`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/WearScreenCodeExporter.kt)
is a second generator rather than a flag on the widget one because "erase the stand-in" and "emit
the stand-in" are different jobs that happen to share a shape.

## The hard constraint: the canvas has no Wear Compose

`androidx.wear.compose:compose-material3` is an Android AAR. The builder's canvas is Compose
Multiplatform for Wasm, which cannot link one. There is no version of this adapter that draws with
the real components, the way `m3-catalog`'s canvas draws the same Material 3 its export names — and
that is not a gap somebody could close with more work in this repository. Every capability note in
the `wear-m3` catalog says so rather than implying parity.

Two things follow. The catalog is *synthesised* from the packaged M3 one
([`ProductionUiBuilderRuntime.kt`](../../ui-builder-runtime/src/main/kotlin/ee/schimke/composeai/uibuilder/service/ProductionUiBuilderRuntime.kt)),
because everything except the two Wear containers genuinely is borrowed Material 3. And the
question "what does this actually look like on a watch" belongs to the **native render lane**, which
compiles the design against the catalog's own bundle and renders it with real Compose — the lane
`ServeUiBuilderNativePreview` exists for, and whose own KDoc names Android as the thing the Wasm
canvas cannot answer.

## The stadium is the scroll extent, and it is the real one

The canvas draws the scaffold as the frame's **width**, the content's **height**, and round caps.
That is not a metaphor for a watch - it is what a Wear long screenshot *is*, and there is a real one
to check against. `@ScrollingPreview(modes = [ScrollMode.LONG])` on wear-m3-catalog's
`TransformingLazyColumn` component stitches the whole scroll into one tall PNG, and the result is a
stadium.

The important property of that capture, and the reason this stand-in can be exact rather than
approximate: **`LONG` stitches with the row transformation off.** Every card on the reference is
172dp wide at every position down the page - measured at each card's centre, not at a corner - so
the capture is the list's *unscaled* layout. A stadium-shaped Column with the same content padding
and the same item spacing is therefore not an impression of it. It is the same picture.

| At 192dp | Reference (`ScrollMode.LONG`) | The builder's canvas |
| --- | --- | --- |
| Frame | 192 x 496dp | 192 x 496dp |
| Content padding | 10dp x 20dp | 10dp x 20dp |
| First row | 72.0 to 136.0dp | 72.0 to 136.0dp |
| Row height / gap | 64.0dp / 4.0dp | 64.0dp / 4.0dp |
| Row span | 10.0 to 182.0dp | 10.0 to 182.0dp |
| Clock glyphs | (75.5, 5.5) to (117.0, 18.0) | (75.5, 5.5) to (117.0, 16.0) |
| Header glyphs | (69.5, 40.0) to (123.0, 55.0) | (69.0, 40.0) to (123.0, 54.0) |
| Title glyphs | (23.0, 87.5) to (89.0, 99.0) | (23.0, 87.5) to (89.0, 98.5) |
| Subtitle glyphs | (22.0, 108.5) to (58.5, 119.0) | (23.0, 108.0) to (59.5, 118.0) |

Everything is within a dp, and the residue is antialiasing thresholds in the measurement rather than
layout. The two are the same design: the builder's template carries wear-m3-catalog's rows character
for character, so a difference between the columns means something.

![The builder's canvas beside wear-m3-catalog's stitched LONG capture](evidence/ui-builder-wear-screen/wear-screen-parity.png)

### Where the numbers came from

Not from a token table, and not from a fraction of the diameter - both were tried and both were
wrong. `ScreenScaffoldContentPaddingTest` in wear-m3-catalog composes the real `AppScaffold` /
`ScreenScaffold` / `TransformingLazyColumn` under Robolectric and asserts what the scaffold hands
its list:

| Screen | Horizontal | Vertical | as a fraction |
| --- | --- | --- | --- |
| 192dp (`wearos_small_round`) | 10dp | 20dp | 5.21% / 10.42% |
| 227dp (`wearos_large_round`) | 12dp | 23dp | 5.29% / 10.13% |
| 240dp (`wearos_xl_round`) | 13dp | 24dp | 5.42% / 10.00% |

Neither column is a constant fraction, which is exactly why guessing failed. The builder
interpolates between the three measured points rather than extrapolating a percentage.

The rest is measured off the reference pixels: the clock's digits at 5.5dp from the top **at every
screen size** (a constant, not a fraction), the 26dp card corner, and Wear's dark colours -
`#000000` behind, `#332E3C` on the card, `#F6EDFF` for a title and the warm `#FFDCC2` for a
subtitle, which is the one nobody guesses.

### The round trip closes

The canvas matching a hand-written reference is half the claim. The other half is that the Kotlin
the builder *generates* renders the same way, and that is now checked end to end:
`samples/design-catalog-wear-m3` in compose-ai-tools carries the generator's own output for the
`wear-list` template, unedited, and renders it with real Wear Compose.

The three, left to right — wear-m3-catalog's hand-written component, the builder's Wasm canvas, and
the generated screen compiled and captured on Android:

![The reference, the canvas, and the generated screen rendered for real](evidence/ui-builder-wear-screen/wear-screen-round-trip.png)

| At 192dp | Canvas | Generated, rendered |
| --- | --- | --- |
| Frame | 192 x 496dp | 192 x 496dp |
| First row | 72.0 to 136.0dp | 72.0 to 136.0dp |
| Row height / gap | 64.0dp / 4.0dp | 64.0dp / 4.0dp |
| Row span | 10.0 to 182.0dp | 10.0 to 182.0dp |
| Last row ends / bottom padding | 476.0dp / 20.0dp | 476.0dp / 20.0dp |

Two things had to be true for that, and neither was until the round trip was actually run.

**The generated screen suppresses its scroll indicator while the platform is capturing.** A stitched
long screenshot composites many frames, and an indicator drawn at a different offset and opacity in
each of them lands as a column of disconnected dashes down the edge — which is what the reference
published before this. `LocalScrollCaptureInProgress` is the platform's own signal, set by Android's
system long-screenshot and by the renderer for a `ScrollMode.LONG` capture, and the emitted
`ScreenScaffold` reads it. That is app behaviour rather than a preview concession: a real long
screenshot of a real app wants the same thing.

**`wear-m3/list-header` is a component now, and it is the first one that is Wear's rather than
borrowed.** The template used to fake `ListHeader`'s 48dp with a padded `m3/text`. The canvas
matched the reference and the generated screen came out **31.5dp shorter**, because a padded Text is
not a `ListHeader` and the generator was right not to pretend it was. Every other row already agreed
to the dp; the header was the whole discrepancy, and there was no way to close it from the borrowed
side.

### Nothing is drawn over the design

An earlier version outlined the first screenful — a circle over the top cap, a line where it ends —
to answer "how much of this is above the fold". It reads as an artifact, because it is one: the
canvas paints the design, and a guide painted into it is editor chrome in the one layer that has to
stay comparable, pixel for pixel, with a render that has no such thing. If the fold is worth marking
it belongs in the editor overlay, the way the reference overlay already does.

The scroll indicator is gone from the canvas for a related reason. It is a real property of the
design and it reaches the generated code; what it has no meaning on is the *extent*, which has no
viewport for an indicator to show a position within.

### What is still not the watch

The long screenshot is not a frame. On a live screen `SurfaceTransformation` scales and fades each
row by where it sits against the bezel, and a row near the curve is inset and shrunk. Neither the
reference nor the canvas shows that, and neither claims to: `LONG` turns it off in order to stitch,
and the canvas has no Wear Compose to turn on. A single-frame render - `ScrollMode.TOP` or `END` in
that repository, or the builder's own native lane - is what answers *that* question.

The content components are still Material 3's, borrowed. The type sizes and the card shape are set
by the template to Wear's measured values, which is what makes this design match; another design
built from the same components starts from the mobile defaults again. Real Wear content ids are the
change that fixes that properly.

## What the design generates

The Code pane routes a `wear-m3/screen-scaffold` root to `WearScreenCodeExporter`, the way a widget
root routes to `WearWidgetCodeExporter` — one answer to one question, rather than the Compose gate's
"no component record" refusal, which is true and useless.

![The Code pane on the Wear screen design](evidence/ui-builder-wear-screen/wear-screen-code-pane.png)

The scaffold's `timeText` generates the `AppScaffold` that actually owns the status strip —
`ScreenScaffold` has no `timeText` argument — and the frozen `10:10` is the same freeze
`samples/design-catalog-wear-m3` applies to its own captures, for the same reason: a strip that moved
would churn every render diff, and dropping it instead would under-report the top margin the content
lays out around.

A borrowed component with no Wear counterpart is refused **by node**, never approximated. The canvas
will draw an `m3/filter-chip` quite happily and there is nothing in Wear Compose Material 3 to write
it as, so the generator names the node and stops rather than emitting Kotlin that does not compile.

## Not done here

- **No Compose export from a component record.** `wear-m3` has `code = null` on its own components.
  `ScreenScaffold` takes a scroll state that has to agree with the list inside its content lambda,
  which `ScreenGenerator`'s call-site emitter cannot write from a record, so the whole-screen
  generator writes it instead.
- **Most content is still borrowed.** `wear-m3/list-header` is the only content id that is Wear's;
  the rest are `m3-catalog`'s, and the template makes up the difference with type sizes and padding
  measured off the reference. Another design built from the same components starts from the mobile
  defaults again. Real Wear ids for the card, the button and the text are the change that fixes it,
  and the round trip is what will say when each one is needed.
- **`EdgeButton` is placed, not shaped.** The slot generates a real `EdgeButton`; the canvas draws
  the borrowed flat button at the bottom cap, because the shape comes from the screen. The parity
  template carries none for that reason.
- **No native-render evidence.** The builder's own native lane would answer the single-frame
  question without leaving the server; standing an Android compile lane up is its own change.
