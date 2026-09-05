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

## What may be borrowed: foundation, and nothing Material

**Material 3 and Wear Material 3 are not used together.** They are different libraries with
different theme systems, sizes and colour roles, and no app mixes them — so a `wear-m3` design
holding a component called `m3/card` claimed something no watch screen can mean.

It claimed it for a while. The catalog borrowed `m3/text`, `m3/icon`, `m3/button`, `m3/card` and
`m3/surface` outright, and [`WearScreenCodeExporter`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/WearScreenCodeExporter.kt)
quietly translated each one on the way out — `m3/card` became a `TitleCard`, `m3/text` became Wear's
`Text`. The **export was right and the palette was lying**, which is the failure mode this whole
document is about: a stand-in that does not say it is one.

The rule now:

- **Foundation is borrowed.** `layout/box`, `layout/column`, `layout/row` and `asset/image` are
  `androidx.compose.foundation` and `androidx.compose.ui` — one declaration shared by both platforms
  — so borrowing one claims nothing about Material at all. Their capability notes say exactly that,
  where the Material borrows' notes used to say "drawn as the Material 3 component of the same
  name".
- **Anything Material is Wear's own id.** `wear-m3/text`, `wear-m3/card` and `wear-m3/button` join
  `wear-m3/list-header` and the two containers. The canvas still draws the Material 3 lookalike —
  [it has no Wear Compose to draw with](#the-hard-constraint-the-canvas-has-no-wear-compose) — but
  the id, the notes and the generated Kotlin all name the Wear composable. Those three are also
  where the lookalikes stop: renaming a borrow the canvas already drew is not the same act as
  [assembling a component it never had](#the-line-a-component-is-never-faked-so-it-can-run-in-wasm).
- **`m3/surface` and `m3/icon` are gone rather than renamed.** Wear publishes no `Surface`, and an
  icon key resolves to a vector through a table the export module cannot reach, so `wear-m3/icon`
  would be a palette entry that refuses on export — which is what the borrowed `m3/icon` already
  was.

`WearM3ScreenCatalogTest` asserts the borrow list as a literal set, so adding one is a decision
somebody writes down rather than something that drifts in behind a convenient `m3/` id.

The rename changes no pixels: `WearScreenSamplePreview` renders byte-identically before and after
([`renders/ui-builder-wear-borrow/`](../../renders/ui-builder-wear-borrow/README.md)). It changes
what the design says it holds.

## The line: a component is never faked so it can run in Wasm

> **Update.** The rule below still stands word for word. What changed is the sentence it ends on —
> *"they arrive with the streaming preview or they do not arrive"* — because the streaming preview
> arrived. `wear-m3` now declares its canvas **approximate** and the Android render lane
> **authoritative**, and seventeen Wear components have joined the catalog *without* a Wasm
> lookalike between them: the canvas gives each a named placeholder and the picture comes from real
> Wear Compose on the Robolectric daemon. See
> [The canvas is not the preview](#the-canvas-is-not-the-preview-and-now-says-so) and
> [What the catalog offers now](#what-the-catalog-offers-now).

The three ids above are the last of their kind *drawn as lookalikes*. `wear-m3/checkbox-button`,
`wear-m3/switch-button` and `wear-m3/radio-button` were built, reviewed and
[closed unmerged](https://github.com/yschimke/compose-preview-server/pull/395), and the reason is the
rule this section states:

**Do not fabricate a component in the Wasm canvas to stand in for a library the canvas cannot link.**

Renaming a borrow is cheap and honest. `m3/card` was already being drawn; the change was to stop
calling it Material's. Building `CheckboxButton` is neither. Wear publishes no such shape to Wasm, so
the canvas has to *assemble* one — a Material 3 `Checkbox` inside a row, at a width, a corner radius,
a fill and a label baseline read off a screenshot — and what comes out is an impression of a
component with nothing in this build to check it against. Everything that impression gets wrong is
wrong silently, in the one surface an author trusts, and the next id starts the same work over. A few
components in, the catalog is a hand-drawn replica of Wear Material 3, maintained here, against an
upstream nobody in this repository compiles.

So the canvas is not the fidelity surface for `wear-m3`, and it is not going to become one.

`remote-m3` is the same rule with a stricter conclusion, and it is written up in
[`UI_BUILDER_REMOTE_COMPOSE.md`](UI_BUILDER_REMOTE_COMPOSE.md#no-borrow-crosses-into-remotecomposable):
a widget body is `@RemoteComposable`, which cannot call `@UiComposable` content at all, so even the
foundation carve-out above is unavailable there — that catalog's borrows are stand-ins all the way
down.

## The canvas is not the preview, and now says so

The editor had two renderers and one of them was quietly claiming to be the other. Pressing
**Preview** on a `wear-m3` design hid the editor chrome and showed the Wasm canvas — a picture of
Material 3 components standing in for Wear ones — under a control whose entire meaning is *this is
your screen*. Nothing was mislabelled; the label was just never asked to be true.

The catalog now says which renderer may make that claim. `wear-m3`'s `statusSemantics` carries a
`previewSurfaces` block, read by
[`UiBuilderPreviewSurfaces`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderPreviewSurfaces.kt):

| Surface | Fidelity | Because |
| --- | --- | --- |
| `wasm` | `approximate` | The canvas is Compose Multiplatform for Wasm and Wear Material 3 is an Android AAR. Author on it; do not read a size, a colour or a shape off it. |
| `native` | `authoritative`, backend `android` | Compiles this design's own generated Kotlin against real Wear Compose and renders it on Robolectric. |

Three things read it, and each of them used to guess:

- **The editor opens a Wear design on the host's renderer**, not on the canvas, where the host has
  one. Pressing **Preview** switches the render surface *before* the mode, so the first frame it
  shows is already the faithful one; where the host cannot compile, the Preview position is refused
  and carries the catalog's own sentence instead of being a grey button.
- **The render-surface menu** describes the Wasm entry as "stand-ins, for authoring" rather than as
  "Drawn in this browser". It is never removed — the canvas is what a node is selected and dragged
  on, and an editor with no canvas is not an editor.
- **The server** sends the compile to the Robolectric daemon rather than to Skiko, because the
  backend is part of the same declaration.

A catalog that says nothing gets the old behaviour exactly: authoritative canvas, desktop daemon.
`m3-catalog` says nothing, and should not — its canvas draws the same Material 3 its export names.

## The native lane takes a Wear design

`ScreenGeneratorComposeExportExecutor.generate` used to turn away every record-free design with one
refusal, and the sentence it ended on — *"export it instead, and preview it on the canvas"* — sent a
Wear author to the one surface that cannot answer their question. It now splits the two record-free
emitters by what they actually write:

- **A Wear screen is Wear Compose.** `ScreenScaffold`, `TitleCard`, `Text` — ordinary Kotlin, which
  compiles against a bundle carrying `androidx.wear.compose:compose-material3` and renders on the
  Android daemon. It goes through.
- **A Wear widget is Remote Compose.** A `WearWidgetDocument` is played rather than composed, so
  there is no `@Preview` to discover and no frame at the end of compiling it. It still refuses, and
  now says *that* rather than something about Wear.

Two things had to be true for the screen half to work, and neither was:

**The generated source has to carry node ids.** A streamed frame is a picture, and a picture is not
an editor — without a tag there is no way to say which rectangle draws the card you selected.
`WearScreenCodeExporter` now threads `Modifier.testTag("<node id>")` through every emitted call when
the native lane asks, including the `ScreenScaffold` itself and the `TransformingLazyColumn`. An
export artifact is left untagged: a test tag is not something a designer asked for in source they
keep.

**A builder catalog id is not a served catalog id.** The Wear bundle is another repository's catalog,
served under whatever `--catalogs` id its operator gave it, so
`--ui-builder-native-catalog wear-m3=wear-m3-catalog` maps the two. The packaged image passes exactly
that by default. A host with no mapping refuses with `NO_NATIVE_CATALOG` and names the flag — a
compile against a desktop classpath would fail on every `androidx.wear.compose` import and read like
the design is broken, which is the wrong half of the system to send anyone to.

One bug fell out of writing the round trip, and it had been there since the generator landed:
`AppScaffold` is emitted for every design and was imported only for designs declaring a `timeText`,
so every Wear screen without one generated a file that did not compile.

## What the catalog offers now

`wear-m3` was a scaffold, a list, a header and three renamed borrows — a palette of containers with
almost nothing to put in them. It now carries Wear's own vocabulary, following `m3-catalog`'s own
approach: an id per component, a `variant` property where upstream publishes a family rather than a
component, starter content so a dropped node looks typical, and a generated call site proved by a
test.

| | |
| --- | --- |
| Content | `wear-m3/text`, `wear-m3/card` (`title` · `app` · `outlined` · `plain`), `wear-m3/icon`, `wear-m3/list-header`, `wear-m3/list-sub-header` |
| Actions | `wear-m3/button` (`filled` · `filled-tonal` · `outlined` · `child`), `wear-m3/icon-button`, `wear-m3/text-button`, `wear-m3/button-group`, `wear-m3/edge-button` |
| Selection | `wear-m3/checkbox-button`, `wear-m3/switch-button`, `wear-m3/radio-button` |
| Value | `wear-m3/slider`, `wear-m3/stepper`, `wear-m3/progress-indicator` (`circular` · `segmented-circular` · `linear` · `arc`) |
| Full screen | `wear-m3/date-picker`, `wear-m3/time-picker` |
| Overlays | `wear-m3/alert-dialog`, `wear-m3/confirmation-dialog`, `wear-m3/open-on-phone-dialog` |
| Containers | `wear-m3/screen-scaffold`, `wear-m3/transforming-lazy-column` |
| Borrowed | `layout/box`, `layout/column`, `layout/row`, `asset/image`, `remote-compose/document` |

Three of those are not rows of a list, and the catalog says so structurally rather than in prose:

- **A dialog is a screen state.** It takes a `visible` flag and draws over everything, so the
  scaffold grew an `overlays` slot and the generator writes dialogs as siblings of the
  `ScreenScaffold` inside `AppScaffold`. Dropped into the list, one is refused by name with the slot
  it belongs in.
- **A `Stepper` and a picker own the display.** They go in the scaffold's content slot *instead of*
  the list, and the generator writes them straight into `ScreenScaffold`'s content lambda.
- **`EdgeButton` is a component now**, not a `wear-m3/button` in a slot that happened to generate
  one, so it can carry the size enum upstream publishes. A plain button in that slot still works,
  because designs written before the id exist.

Two things a `Slider`, a `Stepper`, a selection control and a dialog all need: they are **controlled**
components, so the generated screen hoists a `remember` for each and passes the callback. A generated
screen whose checkbox cannot be ticked is a picture of a screen.

### None of them is drawn on the canvas

Not one. Each shows as a dashed outline carrying the component's name, its label where it has one,
and its children — enough to select, reorder and fill, and claiming nothing about size, colour or
shape. That is [the rule](#the-line-a-component-is-never-faked-so-it-can-run-in-wasm) kept rather
than bent: the reason it existed was that a hand-assembled lookalike is *wrong silently in the one
surface an author trusts*, and the fix is not a better lookalike, it is a surface that says what it
is worth.

### Where a Wear design gets looked at instead

The **streaming preview** — the native render lane,
[`ServeUiBuilderNativePreview`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderNativePreview.kt)
— compiles a design's generated Kotlin and streams the frames it renders back to the browser. That
lane runs real Compose against a real classpath, which is precisely what the Wasm canvas cannot do,
and the Wear source it would be handed is [already the honest half](#what-the-design-generates) of
this adapter: the generated screen names `ScreenScaffold`, `TitleCard` and Wear's `Text`, and always
has.

One refusal stands between here and there. `ScreenGeneratorComposeExportExecutor.generate` turns a
record-free design away before it reaches the compile lane — *"a Wear screen is Wear Compose … the
native preview lane compiles against the catalog bundle, which carries neither; export it instead,
and preview it on the canvas"* — and that last clause is the sentence this decision retires. A
Wear-Compose bundle for that lane is the work. It is a real piece of work, and it is the right one,
because it draws every component Wear publishes rather than the subset somebody found time to
hand-build.

Until it exists, `wear-m3` authors against a canvas that is deliberately approximate and says so in
every capability note, and the fidelity question is answered outside the browser — by
`samples/design-catalog-wear-m3` in compose-ai-tools, which already compiles the generator's own
output and renders it for real ([the round trip](#the-round-trip-closes)).

### What this leaves the three ids that are already drawn that way

`wear-m3/text`, `wear-m3/card` and `wear-m3/button` keep their lookalikes. Each is a rename of a
borrow the canvas was drawing anyway, the whole mapping is three lines of one function
(`wearScreenStandIn` in [`UiBuilderRenderer.kt`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderRenderer.kt)),
and taking them out would leave a palette of containers with nothing to put in them. The cap is the
point: `WearCanvasStandInTest` pins that mapping to exactly those three, so a fourth is a test
somebody has to edit, in a change that has to argue with this section first.

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

### On a deployed box

`wear-m3` is in the packaged image's authoring allowlist —
`SERVE_UI_BUILDER_CATALOGS` defaults to `m3-catalog,remote-m3,wear-m3` in
`deploy/image/entrypoint.sh` — so the chooser offers Wear Material 3 without an operator flag.
Enabling an adapter is a claim that what an author sees is what they get, and it joined the default
only once the round trip above put a render behind that claim.

`ServeWearScreenDeploymentIntegrationTest` walks the path a person walks, over HTTP, against a
server given exactly that catalog list: `POST /ui-builder/wear-m3` with `template=wear-list` —
the form the New design chooser submits, seeded server-side — a `303` to `/ui-builder/wear-m3/activity`,
the design opening as a scaffold over a seven-item list on a 192dp frame, and the export returning
the Wear Kotlin with both previews in it. Two halves that each pass alone are not the same as one
that works: the deploy scripts check that the entrypoint *passes* the flag, and this checks that a
server given the flag can serve a Wear design.

Note that no component record is configured for `wear-m3` and none is needed. A Wear screen is
written by `RecordFreeExport`'s emitter rather than from a recovered signature, which is why the
Compose-export action appears for it on a box that carries a record for `m3-catalog` alone.

### The single frame, which is the other half

Everything above compares extents, because that is what the canvas draws. The generated screen's
other preview is the one a watch actually shows — `@WearPreviewDevices`, one screenful, rows
transformed against the bezel:

![The generated Wear screen rendered as a single round frame](evidence/ui-builder-wear-screen/wear-screen-single-frame.png)

The first row is at full width and the second is already scaled and faded into the curve; the scroll
indicator is on the right bezel, because a single frame is exactly where it belongs. Reading it
beside the extent is the point of emitting both: the extent is what you author against, and this is
what ships.

### What is still not the watch

The long screenshot is not a frame. On a live screen `SurfaceTransformation` scales and fades each
row by where it sits against the bezel, and a row near the curve is inset and shrunk. Neither the
reference nor the canvas shows that, and neither claims to: `LONG` turns it off in order to stitch,
and the canvas has no Wear Compose to turn on. A single-frame render - `ScrollMode.TOP` or `END` in
that repository, or the builder's own native lane - is what answers *that* question.

`SurfaceTransformation(spec)` is worth being exact about, because the emitted code's shape follows
from the API's. Upstream declares it on a **receiver**, not as a free function:

```kotlin
@Stable
public fun TransformingLazyColumnItemScope.SurfaceTransformation(
    spec: TransformationSpec
): SurfaceTransformation
```

So a `transformation` argument can only be written inside an `item { }` / `items { }` lambda of a
`TransformingLazyColumn`, which is exactly where `WearScreenCodeExporter` writes it and the only
place it could. A component that sits outside the list — the `EdgeButton`, anything in the scaffold's
own content — takes no transformation because there is no scope to build one in, not because the
generator forgot. This is also the answer to the question this repository could not check for itself
while it carried no compiled Wear dependency.

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
- **The content ids are Wear's, and the drawing is still borrowed.** `wear-m3/text`, `wear-m3/card`
  and `wear-m3/button` now exist beside `wear-m3/list-header`, so no Material id is offered on a
  watch — but each is still *drawn* as its Material 3 lookalike, and the template still makes up the
  difference with type sizes and padding measured off the reference. What is left is the sizes, not
  the naming.
- **No Wear controls, and none are coming on the canvas.** Wear Material 3 publishes
  `CheckboxButton`, `SwitchButton`, `RadioButton`, `Slider`, `Stepper`, `DatePicker`, `TimePicker`
  and its own `AlertDialog`, and none of them has an id here. They are not borrowable — a Wear
  `CheckboxButton` is a full-width labelled row, not the mobile 20dp square — and the version of
  this that hand-assembles each one for Wasm is
  [the thing this document rules out](#the-line-a-component-is-never-faked-so-it-can-run-in-wasm).
  They arrive with the streaming preview or they do not arrive.
- **`EdgeButton` is placed, not shaped.** The slot generates a real `EdgeButton`; the canvas draws
  the borrowed flat button at the bottom cap, because the shape comes from the screen. The parity
  template carries none for that reason.
- **The streaming preview does not take a Wear design yet, and that is now the blocking one.**
  `ScreenGeneratorComposeExportExecutor.generate` refuses a record-free design because the lane
  compiles against the catalog bundle and no bundle here carries Wear Compose. Every question this
  adapter defers — the single frame, `SurfaceTransformation` against the bezel, a control that is
  not a rename of a borrow — is downstream of that one bundle
  ([why it is the work rather than more lookalikes](#where-a-wear-design-gets-looked-at-instead)).
