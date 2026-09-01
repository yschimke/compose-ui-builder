# Compose UI Builder product specification

**Status:** implementation in review; release gate remains open

**Product surface:** Compose Multiplatform/Wasm builder plus an MCP adapter

**Initial catalog:** `m3-catalog` / the native `compose-m3` catalog

**Primary benchmark:** Jetcaster Discover with a selected supporting pane at
`018c5207fb63c4f78e5841bd8ddd4faabdf19d3a`

**Baseline regression:** Confetti compact Schedule at `997afd9645ab614d3bfccec15a886820c9e2dd08`

## 1. Summary

Build a collaborative visual editor for Compose Multiplatform projects that can produce a Wasm
application. A person or an agent assembles real, compiled catalog components into a semantic
Compose tree, sees that tree rendered by Compose/Wasm, and exports the same saved design as
Figma-compatible SVG or readable Compose code.

This is a distinct product from the catalog and preview surfaces at `preview.coo.ee`. It reuses their
catalog inventory, metadata, fonts, source information, render products, authentication patterns,
and rendering infrastructure, but it does not turn a preview session into an editable document.

The original `?compose=1` prototype proved that compiled catalog composables could be dragged and
nested through `PreviewSlot`, but its browser-local fixed list and layout-affecting handles were not
the product model. The distinct `/ui-builder/` application now renders saved semantic documents,
and the renderer-only CMP/Wasm artifact reports measurements across a sandboxed iframe to a sibling
overlay that does not change Compose pixels. The old `/wasm/<system>/` catalog app remains a separate
feature.

### Implementation snapshot (2026-09-01)

These are implemented review branches, not a claim that every stack is merged or released:

