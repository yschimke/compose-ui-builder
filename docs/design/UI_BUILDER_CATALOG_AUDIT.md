# UI builder catalog audit: what an application needs that the catalogs do not offer

**Status: audit, 2026-09-09.** A survey rather than a plan. It compares what the builder's three
authoring catalogs offer against what the catalog repositories actually publish and what real
application code actually uses, and names the missing pieces. Where a gap has an obvious shape the
shape is proposed, but nothing here is implemented and no decision below is settled.

Read [`UI_BUILDER_CATALOG_CONTRACT.md`](UI_BUILDER_CATALOG_CONTRACT.md) first: it is where most of
these gaps get *delivered from*, because a catalog that declares itself is a catalog that can grow a
component without a release of this repository. This document is the inventory that says which
components are worth declaring.

## Method

Four inventories, taken at `d629fb6`:

| Inventory | Source | Count |
| --- | --- | --- |
| `m3-catalog` authoring catalog | [`fixtures/ui-builder/m3-catalog-capabilities-v1.json`](fixtures/ui-builder/m3-catalog-capabilities-v1.json) | **41** components |
| `wear-m3` authoring catalog | [`fixtures/ui-builder/wear-m3-capabilities-v1.json`](fixtures/ui-builder/wear-m3-capabilities-v1.json) | **30** components (23 `wear-m3/*` + 7 borrowed) |
| `remote-m3` authoring catalog | [`fixtures/ui-builder/remote-m3-capabilities-v1.json`](fixtures/ui-builder/remote-m3-capabilities-v1.json) | **12** components (3 `remote-m3/*` + 9 borrowed) |
| What the catalogs publish | `@CatalogComponent` ids in `yschimke/m3-catalog`, `yschimke/wear-m3-catalog` | **58** mobile, **79** Wear, **53** Remote |

**A declared property is not a capability.** Twice in review this audit called something authorable
because the capability JSON declared a property or a variant value, when no emitter reads it — see
`scrollIndicator` and the `app` card variant in §6, and `contentWindowInsets` in §4. The check that
settles it is the emitter (`CapabilityComposeCodeExporter`, `WearScreenCodeExporter`) and the Wasm
renderer, not the catalog. Every "already authorable" claim below has been through that check;
treat any that has not as unverified.

The authoring catalogs are the smaller number in every row, which is expected — the builder is not
obliged to author everything a catalog can render. The question this audit asks is narrower: *for
each thing an application screen normally contains, can the builder express it at all?* A component
whose absence forces an author out of the builder is a gap; a component whose absence merely costs a
variant is not.

## Summary of findings

| # | Gap | Where the fix lives | Severity |
| --- | --- | --- | --- |
| 1 | No drawing surface at all — no `Canvas`, no draw ops, no path | builder (new component family) | **high** |
| 2 | Missing structural layout: `Spacer`, pager, flow row/column, drawer, pull-to-refresh, staggered grid | builder | **high** |
| 3 | Missing Material 3 components every app has: FAB sizes, navigation bar/rail, bottom app bar, toggle button, segmented button, badge, menu, sheets, tooltip | split: catalog record (some), builder policy (rest) | **high** |
| 4 | Modifier vocabulary stops short of `graphicsLayer`, `clickable`, window insets, brush fills and non-rounded shapes | builder (`CapabilityComposeCodeExporter` + capability lists) | **medium** |
| 5 | `remote-m3` offers 12 of the ~35 real components `wear-m3-catalog:remote-catalog` publishes | catalog contract / record projection | **medium** |
| 6 | `wear-m3` offers 30 of 79 — the whole picker, pager, swipe and media-control families are absent | catalog contract / record projection | **medium** |
| 7 | Four popup-surfaced families are missing from the *catalogs*, not just the builder | `yschimke/m3-catalog` + compose-ai-tools#3916 | **medium** |

---

## 1. Canvas: the missing drawing surface, and what its syntax should be

**Nothing in any of the three catalogs draws.** The closest things are three fixed leaves —
`shape/colour-dot`, `shape/linear-gradient`, `shape/radial-gradient` — and each is a hard-coded
composable, not a drawing primitive. There is no `Canvas`, no `DrawScope`, no path, no stroke, no
transform. An author who needs a chart axis, a progress arc, a divider with a notch, a badge dot on
a custom offset, a waveform, a sparkline, a custom shape silhouette or a gradient that is not a
two-stop linear one has to leave the builder.

