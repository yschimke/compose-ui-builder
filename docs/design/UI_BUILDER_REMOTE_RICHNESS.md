# UI builder: richer Remote Compose documents

Plan and running status, 2026-10. The work that takes a ui-builder design from "static layout of
catalog components" to the document Remote Compose can actually play: computed values, drawing,
the whole modifier vocabulary, the remaining layouts and components. It builds on the
[completeness review](UI_BUILDER_REMOTE_COMPOSE_COMPLETENESS_REVIEW.md), whose ordering it keeps:
values first, because everything else — a clock, a gauge, an animated arc — is a value the player
computes.

## Authoring target

The builder authors against the **latest released alpha**: `remote-material3` 1.0.0-alpha13
(checked against Google Maven, 2026-10-10) for the component record, and `remote-creation-compose`
1.0.0-alpha20 for the modifier vocabulary (alpha21 is out; `remote-modifiers-v1.json` has not been
regenerated against it yet). The embedded `remote-m3-record-v1.json` matches alpha13's signatures; export never writes a snapshot-only parameter, so every exported widget compiles against
the alpha. The target moves with each new alpha, by refreshing the record and its goldens together.

How the record was refreshed to alpha13, so the next refresh can repeat it: the 17 components
remote-m3-catalog's **released** lane stickers came from its `composePreviewDiscover
-PremoteSnapshot=` record (alpha13), with `bindings` stripped (the fixture never carried them) and
each `componentIds` unioned with the previous entry's. The other 10 — the selection buttons, sliders,
stepper, edge button and linear progress, which that catalog stickers only on its snapshot lane — kept
their entries after their signatures were checked unchanged between alpha12 and alpha13 sources. Then
`:ui-builder-runtime:test --tests '*SynthesisedCatalogGoldenTest*' -PuiBuilderGoldens=write
-PuiBuilderRemoteCompose=true`. Never take the published `design-artifacts/remote-m3` record: that
sheet is drawn on the snapshot lane and is ahead of any release.

alpha13 is what the published catalog's snapshot record had run ahead with before: `backgroundColor`
on both page indicators, a `border: RemoteBorderStroke?` on `RemoteCard`, `RemoteAppCard` and
`RemoteTitleCard`, and `RemoteOutlinedCard`'s `border` becoming a `RemoteBorderStroke` with its
`borderColor` gone. A stored design that still sets `borderColor` on `remote-m3/remote-outlined-card`
keeps opening and editing: an undeclared property a design already carried is tolerated and left out
of export (`UndeclaredPropertyToleranceTest`).

## Time text

`remote-m3/remote-time-text` is `RemoteTimeText`: the device's time curved along the top of its
bounds, with optional `leadingText` and `trailingText`, a `separator` (`·` by default), a literal
`textSizeSp` and a `color`. The embedded record is generated from the catalog's sheets and has no
row for it, so it is declared by hand in `UiTimeText`, which the palette entry, the canvas stand-in
and the emitter all read. The time is the player's own, so it is not a property; the canvas curves
the design's fixed preview time in its place. It is on the Content shelf and is not donated by the
generic Remote Compose foundation, since it is a Wear Material 3 component.

## Theme node

`remote-m3/remote-material-theme` is `RemoteMaterialTheme` around one child, re-skinning that
subtree with the colour roles it overrides. The roles and their property names are the Wear screen
scaffold's (`WearScreenTheme`: `themePrimaryColor`, `themeOnSurfaceColor`, …), so an author who knows
one knows the other, and every one exists on `RemoteColorScheme`. Each override is a literal, a
theme role (read from the enclosing scheme) or a computed colour; the exporter writes
`RemoteMaterialTheme(colorScheme = RemoteMaterialTheme.colorScheme.copy(…)) { … }` with only the
roles set, and a theme that sets none writes its child alone. The canvas draws the child under the
same copy the scaffold uses. Declared by hand in `UiRemoteTheme`; on the Layout shelf; no modifiers,
since `RemoteMaterialTheme` takes none.

## Button and card overloads

The embedded record holds one overload per symbol, and for `RemoteButton` and `RemoteCard` it is
the plain `content` one. The other two are offered as follows.

- **Label button.** `remote-m3/remote-label-button` is `RemoteButton`'s `label` / `secondaryLabel`
  / `icon` overload, the Wear button shape most designs want. Its record is assembled in
  `RemoteMaterial3` from the button's own and `RemoteCheckboxButton`'s slot parameters (the same
  types), with `label` ahead of the defaulted parameters so it is written named:
  `RemoteButton(onClick = …) { … }` would match both overloads. The canvas draws the label in Wear's
  button. The published catalog has no row for it yet (`UNPUBLISHED_REMOTE_M3_IDS`), so a design
  using it opens only against the built-in catalog.
- **Container image.** `containerImageKey` on `remote-m3/remote-button`, the label button and
  `remote-m3/remote-card` is an asset key, validated like `asset/image`'s `assetKey`. Setting it
  writes the `containerPainter` overload, `containerPainter = painterRemoteImageBitmap(…)`, with the
  bitmap passed as the image node's is. The canvas draws the plain container.

## Remote modifier vocabulary

A `RemoteModifier` call the typed modifiers do not name is a `remoteCall` modifier: `name` and
`args`, each argument a value — a literal, a state read or a computed `expr`
(compose-preview-contracts 3.20.0, #141).

The calls a document may name are not hand-written. `scripts/remote-vocabulary/generate_remote_modifiers.py`
reads the released `remote-creation-compose` sources and writes
`docs/design/fixtures/ui-builder/remote-modifiers-v1.json`: every public top-level
`RemoteModifier.<name>(…)` overload whose required parameters are all plain values, with each
parameter's type, nullability and whether it is optional. Interaction calls are left out (behaviour
is event bindings), and so is anything needing a lambda, a transition or scroll state. Regenerate
it with each new alpha, together with the component record.

From that one file:

- **Export** resolves the overload the arguments pick (fewest parameters, then dp over a bare
  number, then a `Remote*` type over a plain one), writes named arguments in each parameter's own
  type, and refuses by name a call, an argument or a computed value the released API cannot take.
- **The editor** offers every call no typed modifier already writes, keyed `remoteCall:<name>`,
  added with a starting value per required argument; numbers and booleans edit in the inspector.
- **The canvas** draws the calls Compose has an honest counterpart for (`defaultMinSize`,
  `wrapContentWidth`/`Height`, `basicMarquee`) and leaves the rest to the player, which the device
  preview runs.

`graphicsLayer` is in it too: its value overload was being dropped by the generator because a line
comment sits inside its parameter list (`cameraDistance: RemoteFloat = 8f.rf, // …`); the generator
now strips comments before parsing. The canvas draws it with Compose's `graphicsLayer`, argument for
argument.

