# Compose UI Builder product specification

**Status:** proposed

**Product surface:** Compose Multiplatform/Wasm builder plus an MCP adapter

**Initial catalog:** `m3-catalog` / the native `compose-m3` catalog

**First benchmark:** Confetti compact Schedule screen at `997afd9645ab614d3bfccec15a886820c9e2dd08`

## 1. Summary

Build a collaborative visual editor for Compose Multiplatform projects that can produce a Wasm
application. A person or an agent assembles real, compiled catalog components into a semantic
Compose tree, sees that tree rendered by Compose/Wasm, and exports the same saved design as
Figma-compatible SVG or readable Compose code.

This is a distinct product from the catalog and preview surfaces at `preview.coo.ee`. It reuses their
catalog inventory, metadata, fonts, source information, render products, authentication patterns,
and rendering infrastructure, but it does not turn a preview session into an editable document.

The current `?compose=1` prototype proves that compiled catalog composables can be dragged, nested
through `PreviewSlot`, and interacted with in Wasm. It is not the product model. Its root is a fixed
vertical list, its state lives only in browser memory, and its drag gutters, borders, and slot
placeholders participate in layout. The product must render the actual screen on the canvas and put
selection, insertion, and drag affordances in a separate overlay that never changes or exports the
design.

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

## 4. The first real-screen benchmark

