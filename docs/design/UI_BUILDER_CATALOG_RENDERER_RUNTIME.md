# Catalog-owned renderer runtimes

**Status: normative direction, 2026-09-20.** The executable vertical slice already exists:
`:ui-builder-renderer` produces an immutable renderer-only Wasm distribution,
`CatalogRuntimeProtocol` communicates with it through an opaque-origin iframe, and
`ServeUiBuilderRuntimeAssets` verifies and serves exact runtime ids. What remains is to invert who
builds that distribution. A catalog must build and publish the renderer that calls its components;
the editor must not compile every catalog's implementation into one application.

This supersedes the earlier decision in
[`UI_BUILDER_CATALOG_CONTRACT.md`](UI_BUILDER_CATALOG_CONTRACT.md) that Material canvas adapters stay
in this repository. It also completes the separately-designed bundle/plugin ABI deferred by the
product spec. It does **not** permit arbitrary code in the editor process: catalog code runs only in
the existing sandboxed renderer surface.

## The boundary

The cross-repository boundary is a **self-contained web distribution plus a JSON protocol**, not a
Kotlin or Maven dependency:

```text
catalog delivery generation
  catalog.json
  ui-builder.json
  ui-builder/runtime.zip
    runtime-manifest.json
    <catalog renderer>.mjs
    <catalog renderer>.wasm
    skiko.mjs
    skiko.wasm
    fonts/…
```

The catalog's design-artifacts workflow builds the ZIP and publishes it beside `ui-builder.json`.
The server fetches it through the same catalog source it already uses for the component record,
tokens, templates and live bundle. Nothing is published to a Maven repository and the deployed
server does not link a catalog class.

The catalog renderer itself may compile against the renderer SDK from a pinned source checkout of
this repository, using a Gradle composite build. That is a build-time source dependency. The output
crossing into a deployment is still only the verified Wasm distribution.

## Ownership

The builder owns:

- document editing and validation;
- the renderer protocol and sandbox host;
- catalog/runtime resolution and migration;
- selection, bounds and drop overlays outside the renderer;
- the generic placeholder used when no compatible renderer is available; and
- a small renderer SDK: document traversal, authored modifiers, state bindings, inspection and slot
  reporting.

Each catalog owns:

- the dependencies needed to draw its system;
- its component adapter registry;
- its theme and frame implementation;
- bounded and unrolled presentations of its structural components;
- component-specific interaction and receiver-scoped behavior; and
- tests comparing its catalog renderer with its native render.

For example, `WearCanvasComponents.kt`, the Wear screen scaffold, the transforming-list adapter,
Wear typography and the Wear CMP dependencies move to `wear-m3-catalog`. The corresponding Material
and Remote Compose implementations live with `m3-catalog` and `remote-m3`.

## The SDK is not a second Compose

A catalog renderer needs concise helpers, but a data language containing `Column`, `Grid` and
`SlotContainer` must not become the runtime boundary. It cannot honestly express receiver-scoped
APIs such as `TransformingLazyColumnItemScope.minimumVerticalContentPadding`, shared scaffold/list
state or a component's own interaction. Growing it until it can would recreate Compose badly.

Those names belong in a Kotlin SDK used while building the catalog renderer:

```kotlin
interface CatalogCanvas {
  fun register(registry: CanvasAdapterRegistry)
}

interface CanvasNodeScope {
  val node: CanvasNode
  val mode: CanvasMode // AuthoringUnrolled or Device

  @Composable fun Slot(
    name: String,
    presentation: SlotPresentation = SlotPresentation.Direct,
  )

  @Composable fun Items(
    name: String,
    content: @Composable CanvasItemScope.(CanvasNode) -> Unit,
  )
}

sealed interface SlotPresentation {
  data object Direct : SlotPresentation
  data class Column(val spacingDp: Float = 0f) : SlotPresentation
  data class Row(val spacingDp: Float = 0f) : SlotPresentation
  data class Grid(val columns: Int, val spacingDp: Float = 0f) : SlotPresentation
  data object Overlay : SlotPresentation
}
```

The helpers own traversal and inspection. The catalog still calls its real composables. A Wear list
can therefore apply its real item-scope modifier rather than asking a generic JSON interpreter to
approximate it.

## Identity and immutability

One catalog generation resolves to one exact renderer descriptor:

```text
(systemId, catalogRevision, capabilityDigest)
  -> (runtimeId, protocolVersion, integritySha256)
```

Runtime ids are globally unique and catalog-qualified, for example:

```text
m3-catalog-p2-a13f09c2
remote-m3-p2-912dc872
wear-m3-p2-5e77ab31
```

Bytes behind `/ui-builder/runtime/<runtimeId>/` are immutable. A publisher reusing an id with a
different digest is rejected; there is no `current` or `latest` alias. The document pin names the
runtime id, while the server's descriptor binds it to the other three catalog fields so ids cannot
be exchanged between catalogs.