`semantics` is declared by hand beside the generated calls, because its released signature is a
receiver lambda (`semantics(mergeDescendants) { contentDescription = …; role = … }`): the values it
sets — `contentDescription`, `stateDescription`, `role` (one of the roles the player maps), `enabled`
— and `mergeDescendants` are its arguments, and `clear` writes `clearAndSetSemantics` instead. It is
accessibility, not interaction, which stays an event binding. `drawWithContent` is not offered: a
modifier holds values, not draw operations, and a `layout/box` with a `draw/canvas` behind its
content draws the same thing as nodes.

## Motion between states

Two typed modifiers animate a "Show by state" box's switch, which is a `RemoteStateLayout` in the
export and the device preview:

- **`sharedElement`** (`key`) matches one element across the branches: the same key on both sides
  is one element, and its bounds move between them instead of one copy fading out while another
  fades in. With only a key it exports as `animationSpec(key, true)`, the overload every line has.
  It may also name `durationMs`, `easing` (the `tween` easings), `enter` and `exit`, and then
  exports as `sharedElement(key, remoteTween(…), RemoteEnterTransition.…, RemoteExitTransition.…)`,
  from alpha20.
- **`animateEnterExit`** gives a component its own way in and out as its branch comes and goes —
  `fadeIn`, `slideInLeft`/`Right`/`Top`/`Bottom`, `rotate` in; the matching `…Out` and `rotate`
  out. A slide is named by the way it travels: `slideInBottom` moves down into place. It exports
  as `animationSpec(-1, spec, spec, enter, exit)`, not the creation library's own
  `animateEnterExit`: that writes animation id 0, which every player reads as "animation disabled",
  so its transitions never play. `-1` is the unset id — enabled, and matched to nothing.