The release benchmark is the compact/mobile Schedule screen from
[Confetti](https://github.com/joreilly/Confetti/tree/997afd9645ab614d3bfccec15a886820c9e2dd08),
an Apache-2.0 Compose Multiplatform conference application whose shared module declares a Wasm
browser target and uses Material 3.

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

The benchmark fixture must retain Confetti attribution and the Apache-2.0 notice. Its use must not
imply endorsement.

## 5. User experience

### Product surface

The builder gets its own route and product identity, for example `/wasm/builder/`. It is not a
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

The initial native adapter must expand beyond the prototype's small pipeline-test set. The Confetti
benchmark determines the exact first inventory, expected to include:

- `Scaffold` and `CenterAlignedTopAppBar` templates with named slots;
- `Column`, `Row`, `Box`, lazy list/row, pager, and flow/ordered containers;
- `FilterChip`, tab row/tab/indicator, `ListItem`, `Surface`, divider, icon, text, spacer, and badge;
- schedule-specific semantic templates only where they remain compositions of reusable catalog
  primitives and export as recognizable Compose, not opaque painted widgets.

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
- A design WebSocket sends an initial snapshot or a delta from the client's last revision, committed
  operations, validation/conflict notices, presence, and export status.
- Reconnect from a retained revision must converge without a full-page reload. If the operation log
  has compacted past that revision, the server sends a new snapshot.
- Existing `/ws/{previewId}` frame streaming remains a render-session protocol. It must not be
  overloaded with editable-document messages.

Call the catalog renderer lifecycle a **catalog session** and the saved product a **design**. Avoid
using `session` for both concepts in APIs and UI.

## 9. Persistence and access

Introduce a `DesignStore` boundary supporting atomic append plus revision, snapshot/replay, history,
soft deletion, export provenance, and bounded compaction.

The first deployment may use a file-backed, single-server-process implementation with an advisory
lock, append-only operations, checksummed snapshots, atomic replacement, and startup recovery. That
scope must be explicit. The interface must not bake in process memory so a transactional database
adapter can later support multiple server replicas.

Required durability properties:

- an acknowledged edit survives process restart;
- a corrupt or partial tail is detected and does not destroy the last valid snapshot;
- document and catalog revisions needed by an export are recorded together;
- compaction preserves undo/history promises and idempotency records for their declared window;
- storage, document size, operation rate, asset size, and connected-client counts are bounded; and
- backup/restore and schema migration are tested before public multi-user use.

Persistence also includes the renderable catalog runtime contract. For every catalog revision the
product promises to reopen, the deployment must either retain a version-addressed builder Wasm
bundle/native adapter, prove a compatibility guarantee that the current adapter renders the pinned
capability digest identically, or require an explicit migration before rendering. Wave 0 chooses
one policy. A stored catalog hash without executable code that understands it is not a valid pin.

Reuse the current front-door and GitHub identity rules for human access. Agent grants should gain a
separate design-write capability rather than treating editing as a higher point on the
`preview -> live -> playground` compute ladder. Read, write, export, and any server-side compile or
render costs must be authorized independently. A grant may not approve another grant.

Designs are private to their owner/collaborators by default. Sharing requires an explicit ACL or
unguessable read-only link. Every mutation and export records the actor identity without recording
bearer credentials.

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

Existing catalog source/usage and Code Connect metadata can supply symbols, imports, required
parameters, and slot mappings, but the whole-screen AST-to-Kotlin generator is new work.

### Figma-compatible SVG

The repository already serves layered, self-contained `compose/figma-svg` for daemon-rendered
catalog previews and inlines hybrid raster crops that Figma cannot fetch. A dynamically assembled
design does not yet have that export path.

Before committing the implementation, a time-boxed spike must compare two approaches and prove the
execution bridge for exporting a saved revision when no editor browser is open:

1. render a generated design wrapper through the existing Compose `figma-svg` pipeline; or
2. record the Wasm scene/layout and assemble vector catalog fragments with declared raster
   fallbacks.

Choose one only after importing a nested screen with text, clipping, elevation, and images into
Figma and comparing its rasterization to the clean Wasm render. A single full-screen PNG wrapped in
SVG is not success. Supported text and component groups must remain identifiable/editable, external
URLs must be removed, fonts/assets must be embedded or resolved deterministically, and any raster
fallback must be named in export metadata.

The chosen path must say how the native Wasm-only catalog becomes executable to the exporter: a
compatible catalog target/artifact, generated wrapper compiled into an existing render session, or
an explicitly bounded Wasm capture boundary. `:render-host` cannot directly call the current
Wasm-only `:native-catalog-m3` implementation.

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

Initial tools:

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
revision while a browser remains connected.

## 13. Functional requirements

### P0: first usable product

- Distinct builder route and navigation from the existing catalog.
- Persistent create/open/share URL for one design and one pinned `m3-catalog` revision.
- Searchable catalog grouped by scaffold/container/leaf capability.
- Semantic tree with insert, move, duplicate, delete, undo, and redo.
- Clean Compose/Wasm canvas plus non-layout-affecting overlay.
- Named slot and ordered container drops with preflight validation.
- Typed properties and the modifier subset required by the Confetti benchmark.
- Declarative state/event actions required by filters, tabs, paging, and bookmark selection.
- Light/dark, locale, font scale, density, and viewport environment.
- Multiple connected browsers and MCP concurrently editing one design.
- Restart-safe persistence and revision history.
- Revision-pinned PNG/inspection, SVG, and Compose exports.
- Confetti benchmark assembled through supported builder operations, not injected as handwritten
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
- At the fixed Confetti environment, the developer-authored Confetti Wasm golden, builder render,
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
- A reconnect from the last revision catches up without reload; a compacted client receives a valid
  replacement snapshot.
- An acknowledged design survives process restart and reproduces the same clean render and exports.
- Authorization tests prove one user cannot read, mutate, export, or subscribe to another private
  design without permission.

### SVG

- Root `viewBox` is `411 x 914`; no editor chrome or external asset URL is present.
- SVG is valid UTF-8, self-contained, and imports into Figma at 1:1 root bounds without warnings.
- Supported layers/text remain named or structurally identifiable; fallbacks are enumerated, and the
  file is not one full-screen bitmap.
- Rasterizing the imported result meets the same declared fidelity tolerance as the clean Wasm
  comparison, with any Figma-specific text rendering difference reported separately.

### Compose source

- Same revision produces byte-for-byte deterministic formatted source.
- No unresolved catalog id is emitted; unsupported nodes yield diagnostics.
- The Confetti benchmark compiles and renders in the pinned CMP/Wasm fixture.
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

### Wave 0: answer the irreversible questions

Run four time-boxed spikes in parallel:

| Spike | Output | Stop/go gate |
| --- | --- | --- |
| Confetti decomposition and golden | Exact components, props, slots, modifiers, state/actions, gap list, and a pinned developer-authored Wasm raster plus bounds/baselines | Screen is expressible without opaque fake components and has a valid same-engine oracle |
| Composite SVG and execution bridge | Nested text/image/clip/elevation export imported/rasterized by Figma, plus proof a saved revision exports without an open editor | Editable vector strategy, runtime bridge, placement, and fallback policy meet the SVG gate |
| Collaboration reducer | Two browser-shaped clients plus an MCP-shaped caller edit, retry, undo/redo, disconnect, and converge | Operation, compensation, retention, idempotency, and recovery rules are deterministic |
| Catalog capability and runtime pinning | Capability records and Wasm adapters for scaffold/container/leaf examples; version-retention/compatibility proof | Static validation, specific slot traits, props, state/actions, code metadata, and old-design reopening work end to end |

**Gate 0:** accept the v1 design schema and command RFC, the catalog capability shape, the SVG
strategy/execution bridge, executable catalog-retention policy, developer-authored Wasm golden, and
complete Confetti gap list. Do not scale editor implementation before this gate.

### Wave 1: foundations against shared fixtures

After the Wave 0 RFCs, run these workstreams in parallel:

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

### Wave 2: collaborative vertical slice

Continue in parallel, integrating through the Gate 1 API:

| Workstream | Deliverable |
| --- | --- |
| Persistence/collaboration | Durable append/snapshot store, recovery/compaction, fanout, reconnect, conflicts, presence, ACLs, design-write grant capability |
| Editor | Catalog search, canvas/layers selection, drag/insertion overlay, slot targets, inspector, keyboard operations, undo/redo UI |
| MCP adapter | Auth plus resources/tools over the Design API, batch operations, revision subscription/polling |
| Catalog coverage | Every scaffold/container/leaf capability required by the Confetti gap list |
| Exports | Download endpoints, provenance, compile harness, automated Figma import/raster check or documented reproducible import harness |
| Visual harness | Fixed fonts/assets/time/environment and browser/server/generated-code comparison |

**Gate 2:** browser A and MCP edit the same persisted design while browser B observes; restart and
reconnect preserve it; the full Confetti screen is assembled only through public builder operations;
both exports are produced from the committed revision.

### Wave 3: fidelity and product hardening

Parallel finishing work:

- tune Confetti geometry, typography, tokens, data, interaction, and responsive behaviour;
- complete SVG structure/fallback fixes and generated-code diagnostics;
- add schema/catalog migrations, explicit catalog upgrade, export history, and audit trail;
- add accessibility, keyboard editing, error recovery, quotas, rate limits, backpressure, metrics,
  backup/restore, and concurrency/security soak tests; and
- update the visual harness and add viewable before/after evidence for every UI-affecting change.

**Release gate:** every requirement in section 14 passes, including Figma import, compiled Confetti
source, multi-client/MCP convergence, restart recovery, and authorization isolation.

### Dependency map

```text
Wave 0 schema/capability/SVG decisions
  ├── contracts ──┬── server reducer/API ──┬── durable collaboration ──┐
  │               │                       └── MCP adapter ────────────┤
  ├── catalog ────┼── Wasm renderer/editor ──────────────────────────┤
  │               ├── code generator ────────────────────────────────┤
  └── SVG choice ─┴── render-host exporter ──────────────────────────┤
                                                                  Confetti gate
  shared fixtures/quality harness ───────────────────────────────────┘
```

Contracts, catalog, editor, exporters, and quality can advance concurrently against checked-in
fixtures. Persistence and MCP begin against the reducer API without waiting for visual polish.
Confetti-specific capability filling can proceed while the generic editor is built, but the final
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

Decisions intentionally left for Wave 0 evidence:

1. generated-wrapper versus recorded-scene implementation for composite SVG;
2. retained versioned native bundles versus a catalog runtime compatibility/migration policy;
3. exact stable child-position key, stale-command resolution, and compensating undo rules;
4. file-backed single-process store format versus a transactional database for the first public
   deployment; and
5. which Confetti containers are generic primitives versus reusable semantic catalog templates.

## 17. Repository placement

- `ui-builder` (preferred new isolated frontend directory/module): the distinct builder client,
  native renderer protocol client, editor overlay, and Design API client. It may initially reuse
  code proven in `wasm-ui`, but should not turn the preview browser prototype into the permanent
  product boundary.
- `server`: design routes/WebSocket, reducer orchestration, access control, persistence, export jobs,
  and catalog capability delivery.
- `render-host`: pure JVM dynamic design rendering/inspection and SVG/code conversion helpers that
  do not open a web server, if the chosen execution bridge supplies a compatible input.
- `compose-preview-contracts`: only versioned wire shapes and compatibility fixtures needed by
  clients/adapters; no store, reducer, renderer, or MCP implementation.
- external MCP executable/plugin: a thin authenticated client of the server's Design API unless a
  separately reviewed server transport proves compatible with the module/classpath boundary.

If recorded Wasm scene capture is selected, capture remains in the isolated builder frontend
boundary; only its versioned portable shape crosses the boundary and its pure conversion may live
in `render-host`. The Wave 0 execution-bridge decision is authoritative over these conditional
placements.

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

Do not add a reverse `:render-host -> :server` edge, a web server to `:render-host`, `mavenLocal()`, a
composite include, or implementation code to the contracts repository. `checkServeModuleBoundary`
and `checkRenderHostIsServerFree` remain positive resolved-classpath gates.

## 18. Definition of done

The product is not done when components can be dragged into a list. It is done when the pinned
Confetti Schedule screen can be recreated through the public visual/MCP operations as a semantic
Compose tree; a clean Compose/Wasm render matches the developer-authored fixture; two people and an
agent can safely edit and recover that persisted design; Figma imports its revision-pinned SVG; and
the exported Kotlin compiles for the golden screen while remaining recognizable, editable Compose.
