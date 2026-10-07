# UI builder: richer Remote Compose documents

Plan and running status, 2026-10. The work that takes a ui-builder design from "static layout of
catalog components" to the document Remote Compose can actually play: computed values, drawing,
the whole modifier vocabulary, the remaining layouts and components. It builds on the
[completeness review](UI_BUILDER_REMOTE_COMPOSE_COMPLETENESS_REVIEW.md), whose ordering it keeps:
values first, because everything else — a clock, a gauge, an animated arc — is a value the player
computes.

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
| M2 | `draw/canvas` and `draw/*` operation nodes: shapes, paths, text, transforms, clips, loops, paint | Planned |
| M3 | Events and actions: long/double click, touch, scroll actions, expression writes, host actions | Planned |
| M4 | Remaining `RemoteModifier`s: graphicsLayer, visibility, semantics, marquee, ripple, brushes and shapes as values | Planned |
| M5 | Remaining components: `RemoteTimeText`, page indicators, theme node, button and card overloads | Planned |
| M6 | Device preview: every published component, and expressions played live | Planned (remote-m3-catalog) |

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