## A deployed server picks up catalog renderers

Runtime registration is dynamic catalog state, not only the startup-only
`--ui-builder-runtime-dir` map. A catalog refresh is transactional:

1. Fetch the new `catalog.json`, `ui-builder.json` and declared runtime ZIP into staging.
2. Verify catalog identity, protocol compatibility, archive paths and limits, manifest fields and
   the complete tree digest.
3. Install the runtime under its content digest in the durable state directory.
4. Add its exact runtime descriptor alongside every retained descriptor.
5. Atomically activate the catalog generation only after all of the above succeeds.
6. Notify connected editor shells that a newer catalog generation exists.

If the runtime is absent, malformed or speaks an unsupported protocol, the previous catalog
generation remains active. New capability metadata must never be paired with an old executable.

Implementation updates that keep a supported protocol require no server deployment. A protocol
version the editor does not support requires an editor/server release; the catalog refresh reports
that incompatibility and does not replace the active generation.

## Multiple catalogs and tabs

There is no process-global or browser-global "current renderer". Runtime resolution happens from
the complete pin of each design. At the same instant one deployed server and one browser may hold:

```text
tab A -> m3-catalog-p2-a13f09c2
tab B -> remote-m3-p2-912dc872
tab C -> wear-m3-p2-5e77ab31
tab D -> an older retained wear-m3 runtime
```

Each tab mounts the selected runtime in its own opaque-origin iframe. Multiple Preview device panes
inside that tab use independent surface/session ids against that runtime. Session keys include the
editor instance, design id, document revision and surface id; neither runtime state nor inspection
may be keyed by catalog id alone.

The Wasm runs in the browser. Concurrent tabs do not start one server process per renderer. Native
Preview remains the separate daemon-backed rung and retains its own resource limits.

## Protocol surfaces

Protocol v2 adds a surface description to `renderDocument` rather than making the renderer infer
authoring intent by mutating the document:

```json
{
  "mode": "authoring-unrolled",
  "widthDp": 192,
  "heightDp": 192,
  "density": 2,
  "surfaceId": "editor"
}
```

or:

```json
{
  "mode": "device",
  "widthDp": 240,
  "heightDp": 240,
  "density": 2,
  "surfaceId": "wear-xl"
}
```

The renderer returns revision-bound node bounds, slot bounds, content extent, semantic actions and
diagnostics. Interaction stays semantic (`activate`, `scrollBy`) until the renderer protocol gains a
real Compose input-injection API; synthetic DOM events remain forbidden for the reason protocol v1
records.

## Opening a design means updating it

Pins preserve history per revision; they do not make the editable head stale forever. Opening a
design whose pin is not the current catalog generation enters an update gate before the editor:

1. Render the saved revision with its exact retained runtime.
2. Resolve a catalog-published, declarative migration chain to the current generation.
3. Validate the migrated document and show old/new renders and located decisions.
4. Require the user to resolve every ambiguous change.
5. Commit the document operations and complete new pin as one atomic design revision.
6. Enter the editor on the new head.

A renderer-only update is still a migration: the document tree is unchanged, the user reviews the
pixel change, and a pin-only revision records acceptance. A migration another tab completed first
invalidates the stale proposal and makes that tab reload the new head.

Migration operations are catalog-owned data: component, property, enum and slot renames; defaults;
modifier rewrites; node wrapping/unwrapping; and environment changes. They are not executable code
loaded into the server. With no complete path, the historical revision remains viewable but editing
is blocked with located diagnostics.

## Retention

The server stores verified runtime trees under its durable UI-builder state directory and retains a
runtime while any persisted design revision, active editor session or configured support window
references it. Catalog publication also provides an immutable source address (a commit-pinned
delivery URL or catalog release asset) so a fresh deployment can hydrate a historical pin; a moving
delivery-branch URL is not sufficient by itself.

Garbage collection is reference-based and never removes bytes merely because a newer runtime was
activated.

## Migration sequence for the implementation

1. Extract renderer traversal/inspection and the adapter registry into a small renderer SDK.
2. Add protocol v2's explicit surface mode while retaining protocol v1 during migration.
3. Add a renderer-distribution declaration to the generated catalog contract.
4. Teach the catalog refresher to stage and atomically register declared runtimes.
5. Make the editor resolve and mount a runtime from each design's pin by default.
6. Build and publish `wear-m3-catalog`'s renderer and move all Wear canvas code into it.
7. Remove Wear CMP dependencies and Wear adapter branches from this repository.
8. Repeat for `m3-catalog` and `remote-m3`; retain the placeholder runtime for catalogs that publish
   none.
9. Add the mandatory open-time migration gate and catalog-owned migration documents.

The acceptance test is operational, not a unit-test fiction: a running server discovers a new Wear
runtime without restarting while existing M3, Remote M3, current Wear and retained old-Wear designs
remain open in separate tabs, each drawing through its own exact runtime.