This is not hypothetical on the Remote lane. `wear-m3-catalog`'s own `Shape/MaterialShapes` cell
[draws with a canvas because it cannot clip with a shape](https://github.com/yschimke/wear-m3-catalog/blob/main/remote-catalog/src/main/kotlin/ee/schimke/wearm3catalog/remote/ShapePreviews.kt):
`remote-creation-compose` publishes only the corner-based shape family, so a 12-sided cookie is
*painted* via `RemoteDrawScope.drawRoundedPolygon` rather than clipped out of a filled box. The
catalog the builder authors against already reaches for a canvas to say something it cannot say
otherwise, and the builder has no way to follow it.

### Both lanes already have the surface

| Lane | The surface | The scope |
| --- | --- | --- |
| Jetpack Compose / CMP | `Canvas(modifier) { … }`, `Modifier.drawBehind`, `Modifier.drawWithContent` | `DrawScope` |
| Remote Compose | `RemoteCanvas(modifier) { … }` — `LAYOUT_CANVAS` (205), `LAYOUT_CANVAS_CONTENT` (207), `CANVAS_OPERATIONS` (173) | `RemoteDrawScope` + `StandardRemotePaint` |

`CapabilityComposeCodeExporter` already emits `drawBehind { drawRect(…) }` by hand for the list-item
accent bar and for `BuilderRadialGradient` — so the export side has done this three times as
one-offs, which is the usual sign that the vocabulary should be data instead.

### Which syntax: recommendation

**Model draw operations as nodes, not as a JSON blob property.** Concretely, a `draw/canvas`
`Container` whose single ordered slot `ops` accepts `Leaf` and `Container` nodes carrying a new
`DrawOp` trait:

```json
{ "componentId": "draw/canvas", "slots": { "ops": [
    { "componentId": "draw/clip",   "properties": { "shape": "roundRect", "cornerDp": 8 },
      "slots": { "ops": [ … ] } },
    { "componentId": "draw/rect",   "properties": { "color": "primaryContainer", "style": "fill" } },
    { "componentId": "draw/arc",    "properties": { "startDegrees": -90, "sweepDegrees": 240,
                                                    "style": "stroke", "strokeWidthDp": 6,
                                                    "strokeCap": "round", "color": "primary" } },
    { "componentId": "draw/transform", "properties": { "rotateDegrees": 45 },
      "slots": { "ops": [ { "componentId": "draw/path", "properties": { "data": "M0,0 L1,1 …" } } ] } }
] } }
```

The alternative — one `draw/canvas` leaf with an `ops: []` array property — is one component instead
of a dozen, and loses everything the document model already provides: per-op typed validation
against a capability, per-op selection and inspection on the canvas, per-op MCP addressing, per-op
undo granularity, and drag-reordering. Transforms and clips nest as containers, which is exactly
`MATRIX_SAVE`/`MATRIX_RESTORE` and `CLIP_*`. Keep the shelf hidden until the selection is inside a
canvas, the way pack shelves are hidden until a pack is switched on, so the palette does not grow a
dozen entries for every author.

**Blend mode is a paint property, and it is portable.** It needs no drawing opcode of its own: it
rides on `PAINT_VALUES` (40, `implemented`) — the checked-in player in
`server/src/main/resources/rc-player/bundle.js` reads `_PaintBundle.BLEND_MODE` and applies it while
drawing — and every `DrawScope` call takes a `blendMode` argument. So it belongs in the per-op paint
block below alongside colour, style, stroke width and alpha.

**Paint is per-op properties, not a stateful `draw/paint` op.** Remote Compose has a stateful paint
(`PAINT_VALUES`, 40); `DrawScope` takes paint arguments per call. Per-op is the portable subset, and
it is the only one of the two that a property inspector can show without simulating the document.

**`draw/path` needs structured commands, not an SVG string.** The sketch above shows
`data: "M0,0 L1,1 …"`, and an opaque string cannot say which of its numbers are dp and which are
fractions of the canvas — so it cannot honour the coordinate rule in the next paragraph, and a path
mixing a 12dp inset with a half-width midpoint is unrepresentable. A path should be an ordered list
of typed commands (`moveTo`, `lineTo`, `cubicTo`, `close`) whose coordinates are the same dp ∪
fraction union as every other op. `DATA_PATH` (123) / `PATH_CREATE` (159) / `PATH_ADD` (160) already
model a path as commands rather than text on the Remote side, so this matches the wire rather than
fighting it.

**Coordinates are a union of dp and fraction.** A `.rc` document is resolution-independent, and the
`MaterialShapes` precedent above scales a *normalised* polygon by the drawing area rather than
baking pixels into the path. `{"dp": 12}` and `{"fraction": 0.5}` both need to be expressible; a
fraction-only model cannot draw a 1dp hairline and a dp-only model is not a Remote document.

### The portable op vocabulary

The intersection of `DrawScope` and the operations
[`rc-operations.manifest`](https://github.com/yschimke/rc-players/blob/main/rc-player/protocol/src/main/rc-operations.manifest)
marks `implemented`. Everything in this table can be authored once and exported to both lanes:

| Node | `DrawScope` | Remote opcode |
| --- | --- | --- |
| `draw/rect` | `drawRect` | `DRAW_RECT` (42) |
| `draw/round-rect` | `drawRoundRect` | `DRAW_ROUND_RECT` (51) |
| `draw/circle` | `drawCircle` | `DRAW_CIRCLE` (46) |
| `draw/oval` | `drawOval` | `DRAW_OVAL` (56) |
| `draw/arc` | `drawArc` | `DRAW_ARC` (152), `DRAW_SECTOR` (52) |
| `draw/line` | `drawLine` | `DRAW_LINE` (47) |
| `draw/path` | `drawPath` | `DRAW_PATH` (124) + `DATA_PATH` (123) / `PATH_CREATE` (159) |
| `draw/image` | `drawImage` | `DRAW_BITMAP` (44), `DRAW_BITMAP_SCALED` (149) |
| `draw/text` | `drawText` | `DRAW_TEXT_RUN` (43), `DRAW_TEXT_ANCHOR` (133) |
| `draw/clip` | `clipRect` / `clipPath` | `CLIP_RECT` (39), `CLIP_PATH` (38), `MODIFIER_ROUNDED_CLIP_RECT` (54) |
| `draw/transform` | `withTransform { translate/scale/rotate }` | `MATRIX_TRANSLATE` (127), `MATRIX_SCALE` (126), `MATRIX_ROTATE` (129), `MATRIX_SKEW` (128), `MATRIX_SAVE`/`RESTORE` (130/131) |

Deliberately outside the first cut, and why:

- **`drawPoints`, `drawOutline`** — Compose-side only; no `implemented` opcode answers them, so
  authoring one would produce a design that exports on one lane and refuses on the other.
- **Text on a path** — `DRAW_TEXT_ON_PATH` (53) is `implemented` on the Remote side, but Compose
  publishes no `DrawScope.drawTextOnPath`: reaching it means `nativeCanvas`, which is
  `android.graphics.Canvas` on Android and Skia elsewhere. The editor's canvas is Wasm, so a node
  the portable set promised would be one the editor cannot draw. It belongs with the Remote-only
  set below, `wasm.adapterStatus = unsupported`.
- **`DRAW_TEXT_ON_CIRCLE` (57), `DRAW_TWEEN_PATH` (125), `PATH_TWEEN` (158), `PATH_EXPRESSION` (193),
  `MATRIX_EXPRESSION` (187)** — Remote-side only. `DRAW_TEXT_ON_CIRCLE` is additionally
  `implemented_upstream_unavailable`: writable by the CMP player, readable by no Java-player
  profile. These belong to a later `remote-m3`-only shelf, declared `wasm.adapterStatus =
  unsupported` the way `wear-m3`'s undrawable components already are — not to the portable set.
- **`DATA_SHADER` (45) / AGSL** — a separate decision. A shader is a *program*, not an op, and
  nothing in the document model holds source text today.

### What each lane would emit

```kotlin
// Jetpack Compose / CMP
Canvas(modifier = Modifier.size(48.dp)) {
  drawArc(
    color = primaryColor,
    startAngle = -90f, sweepAngle = 240f, useCenter = false,
    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
  )
}
```

```kotlin
// Remote Compose
RemoteCanvas(modifier = RemoteModifier.size(48.dp)) {
  val paint = StandardRemotePaint().apply {
    color = primaryColor
    style = PaintStyle.Stroke
    strokeWidth = 6.rdp
    strokeCap = StrokeCap.Round
  }
  drawArc(0f, 0f, size.width, size.height, -90f, 240f, paint)
}
```

The Wasm canvas draws the same ops directly — this is the rare component family where the editor's
canvas is *authoritative* rather than approximate, because it is running the same `DrawScope` the
Compose lane exports to.

---

## 2. Layout: what a screen is built out of

The builder has `box`, `column`, `row`, `lazy-column`, `lazy-row`, `lazy-grid`, `scaffold`,
`supporting-pane-scaffold` and `horizontal-carousel`, with a good property surface (arrangement,
spacing, alignment, content padding, span, reverse layout, scroll-state keys). The containers
themselves are not the gap. These are:

| Missing | Why it matters | Notes |
| --- | --- | --- |
| **`Spacer`** | The single most common composable in real screens after `Text`. A *uniform* gap is already covered — `layout/row.horizontalSpacingDp` and `layout/column.verticalSpacingDp` emit `Arrangement.spacedBy` — so the gap is the rest of what a `Spacer` does: one uneven gap between two of five children, a weighted `Spacer(Modifier.weight(1f))` pushing a trailing item to the end, and matching the structure a hand-written screen would have. | Trivial: a `Leaf` with `width`/`height`/`weight`. |
| **`HorizontalPager` / `VerticalPager`** | Onboarding, tabs-with-swipe, media carousels, every Wear pager screen. `wear-m3-catalog` publishes `Pager` and three `PageIndicator` components; the builder has none. | Needs a page-count property and a repeated `pages` slot; the `RepeatedContent` trait already exists. |
| **`FlowRow` / `FlowColumn`** | Chip groups and tag clouds. A chip row that does not wrap is the classic builder-vs-real-screen divergence. | `maxItemsInEachRow`, cross-axis spacing. |
| **`LazyVerticalStaggeredGrid`** | Photo/feed grids. `lazy-grid` covers the uniform case only. | |
| **`ModalNavigationDrawer` / `DismissibleNavigationDrawer`** | The other half of the app-level navigation shell; `scaffold` has `topBar`, `snackbarHost` and `content` and no drawer slot. | See also §3's navigation bar/rail. |
| **`NavigationSuiteScaffold`** | The adaptive shell that picks bar vs rail vs drawer by window size. The builder already has `supporting-pane-scaffold`, so adaptive layout is in scope. | |
| **`PullToRefreshBox`** | Any list backed by a network. | |
| **`BoxWithConstraints`** | The escape hatch for size-dependent layout. Hard to model — its content is a lambda over constraints — but its absence means breakpoint behaviour cannot be authored at all. | Worth an explicit "not doing this, use adaptive scaffolds" note if the answer is no. |
| **`SwipeToDismissBox` / `SwipeToReveal`** | Row actions in a list. `wear-m3-catalog` publishes both. | |
| **`AnimatedVisibility` / `Crossfade` / `Modifier.animateContentSize`** | There is no motion vocabulary anywhere in the document model. | Probably a separate decision, not a component. |
| **Sticky headers in `lazy-column`** | `stickyHeader` is a `LazyListScope` call, not a component, so it needs a slot or an item flag. | |

One smaller one inside what exists: `lazy-column`/`lazy-row` have no `contentType`, which matters
for real list performance in a heterogeneous list. Item *keys* are not a gap — `emitLazy` already
wraps every child in `item(key = …)`, using the child's `stableKey` when it has one and its stable
node id otherwise. And `scaffold` has no
`bottomBar` or `floatingActionButton` slot — which is §3's problem showing up in §2.

---

## 3. Material 3: what `m3-catalog` publishes and the builder does not offer

`m3-catalog` publishes 58 `@CatalogComponent` ids. The builder's `m3/*` shelf has 25. Subtracting
the specimen sheets that are not authorable components (`Color/Role grid`, `Typography/Type scale`,
`Shape/Corner scale`, `Shape/MaterialShapes`), these are the real absences, ordered by how often a
screen contains one:

| Absent | Published by `m3-catalog` as | Note |
| --- | --- | --- |
| **Navigation bar** | `NavigationBar/Short` | Bottom navigation. No app-level navigation is authorable today. |
| **Navigation rail** | `NavigationRail/Standard`, `NavigationRail/Wide` | The tablet half of the same. |
| **FAB sizes and extended FAB** | `Fab/Standard`, `Fab/Extended` | Partially present: `m3/button` has `style = "fab"`. The size axis (small/medium/large) and the extended variant are not reachable, and a FAB modelled as a button variant cannot go in a scaffold slot that does not exist (§2). |
| **Bottom app bar** | `BottomAppBar/Standard` | |
| **Top app bar variants** | `TopAppBar/Small` | The builder has `m3/center-aligned-top-app-bar` only. Small, medium and large are the common three; the catalog also renders medium and large stickers. |
| **Toggle button** | `ToggleButton/{Filled,Tonal,Elevated,Outlined}` | An expressive-era component with no builder equivalent. |
| **Segmented button** | `SegmentedButton/SingleChoice` | Plus the multi-choice sticker. |
| **Split button** | `SplitButton/Filled` | |
| **Chips other than filter** | `Chip/Assist`, `Chip/Input`, `Chip/Suggestion` | The builder has `m3/filter-chip` alone. A chip row is a `FlowRow` of mixed chip types. |
| **Badge** | `Badge/Number` (+ dot) | Needed the moment a navigation bar exists. |
| **Vertical divider** | `Divider/Vertical` | The builder has horizontal only. |
| **Range and vertical slider** | `Slider/Range` (+ vertical sticker) | |
| **Secondary and scrollable tabs** | `Tabs/Secondary` | The builder has `m3/primary-tab-row`; scrollable is a variant of both. |
| **Loading indicator** | `LoadingIndicator/Uncontained` (+ contained) | Distinct from `m3/progress-indicator`. |
| **Vertical floating toolbar** | (sticker) | The builder has the horizontal one. |
| **Elevated and outlined button** | `Button/Elevated`, `Button/Outlined` | A variant-value gap, not a component gap: `m3/button.style` is `filled\|filledTonal\|text\|fab`. `m3/card.variant` (`filled\|elevated\|outlined`) and `m3/icon-button.variant` (`standard\|filled\|tonal\|outlined`) are already complete — this is the one that is not. |
| **Wavy progress indicators** | (stickers: `LinearWavyProgress`, `CircularWavyProgress`) | `m3/progress-indicator.variant` is `linear\|circular`; the expressive wavy pair is a third and fourth value. |

Most of these are *variant axes on components the builder already has*, which is the cheapest kind
of gap to close: a `variantProperty` in `componentMenu` plus allowed values on an existing property.
The genuinely new components are navigation bar, navigation rail, bottom app bar, badge, toggle
button, segmented button, split button, vertical divider and loading indicator — nine.

### 3b. Four families are missing from the catalog too

`Menus.kt`, `BottomSheets.kt`, `SideSheets.kt` and `Tooltips.kt` exist in `m3-catalog` as composables
but carry **no `@CatalogComponent`**, so they are in no component record and no pack projection can
reach them. The reason is recorded in `Menus.kt`: *"Not a catalog comparison until popup surfaces can
be captured (compose-ai-tools#3916)."*

So dropdown menus, modal and standard bottom sheets, side sheets, and plain/rich tooltips are absent
from the builder because they are absent one layer down. That is worth stating plainly, because it
means the fix is not in this repository: it is compose-ai-tools#3916, then annotations in
`m3-catalog`, then the builder gets them for free through the record. Four families that every
application has, blocked on one render-capture issue.

---

## 4. Modifiers

The document's modifier model is good: an ordered list of `{"type": …, …args}` objects, emitted by
`CapabilityComposeCodeExporter.modifierExpression`, gated per component by `modifierCapabilities`.
Twenty-eight types are writable. What is missing splits into two kinds.

### Missing modifier types

| Missing | Why | Note |
| --- | --- | --- |
| **`graphicsLayer`** | The one the question named. It is how a real screen does rotation, scale, alpha, translation, `clip`, shadow elevation, `TransformOrigin`, `RenderEffect` and `CompositingStrategy` in one place — and the only way to express several of them at all. The builder has `rotate`, `scale`, `alpha` and `zIndex` as separate modifiers, which cover the easy third. The editor itself uses `graphicsLayer` for its own zoom. | Maps to Remote `MATRIX_*` for the transform subset; the effect subset is Compose-only and would want `wasm`/`code` notes saying so. |
| **`clickable`** | There is no way to make an arbitrary node interactive. `m3/button` has `onClickAction` and the value-semantics model exists, but a clickable `Card` or `Row` — the most common list pattern there is — is unauthorable. | Should reuse `onClickAction`'s shape, not invent a second one. |
| **Window-inset padding** | `statusBarsPadding`, `navigationBarsPadding`, `imePadding`, `safeDrawingPadding`, `windowInsetsPadding`. Insets are not half-modelled — they are unreachable everywhere: `layout/scaffold` declares `contentWindowInsets`, but `emitScaffold` never reads it, it is absent from `HANDLED_FIELDS`, and the Wasm renderer hardcodes `WindowInsets(0, 0, 0, 0)` (`UiBuilderRenderer.kt:854`). So the work is the five modifiers **and** making the scaffold property mean something. | Edge-to-edge is mandatory on Android 15+, so this is not optional for generated code that runs. |
| **`drawBehind` / `drawWithContent`** | Does **not** fall out of §1 for free. A modifier element has no identity and no slots, and `SetModifiersMutationV1` replaces the whole list at once — so a modifier holding child draw nodes cannot carry the node identity, MCP addressing and per-op undo that §1's node model is chosen for. Either give a canvas an ordinary component slot and place it behind its sibling, or change the modifier wire shape — which is `compose-preview-contracts` work, not this repository's. The exporter already writes `drawBehind` by hand twice, so the want is real; the cheap route is not. | |
| **`wrapContentWidth` / `wrapContentHeight`** | Only `wrapContentSize` exists. | Trivial. |
| **`defaultMinSize`, `requiredSize`, `sizeIn`** | Constraint-shaping that `size`/`widthIn`/`heightIn` do not cover. | |
| **`paddingFromBaseline`** | Text alignment in dense lists. | |
| **`blur`** | | |
| **`animateContentSize`** | See §2 on motion. | |

### Missing arguments on modifiers that exist

These bite as often as the missing types, because the modifier is offered and then cannot say the
thing:

- **`background` and `border` take a colour only** — no `Brush`. A gradient background is the reason
  `shape/linear-gradient` exists as a *component*, which is a workaround for a missing modifier
  argument. `remote-m3`'s widget `background` slot is a second workaround for the same thing.
- **`clip` and `shape` are `RoundedCornerShape(dp)` only** — no `CircleShape`, no
  `CutCornerShape`, no per-corner radii, no `MaterialShapes`. A circular avatar is
  `RoundedCornerShape(veryLargeDp)` today, which is not what the code should say.
- **`padding` is four absolute edges** — no `horizontal`/`vertical` shorthand and no `PaddingValues`
  reuse, so generated code is more verbose than hand-written code at exactly the point a reviewer
  looks.
- **`fillMaxWidth`/`fillMaxHeight`/`fillMaxSize` take no `fraction`.**
- **`offset` is absolute dp only** — no lambda offset, which is fine, but also no `RTL`-aware
  `absoluteOffset` distinction.

---

## 5. `remote-m3` is 12 components against a catalog of ~35

`wear-m3-catalog:remote-catalog` publishes 53 `@CatalogComponent` ids. Setting aside the 15 theme,
typography and typeface specimens, that is roughly 35 real components. The builder's `remote-m3`
catalog offers nine borrowed foundation ids (`layout/box`, `layout/column`, `layout/row`,
`m3/surface`, `m3/text`, `remote-compose/document`, `remote-compose/custom`, `shape/linear-gradient`,
`asset/image`) plus two widget containers and `remote-m3/lottie` — 12 in all.

Absent, and each published and rendered by the catalog today: the whole `Button` family (filled,
tonal, outlined, child, compact, custom-shape, image-background, loading, named-label), `IconButton`
(five variants), `TextButton`, `Card` / `Card/Outlined` / `TitleCard` / `AppCard`, `Icon`,
`ListHeader` / `ListSubHeader`, `Scaffold`, `ButtonGroup`, `CircularProgressIndicator` /
`ArcProgressIndicator` / `LevelIndicator` / `ScrollIndicator`, `PageIndicator` (horizontal, vertical,
interactive), `Shape/MaterialShapes` (the canvas case from §1), and the `Text` variants.

The document's own comment says the subset is "deliberately a reviewed subset, not an alias for the
complete Material 3 catalog", which was the right call for a first cut. It has not moved since, and
the gap is now large enough that a Remote widget of any complexity is not authorable. This is the
clearest case for the catalog contract: `remote-catalog` publishes a component record, and a
projection of that record is most of this list without anybody transcribing a capability table.

## 6. `wear-m3` is 30 components against a catalog of 79

Same shape, less acute — the 23 `wear-m3/*` ids plus seven borrowed foundation ids cover the common
Wear screen. Absent families, each published by `wear-m3-catalog`: `Picker` / `PickerGroup` (7 ids),
`Pager` and the three `PageIndicator` families, `SwipeToDismissBox` / `SwipeToReveal`, `Media`
controls (7 ids), `Auth` (5 ids), `Placeholder` (3 ids), `AnimatedText` / `FadingExpandingLabel`,
`IconToggleButton` / `TextToggleButton`, `LevelIndicator`,
`FastScrollingTransformingLazyColumn`, and the one-handed-gesture set.

Three things that *look* absent are genuinely authorable, verified at the emitter and listed here so
a follow-up does not go looking for them: `TimeText` (`wear-m3/screen-scaffold.timeText`, emitted as
the `AppScaffold(timeText = …)` wrapper, `WearScreenCodeExporter.kt:150`); `ArcProgressIndicator` and
`SegmentedCircularProgressIndicator` (`wear-m3/progress-indicator.variant`, `…:1207-1209`); and
`TitleCard` / `OutlinedCard` (`wear-m3/card.variant`, `…:520,547`).

And two that the capability JSON *declares* but nothing emits — the failure mode this audit's method
note warns about, caught in review:

- **`ScrollIndicator`** is a `wear-m3/screen-scaffold` property, and the exporter appends
  `scrollIndicator = { … ScrollIndicator(listState) }` unconditionally (`…:171`) without ever
  reading it. Every Wear screen gets one and no design can say otherwise, so it is a gap: either
  honour the boolean or drop the property.
- **`AppCard`** is a declared `wear-m3/card.variant` value (`app`), but that branch falls through to
  the `TitleCard` emit. A design asking for an `AppCard` silently gets a `TitleCard`, which is worse
  than the component being absent.

---

## 7. Where each fix belongs

Sorting the findings by which repository owns them, because that is what decides whether a fix needs
a release of this repository:

**This repository, builder-side, no catalog change:**
- §1 the whole `draw/*` family and its two emitters
- §2 `Spacer`, pager, flow layouts, staggered grid, drawer, pull-to-refresh, scaffold slots
- §4 every modifier type and argument **except** `drawBehind`/`drawWithContent`, whose child-node
  form needs a modifier wire shape that only `compose-preview-contracts` can grant — see §4's row

**The catalog contract, once catalogs publish `ui-builder.json`:**
- §3's variant axes and nine new Material 3 components
- §5 and §6 in bulk — these are the plan's whole justification. §5 enumerates at least 32 published
  Remote ids the builder cannot place and §6 the Wear families; a single subtracted total is not
  worth quoting, because nine of `remote-m3`'s twelve entries are borrowed foundation ids that map
  to no published component of that catalog, so `35 − 12` measures nothing.

**Upstream of everything:**
- §3b — compose-ai-tools#3916 (popup capture), then `@CatalogComponent` annotations in
  `m3-catalog` for menus, sheets and tooltips.

## Suggested first batch

If only one batch happens, these are the items whose absence most often forces an author out of the
builder, and none of them needs a catalog release:

1. `Spacer` — one leaf, hours of work, used on every screen.
2. `Modifier.clickable` reusing `onClickAction` — the clickable card/row is the most common list
   pattern in existence.
3. `background`/`border` taking a brush, and `clip` taking `CircleShape` — three argument
   extensions that retire two component-shaped workarounds.
4. Window-inset padding modifiers, and making `layout/scaffold.contentWindowInsets` reach the
   emitter and the renderer — generated code that does not run edge-to-edge correctly is generated
   code a developer has to fix by hand.
5. The `draw/*` family from §1, portable subset only.
6. Scaffold `bottomBar` + `floatingActionButton` slots, and the navigation bar and FAB components
   that go in them.
