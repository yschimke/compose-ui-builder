# Compose UI Builder Wave 0: benchmark decomposition and v1 contract RFC

**Status:** implementation candidate

**Parent specification:** [UI_BUILDER_PRODUCT_SPEC.md](UI_BUILDER_PRODUCT_SPEC.md)

**Baseline benchmark:** Confetti compact Schedule screen, pinned to
[`joreilly/Confetti@997afd9645ab614d3bfccec15a886820c9e2dd08`](https://github.com/joreilly/Confetti/tree/997afd9645ab614d3bfccec15a886820c9e2dd08)

**Primary benchmark:** Jetcaster Discover supporting-pane state, specified in
[UI_BUILDER_JETCASTER_BENCHMARK.md](UI_BUILDER_JETCASTER_BENCHMARK.md)

## 1. Purpose

This record preserves the completed compact baseline research. The Jetcaster benchmark now owns
the primary release gap and acceptance criteria. This record answers four
questions against the code that exists now:

1. What can the compiled native M3 catalog actually author?
2. What exact Compose tree and interactions does the Confetti benchmark require?
3. Which capabilities are missing?
4. What is the smallest document/operation/state contract that can express that screen for both a
   Wasm editor and an MCP client?

It does not close Wave 0. Confetti now supplies the fast reducer/native-render regression; the
same-engine Jetcaster Wasm golden, composite SVG execution bridge, catalog-runtime retention proof,
and reducer prototype remain empirical gates.

## 2. Existing native catalog inventory

The builder currently consumes `:native-catalog-m3` directly from `wasm-ui`. The registry in
`CatalogComponents.kt` contains 16 ids:

| Role | Existing id | Authorable values | Slots |
| --- | --- | --- | --- |
| Leaf | `button-filled` | `label: String`, `enabled: Boolean` | none |
| Leaf | `checkbox-checked` | `checked: Boolean` | none |
| Leaf | `switch-on` | `checked: Boolean` | none |
| Leaf | `slider` | `value: Float` | none |
| Leaf | `shape-morph` | `progress: Float` | none |
| Container | `card-slots` | headline/supporting/accent defaults | `leadingIcon`, `headline`, `supporting`; one child each |
| Leaf | `progress-linear` | `progress: Float` | none |
| Leaf | `badge` | `count: Int` | none |
| Leaf | `textfield-filled` | `value: String`, `label: String` | none |
| Leaf | `button-filled-pressed` | `label: String`; harness-driven state | none |
| Leaf | `button-filled-focused` | `label: String`; harness-driven state | none |
| Leaf | `button-filled-icon-label` | `label: String`; fixed icon | none |
| Leaf | `text-maxlines-truncated` | `text: String`; fixed 128dp width | none |
| Leaf | `text-serif` | `text: String`; fixed family | none |
| Leaf | `text-monospace` | `text: String`; fixed family | none |
| Leaf | `text-branded` | `text: String`; fixed family | none |

Useful foundations already exist:

- the displayed catalog component and Wasm component are the same compiled composable body;
- Wasm supports typed string, int, float, boolean, and colour knob seeds;
- `PreviewSlot` reports name, layout scope, scrolling, sizing, padding, and measured bounds;
- the UI composer already proves recursive slot replacement and prevents direct cycles; and
- source discovery and Code Connect already provide a Kotlin symbol, import, parameter signature,
  required/default information, and composable-slot hints when inference succeeds.

The current set is intentionally a preview-pipeline test set, not Material 3 coverage. It has no
screen scaffold, app bar, generic layout container, lazy container, chip, tab, list item, or icon
button. The only authored nesting is one child in each of three fixed card slots. A real application
screen is therefore blocked on catalog capability and renderer coverage before it is blocked on
drag mechanics.

## 3. Pinned Confetti source tree

The benchmark is derived from these source files at the pinned commit:

- [`HomeScaffold.kt`](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/HomeScaffold.kt)
- [`SessionsUI.kt`](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/SessionsUI.kt)
- [`SessionListView.kt`](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/SessionListView.kt)
- [`SessionItemView.kt`](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/SessionItemView.kt)
- [`TrackFilterRow.kt`](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/TrackFilterRow.kt)
- [`SessionListTabRow.kt`](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/SessionListTabRow.kt)
- [`ConfettiHeader.kt`](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/component/ConfettiHeader.kt)
- [`MockData.kt`](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/preview/MockData.kt)

At compact width the developer-authored hierarchy is:

```text
HomeScaffold
└─ Scaffold
   ├─ topBar: CenterAlignedTopAppBar
   │  └─ title: Text("KotlinConf 2023")
   └─ content: Box(padding(innerPadding), fillMaxSize)
      └─ Column(fillMaxSize)
         └─ SessionListView.Success
            └─ Column
               ├─ AnimatedVisibility(topBarCollapsedFraction < 0.5)
               │  └─ LazyRow(spacedBy=8, padding horizontal=16 vertical=8)
               │     ├─ FilterChip(selected=true)  ─ label: Text("All")
               │     ├─ FilterChip ─ leading: 8dp colour dot; label: Text("droidCon")
               │     ├─ FilterChip ─ leading: 8dp colour dot; label: Text("swiftCon")
               │     ├─ FilterChip ─ leading: 8dp colour dot; label: Text("flutterCon")
               │     └─ FilterChip ─ leading: 8dp colour dot; label: Text("reactCon")
               ├─ PrimaryTabRow(selected=0, transparent, content-sized indicator, no divider)
               │  └─ Tab(selected=true) ─ text: Text("Thu 13 Apr", titleSmall, bold)
               └─ HorizontalPager(page=0)
                  └─ Box(clipToBounds)
                     └─ LazyColumn(initial item=1, bottom navigation padding)
                        ├─ sticky header: TimeHeader("14:00")
                        ├─ Talk ListItem("Confetti: building …")
                        ├─ sticky header: TimeHeader("14:50")
                        ├─ Lightning ListItem("Compose tips in 5 minutes")
                        ├─ sticky header: TimeHeader("15:00")
                        └─ Break Surface("Coffee Break")
```

The `initial item=1` is not a typo. Confetti chooses the session nearest `14:10` and adds the one
preceding sticky header, so the initial list index is `1`. The golden must capture the resulting
actual scroll position rather than assuming the list starts at its first pixel.

### Talk row

```text
ListItem(fillMaxWidth, clickable, 3dp leading track-colour draw)
├─ headline: Text(titleMedium, SemiBold)
├─ supporting: Column
│  ├─ Text(speakers, bodyMedium, onSurfaceVariant, top=2)
│  └─ FlowRow(horizontal=6, vertical=4, top=8)
│     ├─ room Surface(round=6, surfaceContainerHigh)
│     │  └─ Text(labelSmall, padding horizontal=6 vertical=2)
│     └─ first two tag Surfaces(round=6, surfaceContainer)
│        └─ Text(labelSmall, padding horizontal=6 vertical=2)
└─ trailing: Bookmark toggle
```

The first talk is bookmarked and has speakers `John O'Reilly, Martin Bonnin`, room
`Effectenbeurszaal`, tags `Kotlin` and `Multiplatform`, and a `3dp` leading accent derived from the
track mapping when present.

The lightning row uses the same tree plus a secondary-container badge containing a `12dp` Bolt
icon, `2dp` spacer, and the time range. The break row is a full-width low-container `Surface` with
`16 x 6dp` outer padding, `12dp` corners, a `36dp` circular high-container icon surface, a `20dp`
Coffee icon, a `16dp` spacer, and a weighted title/location column.

### Sticky time header

```text
Surface(fillMaxWidth, surfaceContainer)
└─ Row(padding horizontal=16 vertical=8, centerVertically)
   ├─ Icon(AccessTime, 18dp, primary)
   ├─ Spacer(8dp)
   └─ Text(time, titleSmall, Bold)
```

## 4. Capability gap matrix

`role` alone cannot validate a drop: a `Text` leaf is not an acceptable bookmark icon and an icon
is not a tab label unless the host explicitly accepts it. Required slots below therefore name exact
ids or traits as well as a broad role.

| Required capability | Kind | Essential slots/children | Essential props/state | Current status |
| --- | --- | --- | --- | --- |
| `layout/scaffold` | Scaffold | `topBar: TopBar?`, `content: ScreenContent!`, optional nav/FAB/snackbar | window insets | missing |
| `m3/center-aligned-top-app-bar` | Container | `title: TextContent!`, nav/actions | transparent colours, compact scroll behavior | missing |
| `layout/column` | Container | ordered `children:*` | fill, spacing, alignment | missing |
| `layout/row` | Container | ordered `children:*` | fill, spacing, alignment | missing |
| `layout/box` | Container | ordered/stacked children | fill, alignment, clip | missing |
| `layout/lazy-row` | Container | ordered `items:*` | spacing, content padding, scroll state | missing |
| `layout/lazy-column` | Container | ordered `items:*`; sticky-item trait | content padding, initial index/offset, scroll state | missing |
| `layout/horizontal-pager` | Container | ordered `pages:*` | current page | missing |
| `layout/flow-row` | Container | ordered `children:*` | horizontal/vertical spacing | missing |
| `layout/spacer` | Leaf | none | width/height | missing |
| `m3/filter-chip` | Container | `label: TextContent!`, `leadingIcon: IconContent?` | selected; click action | missing |
| `m3/primary-tab-row` | Container | `tabs: Tab*`, `indicator: Indicator?` | selected index, colours, divider visibility | missing |
| `m3/tab` | Container | `text: TextContent!`, optional icon | selected; click/select action | missing |
| `m3/list-item` | Container | headline, supporting?, leading?, trailing?, overline? | container colour, click event | missing |
| `m3/surface` | Container | `content:*` | colour token, shape, tonal/shadow elevation | only opaque `card-slots` exists |
| `m3/icon` | Leaf | none | icon key, size, tint, content description | only fixed inline icons exist |
| `m3/icon-toggle-button` or bookmark | Container/Leaf | optional selected/unselected icon slots | checked; toggle action | missing |
| `m3/text` | Leaf | none | text, typography token, weight, colour, maxLines, overflow, alignment | only fixed specimens exist |
| `m3/horizontal-divider` | Leaf | none | thickness, colour | missing |
| `shape/colour-dot` | Leaf | none | size, circle, colour | expressible only as hard-coded Box |
| `modifier/start-edge-accent` | Modifier | n/a | width, colour | missing |
| `behavior/visibility` | Wrapper/rule | one child | boolean predicate | missing |
| `behavior/sticky` | Item trait | one child | sticky=true | missing |

Minimum first slice should not implement the whole matrix. A useful vertical slice is:

1. Scaffold, top app bar, Column, LazyRow, FilterChip, Text, and colour dot;
2. shared document/state/operation reducer;
3. canvas overlay with compatible slot insertion; and
4. code generation for that exact header/filter subtree.

That slice proves all three component levels, ordered and cardinal slots, typed props, declarative
selection state, and clean editor chrome before pager/list complexity is added.

## 5. Catalog capability v1

This is the candidate wire shape to move to `compose-preview-contracts` after the reducer and native
adapter spike falsify it. Implementation stays in this repository.

```text
CatalogCapabilityManifest
  schema = "compose-ui-builder-capabilities/v1"
  systemId
  catalogRevision
  capabilityDigest
  nativeRuntime: runtimeId, protocolVersion, assetUrl, integrity
  components[]

ComponentCapability
  id
  displayName, group
  role: Screen | Scaffold | Container | Leaf
  traits[]
  rendererKey
  kotlin: symbol, imports[], callTemplate?
  properties[]
  slots[]
  events[]
  modifiers[]
  export: svgSupport, rasterFallbackReason?

SlotCapability
  name
  cardinality: min, max?       // max absent means unbounded
  ordered
  accepts:
    componentIds[]
    traitsAny[]
    roles[]
  layout: scope, horizontal sizing, vertical sizing, padding

PropertyCapability
  name
  type
  required, default
  constraints/choices
  codeMapping

EventCapability
  name
  payloadType?
  allowedActions[]
```

`rendererKey` is not inferred from a preview id. It is a versioned entry point in the native
runtime. Builder primitives such as `layout/column` may be authoring capabilities even when they do
not have an individual sticker preview, but they still belong to and version with the current
catalog manifest.

## 6. Design document v1

```text
DesignDocument
  schema = "compose-ui-builder-document/v1"
  id, title
  revision
  catalogPin
  environment
  stateVariables: id -> StateVariable
  roots[]
  nodes: id -> DesignNode

CatalogPin
  systemId
  catalogRevision
  capabilityDigest
  nativeRuntimeId

DesignNode
  id
  componentId
  properties: name -> TypedValue or StateRead
  modifiers[]                    // order is semantic
  slots: name -> ordered node ids
  visibleWhen?: Predicate
  eventBindings: event -> Action[]
  accessibility?

StateVariable
  type: Boolean | Int | Float | String | StringSet | Selection
  initialValue
  persistence: Design | Preview

Action
  Set(variable, value)
  Toggle(variable)
  AddToSet(variable, value)
  RemoveFromSet(variable, value)
  Select(variable, value)
  Emit(name, payload?)

Predicate
  Equals(read, literal)
  Contains(read, literal)
  Not(predicate)
  All(predicates[])
  Any(predicates[])
```

This is deliberately not a general expression language. It covers the benchmark:

- `selectedTrack: Selection<String?>` and chip click `Select`/toggle-to-null;
- `selectedDatePage: Int` and tab click `Set`;
- `bookmarks: StringSet` and bookmark click `AddToSet`/`RemoveFromSet`;
- selected visuals through `StateRead`; and
- an external talk click through `Emit("sessionSelected", sessionId)` which code export turns into a
  callback parameter/TODO.

Scroll offset, hover, pointer position, current drag, remote cursor, and selection in the editor are
ephemeral client state. The fixed benchmark's initial lazy index/offset is an authored container
property. A user scroll does not mutate the saved design.

`AnimatedVisibility(topBarCollapsedFraction < 0.5)` is out of the first vertical slice. The fixed
golden captures its initially visible child. Supporting the collapse later requires a constrained
runtime-only scroll-derived value; it must not introduce arbitrary Kotlin evaluation.

## 7. Commands, concurrency, and undo

```text
DesignCommand
  designId
  operationId             // idempotency key
  actorId, clientId
  baseRevision
  operations[]            // atomic batch

Operation
  InsertNode(node, parentSlot, afterNodeId?)
  MoveNode(nodeId, parentSlot, afterNodeId?)
  DeleteNode(nodeId)
  SetProperty(nodeId, property, value)
  SetModifier(nodeId, modifierIndex/key, value)
  SetInitialState(variableId, value)
  BindEvent(nodeId, event, actions)
  SetEnvironment(path, value)
  RenameDesign(title)

UndoCommand
  designId, operationId, actorId, clientId, baseRevision
  targetOperationId

RedoCommand
  designId, operationId, actorId, clientId, baseRevision
  targetUndoOperationId
```

Rules:

- The server is the only reducer. Wasm and MCP use the same command endpoint.
- Accepted batches receive one monotonic document revision and one deterministic document hash.
- Retrying an operation id returns its original outcome.
- Insert/move names a stable neighbour, never a stale array index. The server assigns the actual
  position key; its encoding is an implementation detail of the reducer spike.
- Invalid properties, modifiers, event actions, slot targets, cardinality, or cycles reject the
  whole batch with located diagnostics.
- Independent scalar writes are accepted in server order. A stale client receives the committed
  value and a conflict notice.
- Concurrent inserts retain both. Concurrent moves of the same node resolve in server order and
  notify the displaced client.
- Delete tombstones the subtree for the undo-retention window. Later operations against it fail
  rather than resurrecting it implicitly.
- Undo appends an actor-scoped compensating operation with preconditions against current state. It
  never rewinds another actor's later operation. Unsafe compensation is rejected visibly.
- Redo compensates an accepted undo under the same rules.

The reducer prototype must model-check these cases before the UI owns undo buttons.

## 8. Version-addressed native runtime decision

The current `wasm-ui` compiles the native catalog into the application. Persisting only
`catalogRevision` and `capabilityDigest` would therefore identify old pixels without retaining code
capable of drawing them.

The recommended spike is a version-addressed renderer runtime:

```text
/wasm/builder/                         current editor shell
/wasm/builder/runtime/<runtimeId>/     immutable native renderer assets
```

The design pins `nativeRuntimeId`; the capability manifest declares its asset URL, protocol
version, and integrity digest. The current Wasm editor loads the matching renderer in a sandboxed
surface and communicates through a narrow versioned render/measure/input protocol. Old runtime
assets are retained for the published support window.

This separates editor fixes from catalog pixel compatibility and gives SVG capture an explicit
Wasm boundary if recorded-scene export wins. The spike must prove that the overlay can map measured
bounds across that surface without changing layout or pointer coordinates. If it cannot, Wave 0
must choose either retained full-builder bundles or an explicit forced catalog migration; a hash
alone is not acceptable.

## 9. Export execution bridge gate

No current JVM module can invoke `:native-catalog-m3`; it declares only `wasmJs`, and its only
`actual` override implementation is in `wasmJsMain`. The existing per-preview `figma-svg` path does
not make an arbitrary builder document renderable.

The SVG spike must produce one of these executable paths, not only a format choice:

1. add a compatible non-Wasm catalog artifact and compile a generated wrapper into the existing
   render-session/`figma-svg` pipeline;
2. execute the version-pinned Wasm renderer headlessly and capture a versioned scene/layout record;
   or
3. prove another bounded bridge with the same revision/catalog/runtime provenance.

The test document must contain nested text, clipping, elevation, and at least one embedded raster.
It passes only when it exports without an open editor, imports into Figma at 1:1 bounds, retains
supported text/groups, declares every raster fallback, and rasterizes within the product threshold.

## 10. Remaining Wave 0 outputs

- [x] Build and freeze the separately compiled Jetcaster Wasm reference with source/data/asset
      provenance and committed reference pixels.
- [ ] Add Jetcaster bounds/baselines/semantics capture and enforce clean/editor invariance.
- [ ] Correct the Confetti baseline fixture to the pinned source data before retaining it as the
      compact regression.
- [ ] Implement the pure reducer prototype and model tests for concurrency and compensation.
- [x] Implement the Jetcaster capability fixture, strict static validator, and native dispatch for
      every referenced component id.
- [ ] Prove capability-driven codegen and SVG conformance for the Jetcaster fixture.
- [ ] Prove version-addressed renderer loading and overlay/input coordinate mapping.
- [ ] Complete the export execution bridge and Figma import test.
- [ ] Move accepted wire shapes and compatibility fixtures to `compose-preview-contracts`.
- [ ] Replace candidate examples in this document with links to executable tests before Gate 0.

## 11. Operation-replay visual test

The first executable pipeline slice lives behind the isolated `:ui-builder` module. Its JVM test
consumes `confetti-schedule-operations-v1.json` directly and proves that the Kotlin and JavaScript
reducers produce the same canonical document hash. The module's standalone Wasm fixture renders
that reduced document and a separately handwritten compact schedule.
`preview-harness/ui-builder.spec.mjs` performs a zero-tolerance same-browser PNG differential at
411×914 and retains that second rendering as a committed golden. The cross-platform golden
comparison separately permits at most 2% raster drift because Chromium/Skia text and icon edges
differ between macOS and Linux.

This is useful pipeline coverage, but it is not yet an upstream fidelity oracle: both renderings
live in the same module and reproduce nearby values by hand. It must not be described as proof that
the builder matches pinned Confetti. Jetcaster closes that hole with the independently built,
provenance-locked reference required by
[UI_BUILDER_JETCASTER_BENCHMARK.md](UI_BUILDER_JETCASTER_BENCHMARK.md).

The Jetcaster leg is a separate artifact and process boundary:
`ui-builder-reference-jetcaster` directly composes the pinned scene and does not depend on
`:ui-builder`; `:ui-builder` replays the 100 public operations and renders all 99 nodes. The
Playwright harness captures both at `1280 x 800`, verifies the upstream provenance, commits both
reviewable PNGs, and attaches a diagnostic diff. The first integrated render differs by `4.989%`
at pixelmatch threshold `0.1`; CI currently enforces an `8%` convergence ceiling while the release
gate remains exact parity.
The fixture contains a real Scaffold and
app bar, five track filters, two day tabs, a bounded lazy schedule, sticky-style time headers, talk
and lightning rows, dividers, bookmark/icon states, and a shaped break surface. It does not yet
claim pager interaction, bounds/baseline capture, or the SVG leg is complete.

The module deliberately has no dependency on `:server`, `:render-host`, or `:wasm-ui`. Server HTTP
and MCP adapters can depend on its reducer API while it is incubating here; extraction later moves
the whole module and visual fixture rather than separating state semantics from rendering.

The golden test starts from an empty design. It must not load the expected final document as its
input.

```text
public operation sequence (JSON fixture or MCP calls)
  → authoritative reducer
  → committed revision + canonical document hash
  → clean version-pinned Wasm render
  ├─ PNG → pixel diff against developer-authored Confetti Wasm PNG
  └─ SVG export → deterministic rasterizer → pixel diff against the same reference
```

Three layers fail independently:

1. **Reducer golden:** replay a checked-in sequence of public commands and compare the canonical
   document/hash with the expected semantic fixture. This runs without a browser and catches
   ordering, idempotency, state binding, slot, and undo errors.
2. **Wasm PNG golden:** open the resulting committed revision in clean mode at the pinned runtime,
   wait for fonts and the explicit ready signal, capture `411 x 914`, and compare it to the
   separately compiled Jetcaster Wasm golden. Editor chrome is enabled in a sibling capture to assert
   that all design-node bounds and clean pixels remain unchanged when it is toggled off.
3. **SVG golden:** request SVG for that same immutable revision, verify provenance/viewBox/no
   external URLs/no editor nodes, rasterize with the pinned tool, and compare to the clean Wasm PNG.
   A separate structure assertion requires identifiable text/groups and rejects a single
   full-screen image.

The identical operation fixture is also an MCP conformance test: submit its batches through MCP,
observe them through a browser WebSocket, and require the same final revision/hash/PNG/SVG as direct
HTTP replay. No MCP-only mutation or renderer path is allowed.

Golden provenance records:

- benchmark repo and commit;
- fixture source function and mock-data symbol;
- viewport, density, locale, font scale, theme, scroll/page state, and browser/runtime versions;
- fonts/assets with hashes;
- builder document, catalog, native runtime, exporter, SVG rasterizer, and diff versions; and
- the accepted tolerance plus an inspectable diff artifact on failure.

The checked-in candidate replay fixture now covers the compact Schedule's scaffold, filters, tabs,
and visible session content using 57 insert operations. A candidate reducer script is scaffolding
only; the authoritative test must move to the shared server reducer once its contract is accepted.

## 12. Extraction posture

Treat the builder as a future separate product while the seam is still cheap to shape.

Recommended ownership after extraction:

```text
compose-ui-builder
  UI/MCP clients, design reducer/service, persistence, presence, builder assets and visual harness

compose-preview-server
  catalog/capability delivery, versioned native runtime hosting, render/export job execution

compose-preview-contracts
  only the versioned shapes crossing those boundaries
```

This is a target seam, not an instruction to split now. In particular, moving the reducer without
its persistence or leaving it dependent on server internals would create two repositories while
retaining one implementation.

Build toward extraction by placing the new frontend under an isolated top-level `ui-builder/`
boundary, keeping all server integration behind public routes, and making the operation-replay
visual test runnable against a released server distribution/container. Do not put builder state in
`ServeSessionRegistry`, `ServeDocStore`, preview history, or catalog cache objects.

Split only after:

- released contracts cover catalog capabilities, design commands/events, runtime selection, and
  export jobs;
- the builder can build/test without this source tree and without project substitution;
- its storage lifecycle and migrations are independently operable;
- a version skew matrix is green for supported builder/server versions; and
- the same Jetcaster operation sequence produces the accepted visual products across the released
  boundary.

## 13. Recommended first implementation sequence

The first four steps now have executable coverage for the compact Schedule. Continue with the
contract move, or use this boundary to parallelize the independent runtime/export/integration work:

1. add local serializable candidate models and a pure reducer in tests or a non-wire prototype
   namespace;
2. add capability fixtures for Scaffold, top app bar, Column, LazyRow, FilterChip, Text, and colour
   dot;
3. replay the checked-in Schedule operations to create that document, then render it through a
   clean native tree and a sibling overlay;
4. generate recognizable Compose for that same document;
5. replace local candidate DTOs with released contract shapes after their schema survives the
   reducer/renderer tests; and
6. only then split catalog, server, Wasm, MCP, and export implementation among parallel workers.

This order avoids giving several workers a wire contract that has not yet survived one real screen.
