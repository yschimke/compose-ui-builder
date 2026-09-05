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

## The stadium is the scroll extent, not the device

The canvas draws the scaffold as the frame's **width**, the content's **height**, and round caps —
the Wear long-screenshot convention.

![The Wear list screen drawn as a long-screenshot stadium](evidence/ui-builder-wear-screen/wear-screen-stadium.png)

The alternative is a 192dp keyhole showing one screenful. The extent wins for authoring, because
what an author is building is the whole list and a keyhole hides most of it behind a scroll position
they have to keep re-finding. The first screenful is still marked: a stroked circle over the top cap
and the line where it ends.

Where the list wraps differs at 192, 227 and 240dp, and on the extent that comparison is three
columns rather than three scroll positions:

![The same design at the three round screen sizes](evidence/ui-builder-wear-screen/wear-screen-breakpoints.png)

The screen's diameter is read from the **document's own frame**, not from a scaffold property. The
Screen inspector already offers `wearos_small_round`, `wearos_large_round` and `wearos_xl_round`
from `DeviceDimensions`, so picking a watch is picking a device, and a fifth scaffold property would
be a second answer to the same question that would disagree with the first the moment anyone changed
one.

## What the canvas is knowingly wrong about

Stated here once, and again in each component's `wasm` note, because a stand-in that does not say
what it is standing in for is just a wrong picture.

- **The rows are not transformed.** `TransformingLazyColumn` scales and fades its rows toward the
  curved edges through `SurfaceTransformation` and `Modifier.transformedHeight`, and that treatment
  *is* what a Wear list looks like. The canvas draws a plain `Column`. Approximating the curve with a
  hand-rolled scale would draw a different wrong picture and imply it was the right one, so the
  stand-in stays visibly plain and the transformation is emitted in the source.
- **The sides are straight.** On a watch the usable width narrows toward the caps and a row near a
  screenful's edge is inset; between the caps the extent is right, and at the caps it clips rather
  than insets. Every row therefore reads at least as wide as it will be, and usually wider.
- **`TimeText` is flat.** Its *height* is what displaces the content below it, and that much is
  right; the curve is not something Compose Multiplatform draws.
- **`EdgeButton` is a rectangle.** The canvas draws the borrowed `m3/button`; the generated source
  calls `EdgeButton`, which takes its shape from the screen.
- **The content components are Material 3.** A Wear `Button` is not a Material 3 `Button`, and
  `TitleCard`, `ListHeader` and `EdgeButton` have no mobile counterpart at all. Real Wear content
  ids are the next change; borrowing was the alternative to a scaffold with nothing to put in it.

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

- **No Compose export from a component record.** `wear-m3` has `code = null` on both containers.
  `ScreenScaffold` takes a scroll state that has to agree with the list inside its content lambda,
  which `ScreenGenerator`'s call-site emitter cannot write from a record, so the whole-screen
  generator writes it instead. Pointing `--ui-builder-components wear-m3=<components.json>` at the
  sample's own discovery output would give the *borrowed* components a record; the two Wear
  containers still need this generator.
- **No native render evidence.** The lane exists and a `wear-m3`-pinned design would compile against
  that catalog's bundle, but standing an Android/Robolectric compile lane up is its own change.
- **No `wear-m3` templates in the New design chooser.** `wearScreenUiBuilderDocument` is the
  template; wiring it to a `?template=` id is the next step.
- **No round-viewport slider.** The extent marks the first screenful and nothing else; dragging a
  viewport down the extent is the obvious follow-up and is not here.