Padding is part of a Remote Compose component, so a shared element's padding morphs with it. An
element that should travel is placed by its parent (an alignment, or a box around it), not by its
own padding. A published `remote-compose` catalog offers both (and `remoteCall`,
`collapsiblePriority`) on every component whose policy states no `modifiers`, the way the
synthesised remote-m3 does, so they survive the catalog-owned cutover. The canvas draws neither
modifier; the device preview, which runs the CMP player, plays both (rc-players ≥ the release
carrying yschimke/rc-players#604 and #606).

## Where it stood

A property was a literal, a state read or a loop binding, and only text and record parameters could
read state. No modifier argument could be bound. There was no drawing at all: no `RemoteCanvas`, no
paths, no shapes beyond three fixed leaves. The modifier vocabulary was 26 calls of roughly fifty, the
device preview rendered five of the 25 published `remote-m3` components, and dynamic authoring sat
behind a compile-time flag.

## Milestones

| | Scope | Status |
| --- | --- | --- |
| M1 | Computed values: `expr`/`system` wrappers, formula text, canvas evaluation, Remote Kotlin lowering, inspector | Landed |
| M2 | `draw/canvas` and `draw/*` operation nodes: shapes, paths, text, transforms, paint, gradients, clips (box or path), morphs, curved text, conditionals, loops | Landed |
| M3 | Events and actions: long/double click, touch, scroll actions, expression writes, host actions | Long press and double tap landed (`combinedClickable`); the rest planned |
| M4 | Remaining `RemoteModifier`s: graphicsLayer, visibility, semantics, marquee, ripple, brushes and shapes as values | Landed as `remoteCall` over the generated vocabulary (33 calls); `graphicsLayer` (its value overload) and `semantics` (hand-declared, written as its lambda) included; `drawWithContent` is a box with a canvas |
| M5 | Remaining components: `RemoteTimeText`, page indicators, theme node, button and card overloads | Landed: page indicators, `RemoteTimeText`, the theme node, the label button and container images |
| M6 | Device preview: every published component, and expressions played live | In progress (remote-m3-catalog) |

Each milestone lands across the editor, the canvas, validation, MCP-visible document shape and the
Remote Kotlin export together. The JSON-to-`.rc` lane follows where it is cheap and never gates one.

## Computed values

A computed property value is one of two wrappers. Both are ordinary property values: they ride
`SetProperty`, sit wherever a literal can, and are checked by the same validators.

```json
{"type": "system", "value": "time.secondOfHour"}

{"type": "expr", "op": "mul", "args": [
  {"type": "expr", "op": "mod", "args": [
    {"type": "system", "value": "time.secondOfHour"},
    {"type": "int", "value": 60}]},
  {"type": "int", "value": 6}]}
```

Operands are the literal wrappers (`float`, `int`, `bool`, `string`, `color`), `state`, `binding`,
`system` and nested `expr`. The tree is typed: `UiExpressions.check` infers each operation's kind
from its operands and refuses what does not type, with the path of the operand it is about.

| Operations | Kinds |
| --- | --- |
| `add` `sub` `mul` `div` `mod` `min` `max` `clamp` `abs` `neg` | Int stays Int; mixing promotes to Float |
| `floor` `ceil` `round` `sqrt` `pow` `sin` `cos` `tan` (radians) `lerp` `toFloat` | Float |
| `asin` `acos` `atan` `atan2(y, x)` (radians) `degrees` `radians` `exp` `ln` `log10` `sign` `hypot(x, y)` | Float |
| `fract(x)` (`x - floor(x)`) `step(edge, x)` `smoothstep(edge0, edge1, x)` | Float, with their GLSL meaning; written out over the public operators |
| `toInt` | Int |
| `eq` `ne` `lt` `le` `gt` `ge` | Boolean |
| `and` `or` `not` | Boolean |
| `select(condition, ifTrue, ifFalse)` | the branches' kind |
| `concat(…)` `toString(x)` | String; numbers print as the player prints them |
| `format(x, decimals[, minDigits])` | String: exactly `decimals` (0–6, literal) digits after the point, ties away from zero (the player adds half a unit and truncates), a negative always signed, the whole part zero-padded to `minDigits` (1–9, literal), no grouping — `format(7.5, 1, 2)` is `07.5`. Exports as `x.toRemoteString(DecimalFormat("00.0", DecimalFormatSymbols(Locale.ROOT)))` (`java.text`, the released API's type), the player's text-from-float |
| `uppercase(text)` `lowercase(text)` `trim(text)` `substring(text, start[, end])` | String; `start`/`end` are Int |
| `hsv(hue, saturation, value[, alpha])` `rgb(red, green, blue[, alpha])` | Colour from components 0–1, hue as a fraction of a turn — `RemoteColor.hsv` / `rgb` |
| `rand()` `randRange(from, to)` | Float drawn by the player; a preview shows the middle (0.5, the midpoint) |
| `pingPong(x, max)` | Float: `x` folded between 0 and `max`, as the player's PINGPONG (from `abs(x)`, so negative `x` stays in range too); written out over public operators |
| `spline(t, v0, v1, …)` `splineLoop(t, v0, v1, …)` | Float: the player's monotonic spline through 2–64 literal control points spaced evenly from `t` = 0 to 1, a straight line past the ends; `splineLoop` first wraps `t` into 0 up to 1 (repeat the first point last for a seamless loop). Exports as `evalSpline(RemoteFloatArray(…), loop, t)`; the preview runs remote-core's `MonotonicSpline` in Float |
| `cbrt(x)` `copySign(magnitude, sign)` | Float |
| `secondsUntil(instant)` `minutesUntil(instant)` `hoursUntil(instant)` | Float: from now until a literal ISO-8601 UTC instant (`"2026-12-25T00:00:00Z"`), negative once it has passed — the player's time-from-now, `deltaFromReferenceInSeconds(RemoteLong(…))`; a preview counts from its clock |
| `tween(x, ms[, easing])` `spring(x[, stiffness[, dampingRatio]])` | Float: `x`, animated by the player whenever it changes |

`tween` and `spring` export as `animateRemoteFloatAsState(x, remoteTween(…))` /
`remoteSpring(…)`. Their spec is fixed when the document is written, so it must be literal: a
whole-millisecond duration of at least 1, an easing among `standard` `linear` `accelerate` `decelerate` `anticipate`
`overshoot` `bounce` `elastic`, and a stiffness and damping ratio above 0. The canvas draws where
the animation settles, as a still frame of a finished animation; a played document animates. A
state change is what moves the target — `tween(select(on, 150, 40), 600)` slides when a click
toggles `on`.

System values follow the player's `RemoteClock`:

| Value | Meaning |
| --- | --- |
| `time.hour` | hour of day, 0–23 |
| `time.minuteOfDay` | minutes since midnight, 0–1439 |
| `time.secondOfHour` | seconds into the hour, 0–3599 |
| `time.continuousSecond` | the same, continuously |
| `time.dayOfWeek` | 1 (Monday) – 7 |
| `time.dayOfMonth` | 1–31 |
| `time.utcOffset` | seconds |
| `time.animation` | seconds since the player started the document; 0 in a frozen preview |
| `time.animationDelta` | seconds since the previous frame, for motion stepped frame by frame; 0 in a preview |
| `time.month` `time.dayOfYear` `time.year` | 1 (January) – 12, 1–366, the year |
| `device.density` | pixels per dp |
| `device.fontScale` | the user's font scale, 1 at the default size: the player's 14sp font size over 14 dp's pixels |
| `device.windowWidth` `device.windowHeight` | the window in dp (its pixels over the density) |
| `canvas.width` `canvas.height` | the space a canvas's operations draw in: its size in dp, or its viewport's units when it has one. Read only by a canvas's operations; elsewhere the Remote writer refuses it. A stated size exports as its number, an unsized canvas as its measured pixels over the density |
| `touch.x` `touch.y` `touch.velocityX` `touch.velocityY` | the last touch and its speed, in dp and dp per second (the player's pixels over the density); 0 in a preview |
| `touch.time` | when the last touch happened, on the `time.animation` clock; 0 in a preview |
| `sensor.light` | ambient light, lux |
| `sensor.accelerationX/Y/Z` `sensor.rotationX/Y/Z` `sensor.magneticX/Y/Z` | the accelerometer (m/s²), gyroscope (rad/s) and magnetometer (µT), as the host player feeds them; 0 where it has none, and in a preview |

The `device.*` values preview from the document's `environment` (`density`, `fontScale`, `widthDp`,
`heightDp`). A canvas whose operations read `canvas.*` resolves them once it knows its size,
the stated one or what it was given.

### Formula text

The inspector shows and accepts formulas rather than trees: `time.secondOfHour % 60 * 6`,
`concat(count + 1, " left")`, `select(on, "On", "Off")`, `!on || count > 3`. Identifiers are state
variables or system values, `@field` is a row field, functions are the operation names, infix
operators follow C precedence. `UiExpressions.parseFormula` and `format` are inverse; the document
only ever stores the tree, so MCP and hand-written fixtures use the same shape the inspector writes.

### What each lane does with one

- **Canvas.** `CanvasRenderTree` evaluates every computed property and modifier field before an
  adapter sees the node, at the preview state, the row or placement arguments in scope, and the
  design's `environment.fixedTime` (Wear's 10:10:30 when unset), or the wall clock where a preview
  lets time run (`environment.animations` = `running`). Adapters draw a literal and never
  learn there was an expression. A runtime that plays expressions itself passes
  `evaluateExpressions = false`.
- **Remote Kotlin.** `RemoteContentEmitter` lowers the tree to the operators the player evaluates —
  `RemoteFloat`/`RemoteInt` arithmetic, `isLessThan`, `RemoteBoolean.select`, `RemoteTime()`, the
  top-level `clamp`/`sin`/`lerp` — so the exported widget computes the value on the watch, not at
  export time. Accepted on text, colour, every record parameter typed `Remote*`, and the numeric
  modifier arguments (`alpha`, `rotate`, `scale`, `zIndex`, and dp arguments through
  `asRemoteDp()`).
- **Validation.** `propertyWrapperIssue` checks the tree's shape on every write;
  `stateBindingMatchesCatalog` checks the kind it produces against the property: a number into a
  number, a Boolean into a flag, anything printable into text, a colour only into a colour.
- **Inspector.** On a Remote Compose catalog every property one of the probe expressions is accepted
  on offers **Formula**; a computed property shows its formula, editable, with **Clear** to return
  to a literal. Offered only when `UiExpressions.wireSupported`: the linked contracts (3.20.0 and
  later) can carry the value.

### Actions that write a formula

An action's value — `set`, `select`, `setText` — or an `increment`'s amount may be a formula, so a
click can move state on from wherever it is: `x = x + 40`, `label = concat(count, " taps")`,
`count += count`. In the inspector a value starting with `=` is a formula (`= x + 40`); without it
the text is a literal, as before.

- **Validation.** The tree must type against the document's state and produce what the variable
  holds — a number into a number (an Int into a Float, not back), a flag into a flag, anything
  printable into text. `selectOrClear` keeps a literal: its value is compared as well as written.
- **Canvas.** Evaluated when the action runs, over the state as earlier actions in the same event
  left it, at the canvas clock.
- **Remote Kotlin.** `valueChange(x, (x + 40.rf))`: the player computes it on the watch when the
  click lands. An increment is `valueChange(x, (x + amount))`.
- **Compose and JSON.** Refused with a located `TODO`, as computed values are there.

### Loop rows

A `layout/for-each` row field read inside an expression (`concat(@name, ": ", format(@goal, 0))`)
is a row property of the matching Remote type (`RemoteString`, `RemoteFloat`, `RemoteBoolean`,
`RemoteColor`), each row supplying its literal. A field must have the same kind in every row, and
Int fields are not readable, as for component arguments. `@index` is the row's position from 0, as
in a draw loop, unless the rows author a field of that name; the export adds it to the row class
only when the template reads it.

### Not yet

- Named host inputs (`rememberNamedRemote*`).
- The regular Compose and JSON lanes refuse computed values with a located message.

## Drawing

A `draw/canvas` is a layout node like any other — it takes modifiers, sits in a widget body, gets a
size — and its `ops` slot holds **operation nodes**, drawn in order. Operations are nodes rather
than one opaque blob so selection, comments, MCP edits and the layer tree keep working per shape,
and so every geometry property is an ordinary property: a literal, a state read or a computed
value. The vocabulary is declared once, in `UiDrawing`, and read by the catalog declaration, the
canvas stand-in and the Remote emitter.

| Operation | Properties (dp unless noted) | Remote call |
| --- | --- | --- |
| `draw/rect` | `xDp` `yDp` `widthDp` `heightDp` `cornerRadiusDp` + paint | `drawRect` / `drawRoundRect` |
| `draw/circle` | `centerXDp` `centerYDp` `radiusDp` + paint | `drawCircle` |
| `draw/oval` | box + paint | `drawOval` |
| `draw/arc` | box, `startAngle` `sweepAngle` (degrees), `useCenter` + paint | `drawArc` |
| `draw/line` | `startXDp` `startYDp` `endXDp` `endYDp`, colour, stroke | `drawLine` |
| `draw/path` | `pathData` (SVG), `viewportWidth` `viewportHeight` + paint | `drawPath(RemotePath(…))`, scaled to the canvas |
| `draw/polyline` | `count` (2–256, a literal), `index` (a name, `i` when absent), `pointXDp` `pointYDp` (formulas reading `@i`), `closed` + paint | `remotePath { for (i in 0 until count) { moveTo/lineTo(RemoteOffset(x, y)) } }` then `drawPath`: one segment per point, written when the document is, each coordinate a live expression |
| `draw/text` | `text`, `xDp` `yDp` anchor, `textSizeSp`, `align` (start/center/end), `verticalAlign` (top/center/bottom) | `drawAnchoredText`, `align` as `panX` -1/0/1 and `verticalAlign` as `panY` 1/0/-1 (the player's vertical sign is the reverse) |
| `draw/group` | `translateXDp` `translateYDp` `rotate` `scale` `pivotXDp` `pivotYDp`; its own `ops` | `withTransform({ … }) { … }` |
| `draw/clip` | box, or `pathData` in a viewport; `exclude`; its own `ops` | `clipRect(l, t, r, b) { … }`, or `withTransform({ scale(in); clipPath(path); scale(out) }) { … }`; `ClipOp.Difference` when excluding |
| `draw/text-circle` | `text`, `centerXDp` `centerYDp` `radiusDp`, `angle` (270 is the top), `textSizeSp` + colour | `drawTextOnCircle`, centred on the angle, reading clockwise outside the circle |
| `draw/text-path` | `text`, `pathData` in dp, `startDp` `offsetDp`, `textSizeSp` + colour | `drawTextOnPath`, inside a one-dp scale so the path is in dp and the glyphs keep their size |
| `draw/morph` | `pathData`, `toPathData`, `progress` (0–1), viewport + paint | `drawTweenPath(from, to, tween = progress, paint)`, scaled to the canvas |
| `draw/if` | `condition` (a flag, usually state or a formula); its own `ops` | `drawConditionally(condition) { … }` |
| `draw/group` `scaleX` `scaleY` | across and down apart, each in place of `scale` on its axis | `scale(sx, sy, pivot)` inside the group's `withTransform` |
| `draw/repeat` | `from` `until` `step` (unitless), `index` (a name, `i` when absent); its own `ops` | `loop(from, until, step) { i -> … }` |

Paint is `color` (literal, theme role or computed), `style` (`fill`/`stroke`), `strokeWidthDp`,
`strokeCap`, `strokeJoin` (`miter`/`round`/`bevel`), `alpha` and `blendMode` (`srcOver`, `multiply`,
`screen`, `overlay`, `darken`, `lighten`, `plus`, `difference`, `xor`, `srcIn`, `srcAtop`, `dstOut`,
`clear`; written as `RemotePaint.strokeJoin` / `blendMode`), and optionally a `gradient`
(`horizontal`, `vertical`, `radial`, `sweep`) from `color` to `gradientColor` (transparent when
absent), or through every colour `gradientColors` lists (hex or theme roles, comma-separated,
evenly spaced, or placed by `gradientStops`, one 0–1 position per colour). A gradient is laid
across the whole canvas, not the shape, unless placed: `gradientStartDp`/`gradientEndDp` move a
horizontal or vertical one's ends, `gradientCenterXDp`/`gradientCenterYDp` a radial or sweep one's
centre, `gradientRadiusDp` a radial one's radius (in a path's viewport units on a path or morph),
and `gradientTileMode` (`clamp`, `repeated`, `mirror`, `decal`; not on a sweep, which has none)
says what it paints past its ends — the `RemoteBrush` arguments of the same names. Otherwise: the Remote writer applies `RemoteBrush.<kind>Gradient(…)` to the paint over
the canvas's size, as `RemoteModifier.background(brush)` applies one over its component, so a sweep
gradient on a ring turns round the canvas centre. `alpha` fades both ends. Rules every lane shares: geometry is dp from the canvas's top-left; an
absent box is the whole canvas; a stroked shape whose box is defaulted is inset by half its stroke
so a ring drawn with defaults stays inside; angles are clockwise from three o'clock.

- **Palette.** Remote Compose catalogs get a **Drawing** shelf. The canvas slot accepts only the
  `DrawOperation` trait, which only operations carry, and operations carry no `RemoteAuthorable`, so
  a button dropped into a canvas, or a rectangle dropped into a column, is refused at the drop.
- **Canvas.** `UiBuilderDrawCanvas` (renderer SDK) draws the operations with Compose's `Canvas`,
  after the render tree has evaluated their computed values; the editor's renderer and a catalog's
  own renderer share it.
- **Remote Kotlin.** `RemoteCanvas(modifier) { … }`, coordinates as `n.rdp.toPx()`, a computed one
  as `expression.asRemoteDp().toPx()`, a theme colour read into a local above the canvas because
  `RemoteMaterialTheme.colorScheme` is a composable read and the draw lambda is not.

A canvas can instead draw in a **viewport**: `viewportWidth` and `viewportHeight` (literal numbers
above 0) set the space its operations are authored in, and `viewportMode` how that space maps onto
the canvas: `fit` (the default) scales evenly and centres so the whole viewport shows, `fill`
scales evenly to cover the canvas, centred, and clips the overflow, `stretch` scales each way to
the canvas. Operation numbers that are dp elsewhere are then viewport units, stroke widths and
text sizes included, and "the whole canvas" a defaulted box spans is the viewport. It is how the
RemoteCompose-Examples corpus draws its charts and gauges — a chart authored at 500 × 400 shows
correctly at any size — and what the Python authoring library calls a `viewport`. The Remote
writer wraps the operations in one `withTransform({ translate(…); scale(s, s) })` over the canvas's
size (`clipRect` first for `fill`); the canvas stand-in applies the same transform from
`UiDrawing.Viewport.transform`. A viewport with one side, or a side that is state or a formula, is
refused: it is the document's coordinate space, fixed when it is written.

A path clip keeps its operations in canvas coordinates: it scales into its viewport, clips, and
scales back out, all inside the one `withTransform` block, so only the clip has the viewport's shape.
A morph's two paths must tween — the same commands in order with the same count of numbers — which
`UiDrawing.tweenPathData` checks for the export and uses to draw the in-between path on the canvas;
the player interpolates the same numbers pairwise.

The three containers decide whether, where and how often their operations draw. A `draw/repeat`
runs while its index is below `until`, as the player's `loop` does, and binds the index by name:
an operation inside reads it as a `binding` wrapper, `{"type": "binding", "value": "i"}`, either as
a whole property or as an operand of a formula, where the inspector shows it as `@i`. That is the
wrapper a for-each row field already uses, so the wire needed nothing new; what is new is the scope.
`UiDrawing.loopIndices` names the indices around an operation, and every reader that checks a
formula (the editor's commit, both catalog validators, the Remote writer) asks it, so `@i * 30`
types inside a repeat and is refused, naming `i`, outside one. The canvas enters each pass through
the render tree's occurrence path with the index bound, so its operations resolve exactly as a
repeated layout template does; it stops at 1,000 passes, a cap the player does not have.

A progress ring and a clock hand, as a document:

```json
"canvas": {"componentId": "draw/canvas", "modifiers": [{"type": "size", "widthDp": 96, "heightDp": 96}],
           "slots": {"ops": ["track", "sweep", "hand"]}},
"sweep":  {"componentId": "draw/arc", "properties": {
             "style": {"type": "enum", "value": "stroke"}, "strokeWidthDp": {"type": "float", "value": 8},
             "startAngle": {"type": "float", "value": -90},
             "sweepAngle": {"type": "expr", "op": "mul", "args": [
               {"type": "state", "variable": "progress"}, {"type": "int", "value": 360}]}}},
"hand":   {"componentId": "draw/group", "properties": {
             "rotate": {"type": "expr", "op": "mul", "args": [
               {"type": "expr", "op": "mod", "args": [
                 {"type": "system", "value": "time.secondOfHour"}, {"type": "int", "value": 60}]},
               {"type": "int", "value": 6}]}},
           "slots": {"ops": ["needle"]}}
```

### Not yet

- More than two gradient stops, gradient geometry other than the canvas's, and images as paint.
- The device preview in remote-m3-catalog plays the canvas once its renderer adds the case.
- The regular Compose lane: `Canvas { }` is the same shape, but no catalog that exports regular
  Compose offers the vocabulary yet.

## Importing authoring JSON

`RemoteJsonImporter` reads Remote Compose authoring JSON — the `remotecompose-json` codec's input,
the language the RemoteCompose-Examples corpus is written in — into a design. The layout tree
becomes `layout/box`/`row`/`column` and `remote-m3/remote-text` (a spacer is an empty sized box); a
canvas becomes a `draw/canvas` whose viewport is the document's units, so its pixel coordinates draw
where the codec drew them. Its commands become drawing operations: the paint state the codec
carries is copied onto each, a `rotate`/`translate`/`scale`/`clipRect` wraps the commands after it in
a group or clip, a flat `save` … `restore` is a group, and `loop` / `conditionalOperations` become
`draw/repeat` / `draw/if`.

`RemoteJsonFormulas` translates the codec's formulas. The codec writes each function as the
remote-core operator of that name with its arguments pushed in order, so each is translated by what
the operator does with them: `clamp(a, b, x)` is `min(max(a, x), b)`, `step(a, b)` is `a > b`,
`ifElse(a, b, c)` is `c > 0 ? b : a`, `smooth_step(x, high, low)` is `smoothstep(low, high, x)`,
`log` is base 10. System names map to system values, pixel ones (`touchX`, `windowWidth`) times
`device.density`, and `componentWidth`/`componentHeight` to `canvas.width`/`height`. Variables are
the document's and inline where they are read; a loop's index is the builder's `@index`.

What has no builder equivalent — 3D, particles, runtime paths, touch expressions, shaders, arrays —
is skipped and reported by its path, so a document imports as far as it can.
`RemoteJsonImporterTest` imports a corpus checkout given `-PremoteJsonCorpus=<dir>` and writes
`ui-builder-export/build/remote-json-import/report.md`, every gap with the documents it stops.

