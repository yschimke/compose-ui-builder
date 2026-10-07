# UI builder: richer Remote Compose documents

Plan and running status, 2026-10. The work that takes a ui-builder design from "static layout of
catalog components" to the document Remote Compose can actually play: computed values, drawing,
the whole modifier vocabulary, the remaining layouts and components. It builds on the
[completeness review](UI_BUILDER_REMOTE_COMPOSE_COMPLETENESS_REVIEW.md), whose ordering it keeps:
values first, because everything else — a clock, a gauge, an animated arc — is a value the player
computes.

## Authoring target

The builder authors against the **latest released alpha**: `remote-creation-compose` 1.0.0-alpha20
and `remote-material3` 1.0.0-alpha12 (checked against Google Maven, 2026-10-07). The embedded
`remote-m3-record-v1.json` matches those signatures. The published catalog's snapshot record is
ahead of them (`backgroundColor` on the page indicators, `border` on the cards); export never writes
a snapshot-only parameter, so every exported widget compiles against the alpha. The target moves
with each new alpha, by refreshing the record and its goldens together.

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
| M2 | `draw/canvas` and `draw/*` operation nodes: shapes, paths, text, transforms, paint, gradients, clips, conditionals, loops | Landed (path clips, morphs and text on a path follow) |
| M3 | Events and actions: long/double click, touch, scroll actions, expression writes, host actions | Long press and double tap landed (`combinedClickable`); the rest planned |
| M4 | Remaining `RemoteModifier`s: graphicsLayer, visibility, semantics, marquee, ripple, brushes and shapes as values | Landed as `remoteCall` over the generated vocabulary (33 calls); lambda-only calls (`graphicsLayer`, `drawWithContent`) planned |
| M5 | Remaining components: `RemoteTimeText`, page indicators, theme node, button and card overloads | Horizontal and vertical page indicators landed; the rest planned |
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
| `toInt` | Int |
| `eq` `ne` `lt` `le` `gt` `ge` | Boolean |
| `and` `or` `not` | Boolean |
| `select(condition, ifTrue, ifFalse)` | the branches' kind |
| `concat(…)` `toString(x)` | String; numbers print as the player prints them |

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

### Formula text

The inspector shows and accepts formulas rather than trees: `time.secondOfHour % 60 * 6`,
`concat(count + 1, " left")`, `select(on, "On", "Off")`, `!on || count > 3`. Identifiers are state
variables or system values, `@field` is a row field, functions are the operation names, infix
operators follow C precedence. `UiExpressions.parseFormula` and `format` are inverse; the document
only ever stores the tree, so MCP and hand-written fixtures use the same shape the inspector writes.

### What each lane does with one

- **Canvas.** `CanvasRenderTree` evaluates every computed property and modifier field before an
  adapter sees the node, at the preview state, the row or placement arguments in scope, and the
  design's `environment.fixedTime` (Wear's 10:10:30 when unset). Adapters draw a literal and never
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
  to a literal. Offered only once `UiExpressions.wireSupported` — see the contracts note below.

### Not yet

- A row field inside an expression is evaluated by the canvas but refused by the Remote writer,
  which needs a typed loop lowering for it.
- Animated values (`animateRemoteFloatAsState`) and named host inputs (`rememberNamedRemote*`).
- The regular Compose and JSON lanes refuse computed values with a located message.
- **The published contracts.** `UiValueV1` in `compose-preview-contracts` is closed and has no
  `expr`/`system` subtype, so a design holding one cannot be written as a `.uid` file or committed
  through a server until the contracts add `ExpressionValueV1` and `SystemValueV1`. The canvas and
  the exports read them today, and the inspector offers them the moment the linked contracts can
  decode one (`UiExpressions.wireSupported` asks the serializer). `sharedElement` and
  `collapsiblePriority` are in the same position for modifiers.

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
| `draw/text` | `text`, `xDp` `yDp` anchor, `textSizeSp`, `align` | `drawAnchoredText` |
| `draw/group` | `translateXDp` `translateYDp` `rotate` `scale` `pivotXDp` `pivotYDp`; its own `ops` | `withTransform({ … }) { … }` |
| `draw/clip` | box, `exclude`; its own `ops` | `clipRect(l, t, r, b) { … }`, `ClipOp.Difference` when excluding |
| `draw/if` | `condition` (a flag, usually state or a formula); its own `ops` | `drawConditionally(condition) { … }` |
| `draw/repeat` | `from` `until` `step` (unitless), `index` (a name, `i` when absent); its own `ops` | `loop(from, until, step) { i -> … }` |

Paint is `color` (literal, theme role or computed), `style` (`fill`/`stroke`), `strokeWidthDp`,
`strokeCap` and `alpha`, and optionally a `gradient` (`horizontal`, `vertical`, `radial`, `sweep`)
from `color` to `gradientColor` (transparent when absent). A gradient is laid across the whole
canvas, not the shape: the Remote writer applies `RemoteBrush.<kind>Gradient(…)` to the paint over
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

- `clipPath`: a path clip needs the path in canvas pixels, and `draw/path` scales its viewport
  with a transform that a clip would carry onto everything it encloses.
- More than two gradient stops, gradient geometry other than the canvas's, and images as paint.
- Path morphs and text on a path.
- The device preview in remote-m3-catalog plays the canvas once its renderer adds the case.
- The regular Compose lane: `Canvas { }` is the same shape, but no catalog that exports regular
  Compose offers the vocabulary yet.
