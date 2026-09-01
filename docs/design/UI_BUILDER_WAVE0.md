# Compose UI Builder Wave 0: benchmark decomposition and v1 contract RFC

**Status:** implementation in review; remaining release gates are listed in section 10

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

This began as a pre-implementation record. The reducer, durable single-process service, independent
Jetcaster Wasm oracle, generated-source execution bridge, exact runtime hosting, and sandboxed
renderer protocol now have executable coverage. They are no longer open spikes. Wave 0 remains open
for the corrected Confetti baseline, protected-golden decision, released contract coordinates, and
the SVG/Figma import fidelity gate described in section 10.

The implementation evidence is deliberately spread across stacked review branches rather than
described as merged or released. The principal delivered slices are
[runtime extraction and restart recovery (#113)](https://github.com/yschimke/compose-preview-server/pull/113),
[generated preview compilation (#126)](https://github.com/yschimke/compose-preview-server/pull/126),
[production operation replay (#128)](https://github.com/yschimke/compose-preview-server/pull/128),
[external artifact consumption (#129)](https://github.com/yschimke/compose-preview-server/pull/129),
and [the sandboxed renderer runtime (#130)](https://github.com/yschimke/compose-preview-server/pull/130).

## 2. Original native catalog inventory

At the start of Wave 0, the `?compose=1` prototype consumed `:native-catalog-m3` directly from
`wasm-ui`. Its registry in `CatalogComponents.kt` contained 16 ids:

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

That original set was intentionally a preview-pipeline test set, not Material 3 coverage. It had no
screen scaffold, app bar, generic layout container, lazy container, chip, tab, list item, or icon
button. The only authored nesting is one child in each of three fixed card slots. A real application
screen was therefore blocked on catalog capability and renderer coverage before it was blocked on
drag mechanics. The current Jetcaster capability fixture and native dispatch now cover every
component referenced by the frozen benchmark; the table below is retained as the baseline gap that
drove that work, not as a statement about current implementation coverage.

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

## 4. Original capability gap matrix

`role` alone cannot validate a drop: a `Text` leaf is not an acceptable bookmark icon and an icon
is not a tab label unless the host explicitly accepts it. Required slots below therefore name exact
ids or traits as well as a broad role.

| Required capability | Kind | Essential slots/children | Essential props/state | Wave 0 baseline status |
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

The authoritative reducer now model-checks these cases, including structural compensation,
same-anchor insertion permutations, move/delete races, unsafe undo rejection, retained replay, and
idempotent undo/redo. The executable specifications are
[`CollaborationConvergenceTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/CollaborationConvergenceTest.kt)
and
[`PersistentUiBuilderServiceTest`](../../ui-builder-runtime/src/test/kotlin/ee/schimke/composeai/uibuilder/service/PersistentUiBuilderServiceTest.kt).

## 8. Version-addressed native runtime decision

The current `wasm-ui` compiles the native catalog into the application. Persisting only
`catalogRevision` and `capabilityDigest` would therefore identify old pixels without retaining code
capable of drawing them.

The recommended spike is a version-addressed renderer runtime:

```text
/ui-builder/                         current builder preview shell
/ui-builder/runtime/<runtimeId>/     target immutable native renderer assets
```

The design pins `nativeRuntimeId`; the capability manifest declares its asset URL, protocol
version, and integrity digest. The target Wasm editor loads the matching renderer in a sandboxed
surface and communicates through a narrow versioned render/measure/input protocol. Old runtime
assets are retained for the published support window.

The server hosting foundation accepts explicit `runtimeId=directory` inputs, snapshots and verifies
each directory before binding, and serves only exact ids from the immutable route above. Every
input has a `runtime-manifest.json` with schema `compose-ui-builder-runtime/v1`, its exact runtime
id, positive protocol version, safe relative entrypoint, and a lowercase SHA-256 tree digest. The
digest covers the sorted non-manifest assets as `path`, NUL, decimal byte length, NUL, bytes. There
is deliberately no `latest` or `current` alias. The common Wasm client loader fetches only the
pinned manifest, checks protocol, identity and digest against its descriptor, and returns the exact
entrypoint URL.

The renderer split now has an executable vertical slice. `:ui-builder-renderer` produces a distinct
renderer-only CMP/Wasm directory and ZIP, including the verified v1 runtime manifest; the combined
`compose-preview-ui-builder-web` archive remains the editor shell and is not registered as a native
runtime. The editor can mount an exact `nativeRuntimeId` entrypoint in an opaque-origin iframe and
round-trip a real Jetcaster document into measured authored-node and slot bounds. A pointer-inert
sibling overlay maps those root-render coordinates without becoming part of the Compose tree or
changing its pixels. The protocol validates source, locks origin, checks exact runtime/protocol
identity, and correlates every response to one pending request.

Protocol v1 supports revision-bound pointer phases and pixel-mode wheel input in the same
root-render-pixel coordinate space returned by inspection. A correlated `inputDispatched` response
contains the post-input inspection, including resolved preview-state semantics. The executable
Jetcaster acceptance selects the News category chip and verifies Crime is deselected, then scrolls
the independently scrollable supporting detail list while the main category row and both pane
bounds remain invariant. Malformed, stale, duplicate, out-of-viewport, wrong-source/origin, and
unsupported inputs are rejected or ignored; keyboard and focus input remain explicit future work.
The browser harness runs the actual editor artifact and renderer artifact across the opaque iframe
boundary, asserts node/slot measurements and overlay separation, proves that attaching/removing the
pointer-inert sibling changes zero pixels and zero frame bounds, and proves floating runtime ids are
rejected.

This separates editor fixes from catalog pixel compatibility and gives SVG capture an explicit
Wasm boundary. The executable
[`ui-builder-renderer.spec.mjs`](../../preview-harness/ui-builder-renderer.spec.mjs) proves that the
overlay maps measured bounds across the sandbox surface, remains pointer-inert, and does not change
the rendered pixels. Runtime manifest validation is covered by
[`CatalogRuntimeProtocolTest`](../../ui-builder/src/commonTest/kotlin/ee/schimke/composeai/uibuilder/CatalogRuntimeProtocolTest.kt),
and exact immutable server hosting by
[`ServeUiBuilderRuntimeAssetsTest`](../../server/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderRuntimeAssetsTest.kt).
Deployments still need an explicit support-window and retirement policy for retained bundles; a
stored hash alone remains insufficient.

## 9. Export execution bridge gate

The generated-source path is selected. Compose Preview's Playground compiler already stages Kotlin,
runs the Compose compiler against a selected live catalog classpath, discovers `@Preview` entries,
and opens bundle-less render sessions. The existing renderer/daemon lane then applies runtime
overrides and produces PNG or `compose/figma-svg`. Override variants themselves reuse the authored
preview function with a seeded override specification; they do not generate Kotlin wrappers.

The executable proof covers both outputs needed by this product. The full Jetcaster document
generates standalone Compose that compiles and renders as CMP/Wasm, and the saved-revision JVM
recorder exports structured SVG without an editor browser. The server adapter now wraps the
generated composable in a tiny deterministic `@Preview` entry and submits it to the existing
Playground path; its real BTA/discovery test is
[`UiBuilderGeneratedPreviewAdapterTest`](https://github.com/yschimke/compose-preview-server/blob/agent/ui-builder-generated-preview-adapter/server/src/test/kotlin/ee/schimke/composeai/cli/serve/UiBuilderGeneratedPreviewAdapterTest.kt).
`:render-host` does not invoke the Wasm-only `:native-catalog-m3` implementation directly.

The test document must contain nested text, clipping, elevation, and at least one embedded raster.
The execution bridge portion passes because it exports without an open editor and declares its
raster fallbacks; see
[`StructuredSvgExportBridgeTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/StructuredSvgExportBridgeTest.kt)
and
[`ServeUiBuilderRenderPortTest`](../../server/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderRenderPortTest.kt).
The remaining Figma conformance portion passes only when the SVG imports at 1:1 bounds, retains
supported text/groups, and rasterizes within the product threshold.

## 10. Remaining Wave 0 outputs

- [x] Build and freeze the separately compiled Jetcaster Wasm reference with source/data/asset
      provenance and committed reference pixels.
- [x] Add Jetcaster bounds/baselines/authored-semantics capture and enforce clean/editor
      invariance. Merged Compose accessibility semantics and off-screen lazy content remain open.
- [ ] Correct the Confetti baseline fixture to the pinned source data before retaining it as the
      compact regression.
- [ ] Review and explicitly approve any replacement of the protected generated-Compose Jetcaster
      golden. Fidelity work in [#117](https://github.com/yschimke/compose-preview-server/pull/117)
      and [#124](https://github.com/yschimke/compose-preview-server/pull/124) leaves the protected
      reference unchanged; a lower mismatch is evidence, not authorization to rewrite it.
- [x] Implement the pure reducer and model tests for concurrency and compensation. Structural and
      scalar compensation, durable storage/compaction, fanout/reconnect, authenticated actors, and
      restart recovery are covered by
      [`CollaborationConvergenceTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/CollaborationConvergenceTest.kt),
      [`FileDesignStoreTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/FileDesignStoreTest.kt),
      and
      [`PersistentUiBuilderServiceTest`](../../ui-builder-runtime/src/test/kotlin/ee/schimke/composeai/uibuilder/service/PersistentUiBuilderServiceTest.kt).
      The opt-in
      [`PersistentCollaborationSoakTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/PersistentCollaborationSoakTest.kt)
      also completed its required 60-minute, three-client, restart-and-recovery run on
      [#113](https://github.com/yschimke/compose-preview-server/pull/113#issuecomment-5486199877).
- [x] Implement the Jetcaster capability fixture, strict static validator, and native dispatch for
      every referenced component id.
- [x] Prove capability-driven code generation for the Jetcaster fixture. The latest fidelity branch
      emits the full 108-node document with located TODO diagnostics and revision/catalog/environment
      provenance. Generated source compiles and renders as a standalone CMP/Wasm fixture; against
      the independent oracle it differs by `0.199%` expanded and `0.132%` compact. The executable
      checks are
      [`CapabilityComposeCodeExporterTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/CapabilityComposeCodeExporterTest.kt)
      and
      [`ui-builder-jetcaster.spec.mjs`](../../preview-harness/ui-builder-jetcaster.spec.mjs).
- [ ] Close SVG/Figma conformance for the Jetcaster fixture. The saved-revision JVM bridge exports
      structured SVG with vector catalog icons and declared embedded-raster fallbacks. The last
      authorized Figma import preserved exact root bounds and editable layers but differed from the
      clean Wasm render by `5.597%`; it therefore does not pass the release threshold.
- [x] Prove version-addressed renderer loading and overlay/input coordinate mapping. Exact immutable
      hosting, strict manifest/protocol loading, a distinct renderer-only CMP/Wasm artifact, opaque
      iframe loading, reversible inspection/input coordinates, real pointer state changes,
      independent wheel scrolling, and zero-pixel/zero-layout sibling-overlay invariance are
      covered by
      [`ServeUiBuilderRuntimeAssetsTest`](../../server/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderRuntimeAssetsTest.kt),
      [`CatalogRuntimeProtocolTest`](../../ui-builder/src/commonTest/kotlin/ee/schimke/composeai/uibuilder/CatalogRuntimeProtocolTest.kt),
      and
      [`ui-builder-renderer.spec.mjs`](../../preview-harness/ui-builder-renderer.spec.mjs).
      Runtime routes have no floating alias, so retained exact bundles remain addressable by id.
- [ ] Complete the Figma import test for the proven export execution bridge. The first real import
      is recorded in `jetcaster-discover-figma-import-v1.json`: structure passes, raster parity
      fails. The latest local export adds deterministic node/token/family/style/weight provenance,
      but its private re-import remains pending explicit artifact-upload authorization; the evidence
      file keeps that source separate from the last completed import.
- [ ] Release all accepted wire shapes and compatibility fixtures from `compose-preview-contracts`.
      The additive environment, accepted-outcome timestamp, and catalog-upgrade shapes are under
      review in
      [contracts #30](https://github.com/yschimke/compose-preview-contracts/pull/30),
      [#31](https://github.com/yschimke/compose-preview-contracts/pull/31), and
      [#32](https://github.com/yschimke/compose-preview-contracts/pull/32); this repository must not
      consume them through `mavenLocal()`, a composite build, or project substitution.
- [x] Link the reducer, persistence, runtime, export, and replay claims in this record to executable
      tests. Contract acceptance and release remain the separate item above.

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
`:ui-builder`. The production protocol replay branch uses the checked-in 100-operation/99-node
fixture. The latest fidelity branch adds detail-pane content and renders 108 nodes produced by 109
public operations. The Playwright harness captures the builder, capability-generated Compose, and
independent reference at expanded `1280 x 800` and compact `412 x 800`, verifies upstream
provenance, and attaches diagnostic diffs. On [#124](https://github.com/yschimke/compose-preview-server/pull/124),
builder/reference mismatch is `0.212%` expanded and `0.193%` compact; generated/reference mismatch
is `0.199%` expanded and `0.132%` compact. CI enforces a `1%` same-browser convergence ceiling while
the release gate remains exact parity. Protected reference goldens were not changed. Committed
review PNGs separately allow at most `4%` macOS/Linux Chromium/Skia drift.
The Confetti fixture contains a real Scaffold and
app bar, five track filters, two day tabs, a bounded lazy schedule, sticky-style time headers, talk
and lightning rows, dividers, bookmark/icon states, and a shaped break surface. It now publishes a
revision-keyed inspection manifest for composed node and slot bounds, native text baselines, and
authored semantics, and proves the editor overlay leaves clean geometry and pixels unchanged. It
does not yet claim pager interaction, off-screen lazy-node inspection, merged accessibility
semantics, or that the SVG/Figma leg is complete. A JVM saved-document bridge now records the
native Compose scene to Skia SVG and passes a structured-text subset round trip; full Jetcaster is
rejected until its filtered icons are vectorized or mapped to explicit node-correlated fallbacks.

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
   wait for fonts and the explicit ready signal, capture `1280 x 800`, and compare it to the
   separately compiled Jetcaster Wasm golden. Editor chrome is enabled in a sibling capture to
   assert that all design-node bounds and clean pixels remain unchanged when it is toggled off.
3. **SVG golden:** request SVG for that same immutable revision, verify provenance/viewBox/no
   external URLs/no editor nodes, rasterize with the pinned tool, and compare to the clean Wasm PNG.
   A separate structure assertion requires identifiable text/groups and rejects a single
   full-screen image.

The production harness now contains two complementary executable cases on
[#128](https://github.com/yschimke/compose-preview-server/pull/128):

- the checked-in 100-step Jetcaster fixture is submitted through the public HTTP protocol as create
  plus one atomic 99-insert batch, observed by independently authorized browser sessions, and
  exported as revision-pinned production PNG/SVG before comparison with the independent oracle; and
- the actual `compose-preview-mcp` executable edits the same authenticated service observed by two
  browsers, then the installed server restarts and reproduces byte-identical PNG/SVG/Compose
  exports.

Those cases share the Design API and renderer path; there is no MCP-only reducer or export lane.
The executable specification is
[`ui-builder-gate2.spec.mjs`](https://github.com/yschimke/compose-preview-server/blob/agent/ui-builder-mcp-visual-replay/preview-harness/ui-builder-gate2.spec.mjs).

Golden provenance records:

- benchmark repo and commit;
- fixture source function and mock-data symbol;
- viewport, density, locale, font scale, theme, scroll/page state, and browser/runtime versions;
- fonts/assets with hashes;
- builder document, catalog, native runtime, exporter, SVG rasterizer, and diff versions; and
- the accepted tolerance plus an inspectable diff artifact on failure.

The older 57-insert Confetti candidate remains a fast local regression, but it is no longer the
authoritative collaboration proof. The Jetcaster production replay above starts from an empty
design and goes through the shared server service/reducer. Its JVM/Skia production PNG differs from
the independent Compose/Wasm oracle by `3.180%`, within the existing `4%` cross-runtime bound. This
does not replace the stricter same-browser fidelity gate or the still-open protected-golden and
Figma-import decisions.

## 12. Extraction posture

Treat the builder as a future separate product while the seam is still cheap to shape.

Recommended ownership after extraction:

```text
compose-ui-builder
  UI client, design reducer/service, persistence, presence, builder assets and visual harness

compose-preview-server
  catalog/capability delivery, versioned native runtime hosting, render/export job execution

compose-preview-contracts
  only the versioned shapes crossing those boundaries

compose-ai-tools:mcp
  thin authenticated Design API client; no reducer, store, compiler, or renderer implementation
```

This is a target seam, not an instruction to split now. In particular, moving the reducer without
its persistence or leaving it dependent on server internals would create two repositories while
retaining one implementation.

The build now publishes the transport-free runtime, editor archive, and renderer-only archive as
separate artifacts. The
[`check-ui-builder-external-consumer.sh`](https://github.com/yschimke/compose-preview-server/blob/agent/ui-builder-external-consumer-gate/scripts/check-ui-builder-external-consumer.sh)
gate on [#129](https://github.com/yschimke/compose-preview-server/pull/129) publishes to an isolated
temporary repository, copies a consumer outside the checkout, and resolves/executes those artifacts
without project dependencies, source-path leakage, `mavenLocal()`, a composite include, or project
substitution. This proves the artifact seam; it does not yet prove the full released-version skew
matrix or justify a repository move by itself.

Split only after:

- released contracts cover catalog capabilities, design commands/events, runtime selection, and
  export jobs;
- the complete builder can build/test without this source tree and without project substitution
  (the runtime/web external-consumer slice already passes);
- its storage lifecycle and migrations are independently operable;
- a version skew matrix is green for supported builder/server versions; and
- the same Jetcaster operation sequence produces the accepted visual products across the released
  boundary.

## 13. Remaining implementation sequence

The reducer, catalog fixture, Jetcaster operation replay, native renderer/overlay, generated Compose,
offline export bridge, runtime hosting, and artifact seams now have executable coverage. Work can and
does proceed in parallel across those stable boundaries. The remaining dependency order is:

1. merge and release the accepted contract additions, then consume their released coordinates;
2. complete the protected-golden review and close the Figma import/raster fidelity gap without
   weakening its threshold;
3. consume the authoritative delta timestamp and finish the one-frame canvas performance gate;
4. implement catalog-upgrade preview/apply/rollback against the released contract; and
5. run the released-version skew and full external operation-replay matrix before any repository
   extraction.

None of those items requires moving MCP into `:server`: the existing MCP executable remains a thin
client of the same authenticated Design API used by the browser.