| Slice | Executable evidence | Status |
| --- | --- | --- |
| Reducer, compensation, persistence, restart recovery, and 60-minute three-client soak | [`CollaborationConvergenceTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/CollaborationConvergenceTest.kt), [`PersistentUiBuilderServiceTest`](../../ui-builder-runtime/src/test/kotlin/ee/schimke/composeai/uibuilder/service/PersistentUiBuilderServiceTest.kt), and [#113 soak evidence](https://github.com/yschimke/compose-preview-server/pull/113#issuecomment-5486199877) | implemented |
| Authenticated browser/MCP convergence, ACL isolation, reconnect, restart, and deterministic exports | [#116 Gate 2 harness](https://github.com/yschimke/compose-preview-server/pull/116) and [#128 production operation replay](https://github.com/yschimke/compose-preview-server/pull/128) | implemented; stacks still under review |
| Generated Compose and existing Playground/BTA preview adapter | [`CapabilityComposeCodeExporterTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/CapabilityComposeCodeExporterTest.kt) and [#126](https://github.com/yschimke/compose-preview-server/pull/126) | implemented |
| Published runtime/editor artifacts and extraction seam | [#123](https://github.com/yschimke/compose-preview-server/pull/123) and [#129 external-consumer gate](https://github.com/yschimke/compose-preview-server/pull/129) | implemented for runtime/web artifacts; full released-version matrix open |
| Exact runtime hosting and sandboxed renderer/measurement protocol | [`ServeUiBuilderRuntimeAssetsTest`](../../server/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderRuntimeAssetsTest.kt) and [`ui-builder-renderer.spec.mjs`](../../preview-harness/ui-builder-renderer.spec.mjs) | implemented; v1 intentionally rejects input |
| Jetcaster builder/generated fidelity | [#124](https://github.com/yschimke/compose-preview-server/pull/124) | under 0.22% in expanded and compact modes; exact protected golden remains open |
| Performance | [#118](https://github.com/yschimke/compose-preview-server/pull/118) | propagation/reopen pass; canvas p95 `39.5ms` misses `16.67ms` |
| Contracts, Figma import, and release fidelity | [contracts #30–32](https://github.com/yschimke/compose-preview-contracts/pulls) and the Wave 0 remaining-output list | open; no unreleased-coordinate workaround and no unauthorized Figma upload |

## 2. Product promise

> Build a real Compose screen visually, with the same components and layout semantics a developer
> would use, while a human and an agent can work on the same saved design at the same time.

A successful first release lets a user:

1. create or reopen a persistent design pinned to one catalog revision;
2. find a scaffold, container, or leaf composable in `m3-catalog`;
3. drag it into a compatible root or named slot without adding editor pixels to the composition;
4. edit typed component properties and a constrained set of Compose layout modifiers;
5. preview and interact with the clean screen in the Compose/Wasm runtime;
6. watch edits made through MCP arrive in that same open browser session, and vice versa;
7. export the exact saved revision to Figma-compatible SVG; and
8. export a deterministic, readable Compose source approximation.

## 3. Goals and non-goals

### Goals

- Produce native Compose/Wasm pixels by invoking compiled catalog composables, not by approximating
  them with HTML, DOM controls, screenshots, or hand-drawn facsimiles.
- Model a screen the way Compose code models it: components, named slots, ordered children, typed
  parameters, and layout/modifier semantics.
- Support component roles from whole-screen scaffolds through containers to individual composables.
- Use the current catalog data plane. Start with one compile-time native catalog and design the
  document so a later catalog reference is not a breaking change.
- Make browser and MCP edits equal clients of one authoritative, revisioned design service.
- Persist designs across browser closure and server restart, with multiple simultaneous users.
- Make every render and export reproducible from a design revision and a pinned catalog revision.
- Retain or address the executable native catalog bundle needed to render every supported pinned
  revision; metadata identity alone is not reproducibility.
- Establish fidelity with a real application screen, not a builder-only demo.

### Non-goals for the first release

- Android-only projects, Wear catalogs that do not publish a Wasm target, or arbitrary JVM
  composables loaded dynamically in a browser.
- Loading an arbitrary catalog Wasm binary into a running builder. The first app compiles in one
  native `m3-catalog` adapter; multiple catalogs require a separately designed bundle/plugin ABI.
- A freeform vector drawing tool, absolute-positioned canvas, or Figma clone.
- Persisting measured screen coordinates as the source of truth. Bounds are transient inspection
  output; the document stores Compose constraints and relationships.
- Arbitrary Kotlin expressions, lambdas, generics, navigation logic, data fetching, animation
  timelines, or business logic in the visual property editor.
- Offline-first editing. Concurrent online clients are required; a full offline CRDT is not.
- Executing arbitrary generated Kotlin submitted by a user or agent on the public server.
- Perfect reconstruction of any screenshot. The builder assembles supported catalog components and
  reports unsupported capabilities honestly.
- Synthetic pressed/focused states in export unless the rendering harness can reproduce the real
  interaction state. The current native catalog already falls back instead of falsely rendering
  those variants.

## 4. Real-screen benchmarks

### Primary release benchmark: Jetcaster Discover

The release benchmark is Jetcaster's Discover home with a selected podcast visible in its
supporting pane at expanded width. Jetcaster is an advanced, official Jetpack Compose sample. Its
real hierarchy includes an outer adaptive `SupportingPaneScaffold`, an inner Material 3 `Scaffold`,
search chrome, an adaptive grid, filter chips, image-backed carousel cards, episode content, a
floating toolbar, and an independently scrolling detail pane.

The exact pinned source, fixed `1280 x 800dp` scene, semantic hierarchy, deterministic asset rules,
catalog gap, and operation-replay acceptance test are specified in
[UI_BUILDER_JETCASTER_BENCHMARK.md](UI_BUILDER_JETCASTER_BENCHMARK.md).

This target is intentionally difficult. It proves that the product represents scaffold slots,
adaptive constraints, repeated content, overlays, clipping, images, and multiple scroll regions;
matching it with a benchmark-only component or flattened bitmap does not pass.

### Baseline regression: Confetti Schedule

The compact/mobile Schedule screen from
[Confetti](https://github.com/joreilly/Confetti/tree/997afd9645ab614d3bfccec15a886820c9e2dd08),
an Apache-2.0 Compose Multiplatform conference application whose shared module declares a Wasm
browser target and uses Material 3, remains the fast baseline regression.

Pin all benchmark inputs to commit `997afd9645ab614d3bfccec15a886820c9e2dd08`:

- [Android schedule screenshot](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/fastlane/metadata/android/en-US/images/phoneScreenshots/b_scheduleScreen.png)
- [screen scaffold](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/HomeScaffold.kt)
- [schedule integration](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/SessionsUI.kt)
- [compact list](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/SessionListView.kt)
- [session row variants](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/SessionItemView.kt)
- [track filters](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/TrackFilterRow.kt)
- [date tabs](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/ui/sessions/SessionListTabRow.kt)
- [deterministic mock data](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/preview/MockData.kt)
- [preview dimensions](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/shared/src/commonMain/kotlin/dev/johnoreilly/confetti/preview/Previews.kt)
- [license](https://github.com/joreilly/Confetti/blob/997afd9645ab614d3bfccec15a886820c9e2dd08/LICENSE)

### Fixed benchmark scene

- Viewport: `411 x 914dp`; density/DPR `1`; browser zoom `100%`.
- Theme: light, non-dynamic; locale `en-US`; font scale `1.0`.
- Time: `2023-04-13T14:10`; initial date page and scroll position `0`.
- Data: Confetti's pinned `sessionsSuccessState`; no live API request.
- Top bar title: `KotlinConf 2023`.
- Filter chips: All, droidCon, swiftCon, flutterCon, and reactCon, including track colours.
- Selected date: `Thu 13 Apr`, with the real tab indicator.
- Visible content includes the 14:00 Confetti talk, the 14:50 lightning talk, and the 15:00 Coffee
  Break row, with their real row variants, metadata, tags, bookmark state, and time grouping.
- Remote speaker photographs are omitted. The compact source screen does not need them, avoiding a
  third-party asset-rights dependency in the golden fixture.

The Android screenshot is a product reference, not the pixel oracle. Wave 0 must build and retain a
developer-authored Confetti Wasm fixture at the pinned commit under the exact environment above,
including its clean raster and a semantics/bounds/baseline manifest. The builder is compared to that
Wasm golden. This avoids claiming sub-dp equivalence between different Android and browser font and
rendering stacks.

This target is deliberately broader than a list of buttons. It requires a `Scaffold`, centered app
bar, filter row, tab row, pager/list, sticky time headers, list items, surfaces, dividers, icons,
text, badges, selected state, weight, padding, clipping, and scrolling. It therefore exercises
scaffolds, containers, and leaves and exposes whether the builder encodes real Compose layout.

The benchmark fixtures must retain their Apache-2.0 attribution and must not imply endorsement.

## 5. User experience

### Product surface

The builder gets its own route and product identity at `/ui-builder/`. It is not a
`compose=1` mode inside the preview browser. During migration, the old URL may redirect to a new
design seeded with the prototype's examples.

The desktop workspace has four conceptual areas:

- **Catalog:** searchable components with actual clean thumbnails, role, supported slots, and
  compatibility. A component card may be dragged from anywhere; it does not need a permanent grip.
- **Canvas:** the real Compose/Wasm screen at a declared viewport. Editor affordances are a sibling
  overlay positioned from measured node and slot bounds.
- **Layers:** the semantic tree, including named slots and ordered children. This is the dependable
  place for precise selection, nesting, and reordering.
- **Inspector:** typed component properties, supported modifiers, theme/device controls, validation,
  and source/export diagnostics.

Responsive/compact layouts may turn these areas into drawers or tabs, but the canvas remains a
clean composition plus an overlay.

### Core interactions

- Create, name, duplicate, archive, reopen, and share a design.
- Add a root scaffold or container to an empty viewport.
- Drag from the catalog or layers tree into compatible named slots or ordered child gaps.
- Select by canvas or tree; multi-selection and freeform group transforms are deferred.
- Move, duplicate, delete, undo, and redo semantic operations.
- Edit declared catalog properties and supported modifiers with immediate validation.
- Toggle edit/clean preview without mounting a different design tree.
- Interact with the clean preview: click targets, filters, tabs, and scrolling use real composables.
- See other editors' presence and committed selections without persisting cursor or drag state.
- Open export history and reproduce an earlier output from its recorded revision.

### Editor chrome rule

Selection outlines, hover bounds, insertion lines, slot labels, drag ghosts, cursors, and handles:

- are drawn outside the rendered design subtree;
- do not contribute padding, constraints, hit targets, semantics, or scroll extent;
- are absent in clean preview, screenshots, SVG, and generated code; and
- may be disabled independently while the underlying composition remains mounted.

No release gate may be passed by switching to a separate approximation that happens not to show the
handles. The edit canvas and clean preview must resolve the same document through the same renderer.

## 6. Component authoring contract

The existing preview catalog is a discovery and render catalog, not yet a general authoring API.
Preview ids and screenshots cannot reveal arbitrary composable parameters, callback types, slot
cardinality, sizing behaviour, or the Kotlin symbol required for code generation.

Each builder-compatible component therefore needs an additive capability record generated or
hand-authored with the catalog. At minimum it declares:

```text
ComponentCapability
  catalogComponentId
  catalogRevision
  displayName and group
  role: Screen | Scaffold | Container | Leaf
  nativeRendererKey
  Kotlin symbol and imports
  supported platforms (must include Wasm for v1)
  properties: name, type, default, required, choices, code mapping
  slots: name, cardinality, accepted component ids/traits/roles, ordering, sizing/padding contract
  modifier capabilities, typed value constraints, and code mappings
  state variables and supported event/action bindings
  SVG capability and known raster fallbacks
```

`PreviewSlot` remains a valuable runtime signal: the builder can observe the same named region the
component renders, and `/render/<id>.slots` already normalizes semantics-discovered slots. It is not
enough by itself. The published capability supplies the static constraints needed before a drop,
for validation, for MCP discovery, and for source generation.

The initial native adapter must expand beyond the prototype's small pipeline-test set. Confetti
determines the baseline inventory, expected to include:

- `Scaffold` and `CenterAlignedTopAppBar` templates with named slots;
- `Column`, `Row`, `Box`, lazy list/row, pager, and flow/ordered containers;
- `FilterChip`, tab row/tab/indicator, `ListItem`, `Surface`, divider, icon, text, spacer, and badge;
- schedule-specific semantic templates only where they remain compositions of reusable catalog
  primitives and export as recognizable Compose, not opaque painted widgets.

Jetcaster then adds the release-gate inventory: adaptive supporting panes, search/input chrome,
adaptive grids and full-span items, carousels, deterministic image assets, gradient/overlay
composition, floating toolbars, and independent scroll regions.

Unknown or non-Wasm catalog entries fail visibly as unsupported. They must never be silently
substituted with an inaccurate native component.

Role is useful for browsing but too coarse for validation: two leaf components are not necessarily
interchangeable. A slot may accept exact component ids, declared traits such as `TextContent` or
`NavigationItem`, and/or roles. The same capability contract defines a constrained interactive
model: typed state variables, events exposed by a component, and declarative actions such as set,
toggle, select, increment, or navigate a fixed page. This is sufficient for benchmark filters,
tabs, paging, and bookmark state without admitting arbitrary Kotlin lambdas or business logic.

## 7. Canonical design document

The source of truth is a serializable semantic document owned by the server, not Wasm snapshot
state, generated Kotlin, SVG, or a bitmap.

```text
DesignDocument
  schemaVersion
  designId, title, createdAt, updatedAt
  revision
  catalog: systemId, immutable revision/generation, capability digest
  viewport: widthDp, heightDp, density
  environment: theme, locale, fontScale, layoutDirection, background
  state: typed variables and initial values
  roots: ordered node ids
  nodes: stable node id -> DesignNode
  assets and token bindings

DesignNode
  id
  componentRef
  role
  properties: typed values
  modifiers: ordered supported modifier values
  slots: slot name -> ordered child ids
  state reads and declarative event/action bindings
  optional accessibility metadata
```

Important invariants:

- Node ids are stable UUIDs and survive moves and property edits.
- A node has exactly one parent location or is a root; cycles are invalid.
- Slot existence, cardinality, allowed roles, required properties, and value types validate against
  the pinned component capability digest.
- Modifier order is preserved because it changes Compose output.
- State/action bindings validate against component capabilities, are deterministic and serializable,
  and declare whether a variable is saved design state or resettable preview state.
- Ordered children use stable neighbour/position keys so concurrent inserts do not depend on array
  indexes observed before another edit.
- Measured rectangles, selection, scroll position, hover, cursor, and drag state are ephemeral.
- A catalog refresh never silently changes an existing design. Upgrading is an explicit migration
  with a previewable diff.
- Unknown additive fields survive read/write when feasible; incompatible schema versions fail with
  a migration diagnostic rather than being discarded.

Wire DTOs and compatibility fixtures belong in `compose-preview-contracts`. The reducer,
validation, persistence, rendering, and export behaviour belong in this repository. This preserves
the established repository boundary: shapes cross repositories; implementation does not.

## 8. One collaboration service, two clients

The Wasm UI and MCP adapter use one authoritative `DesignService`. Neither client owns merge rules,
and MCP must not automate the browser DOM.

### Commands and events

Every mutation is a typed command or atomic batch carrying:

- design id;
- stable operation id for idempotent retries;
- actor id and client id;
- last observed document revision; and
- operations such as insert, move, delete, restore, set property, set modifier, replace slot, or
  update environment.

The server validates and serializes a command, assigns a monotonic revision, persists the accepted
operation and resulting state transactionally, and broadcasts the committed event. Invalid paths,
cycles, slot cardinality, or property values return located diagnostics without partially applying
the batch.

For online v1, use a server-ordered operation log, stable node ids, stable position keys, and
explicit deterministic resolution instead of a full offline CRDT:

- independent property writes can use per-field last accepted write;
- concurrent insertions retain both nodes in server order/position-key order;
- moves of the same node resolve in server order and emit a conflict notice to the displaced client;
- deletion creates a tombstone sufficient for undo and stale-operation diagnostics;
- an operation against a deleted or incompatible target is rejected and returns the current node
  state/revision; and
- replaying an operation id returns its original result and never duplicates a node.

Undo and redo are server semantics, not a browser-local snapshot stack. An undo command names an
actor-owned committed operation and asks the reducer to append a compensating operation with
preconditions against the current tree. It never rewinds another actor's later work. If a later edit
makes the inverse unsafe, the server rejects it with a located conflict and leaves history intact.
Redo compensates an accepted undo under the same rules. Tombstones, prior values, position anchors,
and idempotency records are retained for the declared undo window and survive compaction.

Presence, selections, cursors, and drag ghosts use an ephemeral channel and never enter design
history.

### Transport

- HTTP creates/lists/reads designs, submits command batches, reads history, and requests immutable
  renders/exports.
- A design WebSocket sends an initial snapshot or a delta from the client's last accepted-event
  sequence, committed operations, validation/conflict notices, presence, and export status. Every
  committed operation carries both its transport sequence and resulting document revision; these
  values are deliberately independent.
- Reconnect from a retained sequence must converge without a full-page reload. If the operation log
  has compacted past that sequence, the server sends a new snapshot. Rejections, idempotent retries,
  presence, and failed durable writes never advance the sequence.
- Existing `/ws/{previewId}` frame streaming remains a render-session protocol. It must not be
  overloaded with editable-document messages.

Call the catalog renderer lifecycle a **catalog session** and the saved product a **design**. Avoid
using `session` for both concepts in APIs and UI.

## 9. Persistence and access

The transport-free `ui-builder-runtime` now owns the `DesignStore`/service boundary for atomic
mutation plus revision, snapshot/replay, history, tombstones, export provenance, and bounded
compaction. Its recovery, compensation, access, and durable-sequence behavior is executable in
[`PersistentUiBuilderServiceTest`](../../ui-builder-runtime/src/test/kotlin/ee/schimke/composeai/uibuilder/service/PersistentUiBuilderServiceTest.kt)
and
[`FileDesignStoreTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/FileDesignStoreTest.kt).

The current implementation uses a file-backed, single-server-process store with an advisory lock,
checksummed state, atomic replacement, retained backup recovery, bounded compaction, and startup
replay. That scope remains explicit. The interface does not bake in process memory, so a
transactional database adapter can later support multiple server replicas.

Required durability properties:

- an acknowledged edit survives process restart;
- a corrupt or partial tail is detected and does not destroy the last valid snapshot;
- document and catalog revisions needed by an export are recorded together;
- compaction preserves undo/history promises and idempotency records for their declared window;
- storage, document size, operation rate, asset size, and connected-client counts are bounded; and
- backup/restore and schema migration are tested before public multi-user use.

Persistence also includes the renderable catalog runtime contract. Exact version-addressed runtime
hosting and the renderer-only CMP/Wasm artifact now implement the selected Wave 0 policy. For every
catalog revision the product promises to reopen, the deployment must either retain a
version-addressed builder Wasm
bundle/native adapter, prove a compatibility guarantee that the current adapter renders the pinned
capability digest identically, or require an explicit migration before rendering. Wave 0 selected
exact retained bundles plus explicit migration for unavailable/retired pins. The remaining
operational decision is how long deployments retain a runtime and how
retirement forces an explicit migration. A stored catalog hash without executable code that
understands it is not a valid pin.

The implementation reuses the current front-door and GitHub identity rules for human access. Agent
grants have a separate design-write capability rather than treating editing as a higher point on the
`preview -> live -> playground` compute ladder. Read, write, export, and any server-side compile or
render costs must be authorized independently. A grant may not approve another grant.

Designs are private to their owner/collaborators by default. Sharing requires an explicit ACL or
unguessable read-only link. Every mutation and export records the actor identity without recording
bearer credentials. Route-level isolation is covered by
[`ServeUiBuilderRoutesTest`](../../server/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderRoutesTest.kt),
and the installed-browser plus real-MCP flow by
[#116](https://github.com/yschimke/compose-preview-server/pull/116).

## 10. Rendering model

The Wasm renderer is a pure projection of
`(DesignDocument revision, catalog capability revision, native runtime bundle)`:

1. resolve every component reference through the compile-time native `m3-catalog` registry;
2. map typed properties and supported modifier values;
3. recursively provide named slot content and ordered container children;
4. apply the pinned theme, fonts, locale, density, and deterministic fixture state; and
5. report measured node/slot bounds to the editor overlay and inspection clients.

Editor overlays consume those measurements but are not ancestors of the design nodes. The renderer
also exposes a clean mode used by screenshots and exports. Unsupported nodes produce a located error
surface outside the clean export and block an export that would otherwise lie about the result.

The same document renderer must be usable by the generated-code fixture. The fidelity question is
not only whether the result resembles Confetti; it is whether visually authored and generated
Compose trees resolve identically.

## 11. Exports

Every export first snapshots an immutable design revision. Its response and provenance include the
design revision, catalog revision/capability digest, viewport, theme/environment, exporter version,
and declared fallbacks. Concurrent edits can create a newer design revision but cannot change an
export already in progress.

### Compose source

Generate a deterministic Kotlin syntax tree/template projection, not code scraped from pixels.

- Emit a readable `@Composable` screen with stable names and imports.
- Preserve recognizable Material 3 and catalog calls, named slots, modifier order, and editable
  literal parameters.
- Emit minimal model/state placeholders and explicit TODOs for app callbacks or models the document
  cannot represent.
- Never encode the whole screen as an image, SVG, Canvas path dump, or opaque generated widget.
- Format the result and return located diagnostics for unsupported properties or components.
- The stated product tolerance is “almost compiling”; the Confetti golden is held to the stronger
  requirement that it compiles in a pinned CMP/Wasm fixture project.

Existing catalog source/usage and Code Connect metadata supply symbols, imports, required
parameters, and slot mappings. The capability-driven whole-screen generator now emits the full
Jetcaster fixture deterministically, fails closed on unknown catalog/modifier values, and reports
located diagnostics; see
[`CapabilityComposeCodeExporterTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/CapabilityComposeCodeExporterTest.kt).

### Figma-compatible SVG

The repository already serves layered, self-contained `compose/figma-svg` for daemon-rendered
catalog previews and inlines hybrid raster crops that Figma cannot fetch. Dynamically assembled,
revision-pinned designs now reach that export lane through generated Compose and the existing
Playground/BTA/preview machinery.

Wave 0 compared two approaches for the execution bridge that exports a saved revision when no
editor browser is open:

1. render a generated design wrapper through the existing Compose `figma-svg` pipeline; or
2. record the Wasm scene/layout and assemble vector catalog fragments with declared raster
   fallbacks.

The generated-source path is selected. Compose Preview's Playground compiler already stages Kotlin,
runs the Compose compiler against a selected live catalog classpath, discovers `@Preview` entries,
and opens bundle-less render sessions. The existing renderer/daemon lane then applies runtime
overrides and produces PNG or `compose/figma-svg`. Override variants themselves do not generate
Kotlin wrappers: they reuse the authored preview function with a seeded override specification.

The builder reuses the Playground execution path. It deterministically generates readable Compose
for a pinned design/catalog revision plus a tiny `@Preview` entry, then submits that controlled
source to the existing compiler and render lane. The full Jetcaster source already compiles and
renders as CMP/Wasm, and saved revisions already export structured SVG without an editor browser;
[#126](https://github.com/yschimke/compose-preview-server/pull/126) implements the deterministic
preview-entry/Playground adapter and exercises real BTA compilation, preview discovery, and
first-frame handoff. No second compiler or renderer architecture is needed.

The choice of execution bridge does not waive the Figma gate. Import a nested screen with text,
clipping, elevation, and images into Figma and compare its rasterization to the clean Wasm render.
A single full-screen PNG wrapped in SVG is not success. Supported text and component groups must
remain identifiable/editable, external URLs must be removed, fonts/assets must be embedded or
resolved deterministically, and any raster fallback must be named in export metadata.

The native Wasm catalog is therefore executable to the exporter through compatible generated
source and a version-addressed preview artifact, not by having `:render-host` call the Wasm-only
`:native-catalog-m3` implementation. The catalog capability digest, design revision, generated
source hash, bundle identity, renderer identity, and output hash are export provenance.

Placement follows that decision. Pure JVM render/conversion code that opens no sockets belongs in
`:render-host`; HTTP routes, authorization, jobs, and persistence remain in `:server`. If Wasm scene
capture wins, capture starts in `wasm-ui`, any portable recording crossing the server boundary is a
versioned wire shape, and only pure conversion belongs in `:render-host`. Keep the enforced
direction `:server -> :render-host`, never the reverse.

## 12. MCP surface

The MCP integration is a thin adapter over the same versioned Design HTTP/WebSocket command API.
It does not need direct access to the store or renderer and must not pull an MCP server onto
`:render-host`'s classpath.

Initial resources:

- component catalog and capability schema;
- design snapshot at a revision;
- design history/diff;
- render image plus structured node/slot inspection; and
- export result and diagnostics.

The thin adapter and these eight tools landed in
[`compose-ai-tools` #4929](https://github.com/yschimke/compose-ai-tools/pull/4929). The follow-up
[#4933](https://github.com/yschimke/compose-ai-tools/pull/4933) makes every render/SVG/Compose export
name an explicit committed revision instead of accepting a moving default:

- `create_design`
- `open_design`
- `list_components`
- `apply_design_operations` (atomic batch)
- `render_design`
- `export_svg`
- `export_compose`
- `get_revision_diff`

Prefer a small batch-oriented semantic surface over many tools shaped like editor buttons. Tool
responses return the committed revision/document hash and validation failures that name the exact
node, slot, property, or modifier path. A long-running agent can subscribe or poll from its last
accepted-event sequence while a browser remains connected.

## 13. Functional requirements

### P0: first usable product

- Distinct builder route and navigation from the existing catalog.
- Persistent create/open/share URL for one design and one pinned `m3-catalog` revision.
- Searchable catalog grouped by scaffold/container/leaf capability.
- Semantic tree with insert, move, duplicate, delete, undo, and redo.
- Clean Compose/Wasm canvas plus non-layout-affecting overlay.
- Named slot and ordered container drops with preflight validation.
- Typed properties and the modifier subset required by the Jetcaster benchmark.
- Declarative state/event actions required by filters, tabs, paging, and bookmark selection.
- Light/dark, locale, font scale, density, and viewport environment.
- Multiple connected browsers and MCP concurrently editing one design.
- Restart-safe persistence and revision history.
- Revision-pinned PNG/inspection, SVG, and Compose exports.
- Jetcaster benchmark assembled through supported builder operations, not injected as handwritten
  source or a special-case bitmap.

### P1: hardening and breadth

- Presence avatars, selections, conflict explanations, and collaborator roles.
- Explicit catalog-revision migration with preview and rollback.
- Reusable user components/templates made from catalog nodes.
- Responsive breakpoint variants and additional viewport/device presets.
- Asset upload and token binding with quotas and provenance.
- Comments, named checkpoints, branch/copy/merge of designs.
- Additional compile-time native catalog bundles after a bundle ABI is designed.

## 14. Quality and acceptance gates

### Visual and semantic fidelity

- Clean preview contains zero selection, handle, insertion, slot, cursor, or drag chrome.
- Toggling editor overlay does not change any measured design-node bound or semantic node.
- At each fixed benchmark environment, the independently authored Wasm golden, builder render,
  and generated-Compose Wasm render
  have identical dimensions and zero differing pixels. If a platform forces nondeterministic text
  antialiasing, the documented fallback is at least `99.5%` of pixels within RGBA delta `2`, with no
  geometry shift over `1px`.
- Each benchmark bound and text baseline is within `0.5dp` of the pinned developer-authored fixture;
  typography, colour tokens, line wrapping, ellipsis, clipping, and scroll position match.
- The scene retains real component identities: Scaffold/app bar, chips, tabs, list items, surfaces,
  text/icons/dividers. It is not a flattened image.
- Filters and tabs change real selected state; list scrolling/sticky headers and row/bookmark hit
  targets work through deterministic callbacks.

### Collaboration and persistence

- Two browsers and one MCP client can apply overlapping edits and converge to the same revision and
  document hash.
- Retried operation ids do not duplicate changes; cycles and cardinality violations are rejected
  atomically; concurrent move/delete conflicts follow the documented rule.
- A reconnect from the last accepted-event sequence catches up without reload; a compacted client
  receives a valid replacement snapshot.
- An acknowledged design survives process restart and reproduces the same clean render and exports.
- Authorization tests prove one user cannot read, mutate, export, or subscribe to another private
  design without permission.

### SVG

- The primary Jetcaster export root `viewBox` is `1280 x 800`; no editor chrome or external asset URL
  is present.
- The compact Confetti regression export retains its `411 x 914` root `viewBox`.
- SVG is valid UTF-8, self-contained, and imports into Figma at 1:1 root bounds without warnings.
- Supported layers/text remain named or structurally identifiable; fallbacks are enumerated, and the
  file is not one full-screen bitmap.
- Rasterizing the imported result meets the same declared fidelity tolerance as the clean Wasm
  comparison, with any Figma-specific text rendering difference reported separately.

### Compose source

- Same revision produces byte-for-byte deterministic formatted source.
- No unresolved catalog id is emitted; unsupported nodes yield diagnostics.
- The Jetcaster benchmark compiles and renders in the pinned CMP/Wasm fixture.
- Other allowed incomplete cases contain explicit, located TODOs only for app callbacks/models and
  never hide a rasterized screen.

### Performance and reliability targets

- A committed edit is visible to other connected clients at p95 under `250ms` on the same region.
- Normal property edits update the Wasm canvas at p95 under one animation frame after receipt,
  excluding catalog/font cold start.
- Initial reopen of the benchmark design reaches an interactive clean render at p95 under `2s` on
  the reference development machine after assets are cached.
- A 60-minute three-client soak loses no acknowledged operation and converges hashes after forced
  disconnects and one server restart.

## 15. Parallel roadmap

The work is organized around stable integration contracts so independent contributors or agents can
work concurrently. A wave is a dependency boundary, not a single serial team.

Current roadmap truth:

| Gate | Evidence | Current result |
| --- | --- | --- |
| Wave 0 | independent Jetcaster oracle, reducer/model tests, offline execution bridge, exact retained runtime plus sandbox protocol | core spikes complete; SVG/Figma conformance, Confetti correction, protected-golden review, and released contract coordinates remain |
| Gate 1 | persisted round trip, strict validator, clean sibling overlay, revision-pinned code/SVG jobs | executable on current review stacks; release-boundary compatibility is not yet proven |
| Gate 2 | [real MCP/browser/restart harness #116](https://github.com/yschimke/compose-preview-server/pull/116) plus [public Jetcaster operation replay #128](https://github.com/yschimke/compose-preview-server/pull/128) | behavior proven on installed development distributions; merge/release and released-version replay remain |
| Release | [fidelity #124](https://github.com/yschimke/compose-preview-server/pull/124), [performance #118](https://github.com/yschimke/compose-preview-server/pull/118), and section 14 | open: Figma parity, protected golden, one-frame canvas p95, contract releases/catalog upgrade, and final audit |

### Wave 0: answer the irreversible questions

The four time-boxed spikes ran in parallel:

| Spike | Output | Stop/go gate |
| --- | --- | --- |
| Jetcaster decomposition and golden | Exact components, props, slots, modifiers, state/actions, asset manifest, responsive gap list, and a pinned developer-authored Wasm raster plus bounds/baselines | The expanded two-pane screen is expressible without opaque fake components and has a valid same-engine oracle |
| Composite SVG and execution bridge | Nested text/image/clip/elevation export imported/rasterized by Figma, plus proof a saved revision exports without an open editor | Editable vector strategy, runtime bridge, placement, and fallback policy meet the SVG gate |
| Collaboration reducer | Two browser-shaped clients plus an MCP-shaped caller edit, retry, undo/redo, disconnect, and converge | Operation, compensation, retention, idempotency, and recovery rules are deterministic |
| Catalog capability and runtime pinning | Capability records and Wasm adapters for scaffold/container/leaf examples; version-retention/compatibility proof | Static validation, specific slot traits, props, state/actions, code metadata, and old-design reopening work end to end |

**Gate 0:** accept the v1 design schema and command RFC, the catalog capability shape, the SVG
strategy/execution bridge, executable catalog-retention policy, developer-authored Wasm golden, and
complete Jetcaster gap list. Do not scale editor implementation before this gate.

The reducer and runtime-pinning stop/go questions now pass executable tests. The execution bridge
works without an editor browser, but its Figma raster result does not meet the product threshold.
Accepted contract additions also remain open/unreleased, so Gate 0 is not recorded as fully closed.

### Wave 1: foundations against shared fixtures

These workstreams proceeded in parallel against checked-in fixtures:

| Workstream | Deliverable | Depends on |
| --- | --- | --- |
| Contracts | Versioned document/command/event/capability DTOs, JSON fixtures, compatibility tests, release | Gate 0 schemas |
| Catalog | Generated/hand-authored `m3-catalog` capabilities, native registry, conformance tests | Capability RFC |
| Server | Reducer including compensating undo/redo, validator, in-memory `DesignStore`, HTTP/WS API, deterministic document hashing | Command fixtures |
| Wasm editor | New builder shell, fixture document renderer, overlay architecture, tree/inspector scaffolding | Document fixtures |
| Code export | Deterministic AST/template generator with fixture golden files | Document + capability fixtures |
| SVG export | Chosen composite export prototype behind a pure render-host interface | SVG spike |
| Quality | Golden document corpus, reducer model tests, screenshot/import harness skeleton | Shared fixtures |

**Gate 1:** one fixture round-trips without loss; service and Wasm resolve the same tree; invalid
cycles/slots/props are rejected; the overlay changes no design bounds; code and SVG jobs are pinned
to a revision.

Each behavior above has executable evidence, including
[`PersistentUiBuilderServiceTest`](../../ui-builder-runtime/src/test/kotlin/ee/schimke/composeai/uibuilder/service/PersistentUiBuilderServiceTest.kt),
[`CapabilityComposeCodeExporterTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/CapabilityComposeCodeExporterTest.kt),
and
[`ui-builder-renderer.spec.mjs`](../../preview-harness/ui-builder-renderer.spec.mjs). Formal release
closure remains downstream of Gate 0's contract and Figma items.

### Wave 2: collaborative vertical slice

Continue in parallel, integrating through the Gate 1 API:

| Workstream | Deliverable |
| --- | --- |
| Persistence/collaboration | Durable append/snapshot store, recovery/compaction, fanout, reconnect, conflicts, presence, ACLs, design-write grant capability |
| Editor | Catalog search, canvas/layers selection, drag/insertion overlay, slot targets, inspector, keyboard operations, undo/redo UI |
| MCP adapter | Auth plus resources/tools over the Design API, batch operations, revision subscription/polling |
| Catalog coverage | Every scaffold/container/leaf capability required by the Jetcaster gap list |
| Exports | Download endpoints, provenance, compile harness, automated Figma import/raster check or documented reproducible import harness |
| Visual harness | Fixed fonts/assets/time/environment and browser/server/generated-code comparison |

**Gate 2:** browser A and MCP edit the same persisted design while browser B observes; restart and
reconnect preserve it; the full Jetcaster screen is assembled only through public builder operations;
both exports are produced from the committed revision.

The installed-distribution harness on #116 proves the real external MCP/browser/restart path; #128
adds the full Jetcaster public-operation replay and production PNG/SVG comparison. This is executable
Gate 2 evidence, not a claim that the stacked PRs or their coordinates are released.

### Wave 3: fidelity and product hardening

Parallel finishing work:

- tune Jetcaster geometry, typography, tokens, data, assets, interaction, and responsive behaviour;
- complete SVG structure/fallback fixes and generated-code diagnostics;
- finish explicit catalog upgrade preview/apply/rollback after the contracts release; persistence
  schema migration, export audit, backup/restore, and bounded compaction already have coverage;
- finish accessibility and keyboard breadth; error recovery, presence, quotas, rate limits,
  backpressure, metrics, backup/restore, and concurrency/soak coverage already exist on the review
  stacks; and
- update the visual harness and add viewable before/after evidence for every UI-affecting change.

**Release gate:** every requirement in section 14 passes, including Figma import, compiled Jetcaster
source, multi-client/MCP convergence, restart recovery, and authorization isolation.

### Dependency map

```text
Wave 0 schema/capability/SVG decisions
  ├── contracts ──┬── server reducer/API ──┬── durable collaboration ──┐
  │               │                       └── MCP adapter ────────────┤
  ├── catalog ────┼── Wasm renderer/editor ──────────────────────────┤
  │               ├── code generator ────────────────────────────────┤
  └── SVG choice ─┴── render-host exporter ──────────────────────────┤
                                                                 Jetcaster gate
  shared fixtures/quality harness ───────────────────────────────────┘
```

Contracts, catalog, editor, exporters, and quality can advance concurrently against checked-in
fixtures. Persistence and MCP begin against the reducer API without waiting for visual polish.
Jetcaster-specific capability filling can proceed while the generic editor is built, but the final
benchmark cannot close until all lanes integrate.

## 16. Risks and explicit decision points

| Risk | Consequence | Mitigation / decision |
| --- | --- | --- |
| Catalog previews are mistaken for an authoring contract | Invalid drops, unrenderable props, and unusable code | Require explicit capabilities; never infer arbitrary Kotlin signatures from pixels |
| Native catalogs are compile-time Wasm code | “Select any catalog” cannot work as claimed | Keep v1 one-catalog; design and version a bundle ABI before adding dynamic/multiple catalogs |
| A persisted catalog hash outlives its compiled Wasm adapter | Reopened designs cannot reproduce old pixels | Gate P0 on retained versioned bundles, proven runtime compatibility, or explicit migration |
| Composite SVG loses text/effects or becomes a bitmap | Export is visually wrong or not useful in Figma | Wave 0 import spike is a stop/go gate; publish named raster fallbacks, never silent flattening |
| JVM export path cannot invoke a Wasm-only catalog | Saved designs export only while a browser is open, or not at all | Wave 0 proves the wrapper/artifact/Wasm-capture execution bridge and assigns code accordingly |
| Fonts, assets, time, animations, or locale drift | Pixel tests measure environment noise | Pin all render inputs and expose provenance; disable or deterministically seek animations |
| Concurrent tree moves/deletes are underspecified | Browser and MCP diverge or lose nodes | One server reducer and documented conflict rules; model/property tests before UI integration |
| Catalog revision changes under a design | Old designs silently change pixels or stop compiling | Pin catalog revision/digest; explicit migration with preview and rollback |
| Generated Kotlin becomes an execution lane | Public server executes attacker-controlled code | Deterministic generation; compile only controlled fixtures in CI/sandboxed existing flows |
| Product is coupled back into preview session lifecycle | Saved design disappears or render hosts stay resident | Separate design identity/store/protocol; borrow lifecycle patterns but not session state |
| New dependencies violate repository boundaries | Render host gains a web/MCP server or module direction reverses | Wire shapes in contracts, pure export in render-host, routes/store in server, MCP as API adapter |

Decisions still requiring implementation or operational evidence:

1. the retention/support window for exact versioned runtime bundles and the operator workflow for
   retiring a pin into explicit catalog migration;
2. file-backed single-process storage versus a transactional database for the first multi-replica
   public deployment;
3. which Jetcaster containers graduate from fixture-specific semantic templates into reusable
   generic catalog primitives; and
4. the accepted Figma fallback policy if structured SVG cannot reach the clean-render threshold.

Stable position keys, stale-command resolution, and structural/scalar compensating undo are no
longer open decisions; they are executable in
[`CollaborationConvergenceTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/CollaborationConvergenceTest.kt).

## 17. Repository placement

- `ui-builder` (preferred new isolated frontend directory/module): the distinct builder client,
  native renderer protocol client, editor overlay, and Design API client. It may initially reuse
  code proven in `wasm-ui`, but should not turn the preview browser prototype into the permanent
  product boundary.
- `ui-builder-runtime`: a published, transport-free JVM module containing the authoritative
  reducer, catalog validation, persistence, revision/conflict semantics, the `DesignService` port,
  and revision-pinned export orchestration. It depends on released UI-builder contracts but not on
  Ktor, MCP, Compose UI, a renderer, or the frontend project.
- `server`: Ktor design routes/WebSocket, access control, production configuration, quotas and
  adapters from the transport-free runtime to render jobs and catalog capability delivery.
- `render-host`: pure JVM dynamic design rendering/inspection and SVG/code conversion helpers that
  do not open a web server. The generated bundle is an immutable input to this lane.
- `compose-preview-contracts`: only versioned wire shapes and compatibility fixtures needed by
  clients/adapters; no store, reducer, renderer, or MCP implementation.
- `compose-ai-tools:mcp`: the existing agent-facing MCP executable remains a thin authenticated
  client of the server's Design API. It owns stdio/SDK lifecycle, agent configuration and the
  combined daemon/project tool catalog; it does not access the builder store or renderer. If the
  builder adapter becomes substantial, split it into a transport-only module inside
  `compose-ai-tools` rather than adding MCP dependencies to `server` or `ui-builder-runtime`.

Wasm inspection and capture remain inside the isolated frontend boundary and may support
interactive diagnostics, but they are not the authoritative offline export path.

### Extraction posture

The builder is expected to become large enough for its own repository and release cadence. Keep it
extractable from its first implementation:

- frontend code lives under one top-level boundary and consumes only versioned HTTP/WebSocket and
  published contract shapes, never server source classes;
- the MCP adapter is a client of the same Design API, not an in-process shortcut to the store;
- builder persistence has its own `DesignStore` interface and configuration rather than hiding
  inside `ServeSessionRegistry`, catalog caches, or preview history;
- native catalog runtimes are version-addressed artifacts with a declared protocol, not project
  classes the editor assumes are present;
- render/export is requested through a versioned job boundary with immutable inputs and products;
  and
- builder visual/integration tests can run against a released server distribution or container.

Extraction becomes appropriate when the builder has an independent release/deployment need and all
of these are true:

1. it builds and tests without reading this repository's source tree;
2. it runs against released preview-server/contracts versions with no `mavenLocal()`, composite
   include, or project substitution;
3. catalog/capability, design, collaboration, render, and export APIs cover every cross-boundary
   call with compatibility tests;
4. its data can be migrated/operated independently of preview render-session lifecycle; and
5. an end-to-end operation-replay visual test passes against the released boundary.

Until then, co-location is useful because the render and catalog seams are still being discovered.
Directory isolation and contract tests prevent that convenience from becoming an implicit API.

The co-located build now expresses the extraction seams as artifacts. The server distribution
consumes the `compose-preview-ui-builder-web` archive variant instead of reaching into the
`:ui-builder` task graph or output directory; replacing its project producer with a released
coordinate does not change distribution assembly. Production routes consume the published
transport-free `ui-builder-runtime` port/implementation and never add a `:server -> :ui-builder`
project dependency. `:ui-builder-renderer` produces a separate renderer-only CMP/Wasm directory and
ZIP rather than publishing the combined editor as a runtime. The external-consumer gate in
[#129](https://github.com/yschimke/compose-preview-server/pull/129) proves isolated coordinate
consumption for the runtime and web archive; adding the renderer ZIP and a full released-version
operation replay remains part of the extraction gate. Moving the frontend out remains a
release/deployment decision, not a source API discovery exercise.

`scripts/check-ui-builder-external-consumer.sh` makes that artifact seam executable. It publishes
the runtime and frontend to a fresh temporary Maven repository, copies a minimal Gradle consumer
and wrapper outside this checkout, compiles and runs against the runtime coordinate, and resolves
the frontend only through its exact distribution attributes. The consumer rejects project
components, artifacts under the producer checkout, source-path leakage in the repository, malformed
metadata, and an incomplete frontend ZIP. This proves the two published artifacts can be consumed
without `mavenLocal()`, a composite build, or project substitution; the broader extraction criteria
still require the released-server operation-replay and version-skew tests above.

Do not add a reverse `:render-host -> :server` edge, a web server to `:render-host`, `mavenLocal()`, a
composite include, or implementation code to the contracts repository. `checkServeModuleBoundary`
and `checkRenderHostIsServerFree` remain positive resolved-classpath gates.

## 18. Definition of done

The product is not done when components can be dragged into a list. It is done when the pinned
Jetcaster Discover supporting-pane state can be recreated through the public visual/MCP operations
as a semantic Compose tree; a clean Compose/Wasm render matches the developer-authored fixture; two
people and an agent can safely edit and recover that persisted design; Figma imports its
revision-pinned SVG; and
the exported Kotlin compiles for the golden screen while remaining recognizable, editable Compose.
