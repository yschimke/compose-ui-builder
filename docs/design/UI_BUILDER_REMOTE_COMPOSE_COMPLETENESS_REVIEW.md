# UI builder: expressive interfaces, with Remote Compose as an output

Review and disposable feasibility proof, 2026-09-10. **Proposal, not an adopted architecture or a
claim of 80% implemented coverage.** Production implementation is deliberately deferred until the
proof is reviewed. The product should remain an interface builder: layout, data, behavior and
reusable components. Remote Compose is an execution/export target, alongside regular Compose.

The accompanying [operation inventory](evidence/remote-compose-completeness/operations.csv) gives
every operation a proposed authoring surface, regular Compose mapping, fidelity qualification and
stage. The [local proof](../../experiments/remote-compose-poc/README.md) exercises the critical path.

## Evidence and scope

The operation census uses AndroidX revision
[`36dfbf59a0566d34121e31a6c495c094105124d7`](https://github.com/androidx/androidx/blob/36dfbf59a0566d34121e31a6c495c094105124d7/compose/remote/remote-core/src/main/java/androidx/compose/remote/core/Operations.java),
not the May checkout on this machine. That registry declares **172 constants and 162 distinct
operations with registered readers** across its supported versions/profiles. Ten constants have no
active reader: `LOAD_BITMAP`, `EXTENDED_OPCODE`, the four `EXTENSION_RANGE_RESERVED_*` constants,
`DRAW_TEXT_ON_CIRCLE`, `MATRIX_SET`, `PARTICLE_PROCESS`, and `UPDATE`. They remain in the inventory
but are excluded from the active-reader denominator. A name in the creation parser does not prove
the corresponding wire constant is readable; helpers can lower to other operations.

This is a pinned source census. Profile membership and released API availability are separate
dimensions. AndroidX released alpha19 on September 9; this repository's reviewed source emitter
describes alpha18, and its player pin is 1.60.1. The proof explicitly uses alpha19 creation-core and
player 1.60.1, and makes no claim that all main-branch APIs are in alpha19.
[AndroidX release notes](https://developer.android.com/jetpack/androidx/releases/compose-remote).

The JSON investigation used the pinned [schema](https://github.com/androidx/androidx/blob/36dfbf59a0566d34121e31a6c495c094105124d7/compose/remote/Documentation/parts/remote_compose_schema.json),
[parser implementation](https://github.com/androidx/androidx/blob/36dfbf59a0566d34121e31a6c495c094105124d7/compose/remote/remote-creation-core/src/main/java/androidx/compose/remote/creation/json/RemoteComposeJsonParser.java),
component/modifier/resource registries in the same directory, and the updated
[skills reference](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/remote-compose.md).
The parser implementation exposes constructs its prose overview does not enumerate, including
`stateLayout` and typed integer resources. Validate exact syntax against the pinned implementation;
do not infer support solely from a documentation list.

## What exists here, and what is missing

The repository reviewed was `46b1b8f6b6f7ed5aa35e40174745d33ba2effc8c`.

| Area | Existing foundation | Gap to close |
| --- | --- | --- |
| Preview | Real `RcComposePlayer` on CMP/Wasm; embedded `.rc` documents; inline capture lane | Generate fresh documents directly from editable semantic nodes, including dynamic behavior |
| Layout | Shared Box/Row/Column IDs; Remote Kotlin emitter; catalog records | State switch, adaptive layouts and consistently bindable modifier arguments |
| State and actions | `stateVariables`, `eventBindings`; Compose state handling; Remote state writes | Typed reads/computed values across properties/modifiers; named host inputs; ordered action sequences and host callbacks |
| Repetition | `layout/for-each`, row bindings and instance paths; editor-side Compose export | Record-driven export parity; lazy `items` keys; Remote layout repetition distinct from canvas loops |
| Reusable components | In-document symbols and instances; Compose functions; Remote body expansion | Explicit state/callback/slot parameters; scoped mutable state and identity; native pattern conformance |
| Remote catalog components | Record-driven fallback for discovered callable APIs, plus hand-written adapters | Arbitrary catalog components need a JSON lowering implementation or a precompiled recipe, not just a Kotlin signature |
| JSON | Published `remotecompose-json` dependency; `.rc.json` inspection route | Revision-pinned authoring-JSON/`.rc` export and live assembly from designs |
| Identity | Canvas instance paths | Inspection, actions, semantics and native geometry must address the same repeated instance |

These findings are grounded in `RemoteContentEmitter.kt`, `UiBuilderDocument.kt`,
`ScreenExportGate.kt`, and the existing Remote Compose, repetition, value-semantics and project
boundary design documents. In particular, the Remote emitter currently rejects multiple actions
on one event; that is an emitter restriction, not a fundamental Remote Compose limitation.

## Operation-to-product map

The CSV is exhaustive at wire-operation granularity. This table groups that inventory into features
an author can understand. A direct semantic mapping may emit several wire operations; requiring
one palette item per opcode would expose implementation details and inflate coverage numbers.

| Feature in the builder | Remote Compose representation | Regular Compose UI | Qualifications |
| --- | --- | --- | --- |
| Box, Row, Column | Box/Row/Column layout operations; `RemoteBox`, `RemoteRow`, `RemoteColumn` | `Box`, `Row`, `Column` | Direct; retain arrangement, parent scope and layout direction |
| Wrap / fit / overflow | Flow, FitBox, CollapsibleRow/Column; priority modifier | `FlowRow`/`FlowColumn`; constraint-aware/custom layout | Collapsing by priority is not lazy scrolling; fitting policy must be explicit |
| State switch | `LAYOUT_STATE`; `RemoteStateLayout`; JSON `stateLayout` | `when` inside a modifier-bearing wrapper | Selection first; animation and retention policy can follow |
| Show when | Visibility operation or conditional subtree | `if`, or invisible-but-measured layout | Hidden, gone and transparent are different behaviors |
| Size and spacing | Dimension modes, width/height constraints, padding, weight | `size`, `sizeIn`, `requiredSizeIn`, `padding`, scoped `weight` | Preserve min/max constraints, dp/px units and modifier order |
| Position and transforms | Offset, z-index, graphics layer, align-by | `offset`, `zIndex`, `graphicsLayer`, `alignBy` | Scope validation; arbitrary matrix operations need conversion |
| Fill, border, clip | Background, border and clip modifiers; paint brushes | `background`, `border`, `clip`, `Brush` | Preserve chain order; some wire coordinates always remain pixels |
| Scroll and touch indication | Scroll, marquee, ripple | Scroll modifiers, `basicMarquee`, indication/ripple | Physics and timing need not be identical in the first slice |
| Text and typography | CoreText/TextLayout, TextStyle, transforms | `Text`/`BasicText`, `TextStyle`, `AnnotatedString` | Shaping, autosizing, overflow and font metrics require real-player evidence |
| Images and fonts | Bitmap/font resources and ImageLayout | `Image`, `ImageBitmap`, `FontFamily` | Assets need bytes/provenance, not just names; bitmap fonts require helpers |
| Values and bindings | Typed constants, named variables, color theme | Hoisted parameters/state; remembered local state; `MaterialTheme` | Declare type, source, mutability, initial/preview value and lifetime |
| Computed values | Float/integer/color expressions; text formatting/lookup/merge | Typed Kotlin expressions, formatting, collections, color interpolation | Do not replace player-time evaluation with generation-time constants |
| Lists and records | ID lists/maps, float arrays and dynamic lists | Typed lists/maps with state-aware updates | Stable keys and argument scope are part of correctness |
| Measurements | ComponentValue, text measurement and attribute operations | Layout constraints/measurement, `TextMeasurer`, image metadata | Avoid measurement cycles and distinguish host data from local state |
| Click / press / cancel | ClickArea, click/multiclick/touch modifiers | `clickable`, `combinedClickable`, `pointerInput` | Preserve hit regions, accessibility and event order |
| Set / toggle / increment | Value-change and expression-change actions | State assignment and callbacks | Ordered execution; do not promise transactional semantics without evidence |
| Call app action | Host/named/metadata actions | Typed callback interface | Host integration must be declared; the preview host logs callbacks |
| Repeat drawing | LoopOperation with expression-backed index | `repeat`/`for` inside `Canvas` | A drawing loop is not a repeated layout item |
| Conditional behavior | ConditionalOperations | `if` inside drawing/action logic | Separate this from layout branch selection |
| Reusable component | Pattern define/inflate/arguments/blocks, or generation-time expansion | `@Composable` functions, calls and content lambdas | Preserve semantic definitions even when the wire target expands them |
| Reusable formula | FloatFunctionDefine/Call | Pure Kotlin numeric function | This is not a composable call |
| Shared style | ReferencedOperations / IncludeReferencedOperations | Modifier factory or reusable style value | Preserve order and scope; do not clone silently in the design |
| Canvas shapes | Rect, round rect, circle, oval, line, arc, sector | `Canvas` / `DrawScope` primitives | Direct geometry with explicit units |
| Paths | Path data/create/append/combine/expression/tween | `Path`, `Path.combine`, interpolation/evaluation helpers | Validate compatible morph paths; no generic path morph API assumption |
| Canvas text and images | Text runs/anchors/path text, bitmap draw/crop/font glyphs | `drawText`, `drawImage`; path-text/bitmap-font helpers | Some helpers require platform-specific implementation |
| Drawing scopes | Paint, matrix save/restore/transforms, clipping, draw-content | `withTransform`, clips, `drawWithContent`, brushes/strokes | Save/restore and paint state are semantic, not decorative |
| Motion | Float expressions with animation, AnimationSpec, TouchExpression, impulses | `Animatable`, `animate*AsState`, transitions, drag and spring helpers | Initial proof covers feasibility, not timing/physics parity |
| Advanced effects | Shaders, particles, matrix math, offscreen bitmap | ShaderBrush/runtime effects; Canvas algorithms; layers | Capability-gated; not universally portable |
| Device effects | Haptic, wake scheduling, sound data/expression/playback | Haptic API and explicit host audio/scheduling services | There is no raw Compose UI equivalent for the host service itself |
| Custom content | `LAYOUT_CUSTOM` with registered implementation and declared bounds | Composable slot/component registry | Standalone `.rc` cannot carry arbitrary application composable bytecode |
| Document machinery | Header, root/content boundaries, theme, semantics, skip, comments/debug | Host settings, composition structure, semantics, diagnostic hooks | Compiler/host responsibilities; keep off the component palette |

Expressions and paint contain additional sub-operations. The first shared expression vocabulary
should cover typed arithmetic, comparison, boolean/select, clamp/min/max, interpolation, formatting,
list indexing, string concatenation and lengths. Trigonometry, splines, matrix/vector math and
random/time functions belong under advanced computed values. Paint needs fill/stroke, alpha,
cap/join, dash/path effects, blend modes, gradients and shader uniforms. Counting one
`PAINT_VALUES` opcode as all those features being complete would be misleading.

## Material components are a separate coverage axis

The pinned public `remote-material3` API lists 26 `Remote*` composable names, including the theme.
These lower to primitives; they are not 26 extra wire opcodes. Component-record discovery proves
how to call Kotlin, but cannot recover the implementation needed for direct JSON generation.

| Remote Material family | Ordinary Compose mapping |
| --- | --- |
| Button, CompactButton, TextButton, IconButton, EdgeButton | Appropriate Material/Wear buttons; mobile EdgeButton/CompactButton may need catalog adapters |
| ButtonGroup | Library ButtonGroup where available, otherwise a declared arrangement adapter |
| Card, AppCard, TitleCard, OutlinedCard | Corresponding Wear cards; mobile Card/OutlinedCard with explicit title/application slots |
| CheckboxButton, RadioButton, SwitchButton | Wear equivalents; mobile selectable row plus Checkbox/RadioButton/Switch |
| SplitCheckboxButton, SplitRadioButton, SplitSwitchButton | Two separately actionable regions; no silent conversion to one click handler |
| CircularProgressIndicator, LinearProgressIndicator, CurvedProgressIndicator | Circular/linear Material progress; curved progress needs Wear/Canvas adapter |
| HorizontalPageIndicator, VerticalPageIndicator | Appropriate pager indicators or a small declared adapter |
| Icon, Text, TimeText, MaterialTheme | Icon/Text/theme; TimeText requires host clock/formatting and Wear layout policy |

For direct JSON, each catalog needs versioned lowerings or precompiled parameterized recipes with
evidence against its real components. A catalog with only Kotlin records can still use the compile
lane. Never substitute a hand-built M3-looking approximation and label it the catalog component.
This is particularly important for AI: plausible-looking output is not proof of library fidelity.

## One semantic design, several concrete outputs

Keep the editable document authoritative: stable node IDs, ordered modifiers, typed values, state
declarations, expressions, event sequences, component symbols and placements. Browser controls and
MCP mutations both edit that document through the same validation and revision rules.

The proposed output flow is:

1. Validate semantic design, catalog pin, selected target and required host capabilities.
2. Resolve bindings and component/loop scopes without evaluating runtime expressions prematurely.
3. Lower to AndroidX **authoring JSON**, then compile with the official creation-core parser and
   play those `.rc` bytes; alternatively lower to Remote Kotlin or regular Compose Kotlin.
4. Preserve a source map from design node/property/instance to JSON location, generated Kotlin
   span and emitted operation IDs. Use it for diagnostics, picking and interaction replay.
5. Export the requested artifact plus assets, host interface and dependency/profile metadata.

Save the design separately from its exports. `design.json`, `interface.remote.json`, `interface.rc`
and Kotlin source serve different purposes. An inspection `.rc.json` dump is a one-way projection,
not editable source and not an inverse compiler. Importing arbitrary `.rc` should initially embed
it as an opaque component; do not promise recovery of names, components or original control flow.
The exact distinction is documented in
[REMOTE_COMPOSE_JSON.md](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/REMOTE_COMPOSE_JSON.md).

JSON assembly can run on the server's JVM without an Android daemon or composable compiler. The
browser still needs that assembly endpoint unless a separately proven browser compiler exists.
Live playback remains the real CMP/Wasm player. Compilation success must be followed by player
support validation and rendering; the default creation platform's measurement stubs cannot prove
text geometry. Cache by design revision, catalog/lowering version, assets and target profile;
discard late results for stale revisions.

## StateLayout: the first complete slice

An author inserts **State switch**, binds **Value** to `mode`, creates named cases and styles the
container through the normal modifier inspector. The tree shows the case labels, and preview
controls let the author select a case without changing the saved initial value. No one enters an
integer resource ID.

The lowering assigns cases a deterministic ordinal and links the StateLayout to a typed integer
state variable. Boolean and enum values map through that ordinal table. Arbitrary values such as
10 and 20 must not be passed directly as child indices. Handle unknown values explicitly through a
default case or a located validation/runtime diagnostic.

Regular Compose exports a modifier-bearing `Box` around `when (mode)`. Remote Kotlin emits
`RemoteStateLayout` against the pinned overload; JSON uses `stateLayout` and an integer binding.
The POC proves two-state selection and state updates. Shared transitions, preservation of inactive
branch state and animation polish are intentionally later work. A plain `when` is acceptable for
this initial behavior contract.

State and parameter scopes need to be explicit before generalization: document state, local
component state, immutable parameter, loop item, derived value, host input and system value.
A reusable component should receive state values and event callbacks as parameters rather than
silently closing over document globals. Stable instance/item identity must address which copy
owns a local variable and which copy an action targets.

## A human interface that also serves AI

Use familiar shelves: **Layout**, **Components**, **Content**, and **Behavior**. Values belong in
an inspector, not a permanent programming panel. A property starts as a normal literal control;
its binding button opens **Value / Data / Formula**. Behaviors read “On click → Set mode → Call
lighting.changed”. Reusable components look like named components with editable parameters.

Expose **State switch**, **Repeat**, **Show when** and **Component** as progressive features.
Advanced drawing and device effects appear only when relevant. Show export/backend details in the
code/export panel and a compact target-support indicator. Search can accept “StateLayout” and
“RemoteStateLayout” as aliases without requiring those labels in the primary UI.

AI gets the same capabilities in structured form: typed schemas and bounded examples, valid
parent/slot scopes, supported property expressions, exact target versions, and stable operation
identities. Atomic batches, dry-run validation, revision preconditions and idempotency make it
possible to repair a small failed edit instead of regenerating a screen. An error should identify
the node, field, expected type, unsupported target and a valid alternative when one exists.

For an export to be described as ready for production, require a target-specific evidence bundle:
compiling source or validated binary; complete assets; host input/action interface with implemented
adapters; tested interaction scenarios; accessibility/semantics checks; renderer/profile support;
and reproducible dependency pins. A callback stub or unsupported effect cannot silently count as
a shipping feature. The POC's logged host action is deliberately a mock host, not a lights service.

## Measuring 80% without gaming the number

Track three separate measures:

- **Operation coverage:** supported operations / all registered operations in a declared pinned
  target/profile. The union reviewed here is 162, so 80% of that union would require at least 130.
  Report excluded profiles and reserved constants explicitly.
- **Semantic coverage:** whether authoring, typed binding, modifiers, state/actions, preview,
  website/MCP mutation and each requested export work for that feature. Constants-only support is
  not dynamic support. Track expression/paint sub-operations separately.
- **Catalog coverage:** exact component/variant/property recipes proven against library renders.
  Playing a published component sticker does not prove it is editable or JSON-generatable.

Maintain a capability record with `author`, `preview`, `json`, `rc`, `remoteKotlin`, `composeKotlin`,
supported operand/value kinds, minimum API/profile/player version, fidelity and evidence fixture.
These are proposed fields, not a new committed protocol. Use an explicit unsupported result for
target-specific features. The 80% claim should follow measured coverage, not determine a convenient
denominator after implementation.

## Local experimentation and implementation decision

The user's instruction explicitly allows local project dependencies for prototyping, overriding
the normal released-coordinate-only rule for this work. Do not require a human to merge/release
contracts or tools to run a prototype. The isolated Gradle build supports an opt-in local player
composite. Its initial proof needed no contracts change; if one becomes necessary, use an explicit
local contracts path/substitution and prove the consumer against it before asking for a release.
Keep overrides opt-in and record the exact paths/revisions used; do not make unrelated builds
silently consume whatever happens to be in a local Maven repository.

After reviewing the proof, production work can be split by demonstrated behavior: first typed state
and State switch end-to-end; then ordered actions/host contracts and reusable components; then
layout/data repetition and expressions; then broader drawing/catalog recipes. Each slice closes
website + MCP + validation + real preview + exports together. This is an implementation sequence
to consider after the feasibility decision, not work started by this review.

Wire contracts still belong in compose-preview-contracts; offline compiler/scoring behavior belongs
in the offline tool layer; this repository owns editor/runtime orchestration and HTTP/MCP surfaces.
The existing four published UI-builder seams remain useful. A prototype dependency override does
not by itself justify changing the architectural direction of those dependencies.
