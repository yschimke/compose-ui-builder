# Remote Compose composition in the UI Builder

## Decision

The UI Builder document is the composite document. A `remote-compose/document` node embeds one
Remote Compose wire document and participates in the outer tree like any other container. This
keeps ownership, ordering, modifiers, collaboration, and export orchestration in one existing
document instead of introducing a second manifest.

The implementation uses the common Compose Multiplatform player API from `rc-players`. It is not a
Wasm-specific player design: JVM, Android, iOS, and Wasm hosts can use the same node renderer. The
current Base64 source is intentionally transport-neutral. A later URL, bundle, or repository
resolver should load and verify bytes outside the renderer, then supply the same decoded document.

The checked-in JVM Compose render evidence captures the same fixture immediately
[before](evidence/ui-builder-remote-compose/before.png) and
[after](evidence/ui-builder-remote-compose/after.png) registering its `hero.card` slot. The render
test regenerates both images while also asserting the nested slot's centre pixel.

The preview deployment exposes this adapter as a second catalog-scoped builder at
`/ui-builder/remote-m3/`. Catalog registration is intentionally insufficient: the server's
`--ui-builder-catalogs` allowlist admits only reviewed authoring adapters, currently `m3-catalog`
and `remote-m3`. Each instance creates an exact catalog pin. The Remote M3 catalog starts with the
two stable Wear widget host preview sizes as slot-bearing scaffolds—Small 216×76dp and Large
216×124dp—and exposes only six relevant fill components. Their geometry is copied locally from the
published 240dp-screen squircle preview contract so the builder does not acquire a runtime
dependency on Glance preview tooling.

| Default catalog instance | Explicit Remote Compose catalog instance |
| --- | --- |
| ![M3 catalog UI-builder instance](evidence/ui-builder-remote-compose/m3-catalog-builder.png) | ![Remote M3 UI-builder instance](evidence/ui-builder-remote-compose/remote-m3-builder.png) |

The catalog-scoped site also owns the complete first-use path. Opening the builder without a
design selects an enabled catalog and template, validates the design ID, and navigates into the
new live document without requiring a hand-authored URL. Each compatible catalog row has an **Add**
action as well as drag-and-drop. Selection, search, and generated-operation numbering survive
authoritative collaboration snapshots, so authors can add a container and immediately add its
children without reselecting or re-searching after every save.

| New Remote M3 widget | Authored entirely in the website |
| --- | --- |
| ![New widget catalog, template, and design ID chooser](evidence/ui-builder-remote-compose/new-widget-dialog.png) | ![Large Wear widget with a Column and edited Text](evidence/ui-builder-remote-compose/new-widget-complete.png) |

## Where a document comes from

`documentBase64` is the node's only required property, and until the palette below existed nothing
in the editor could produce one. The inspector offered it as a plain string field, so embedding a
real component meant pasting a couple of kilobytes of Base64 by hand; the reviewed `remote-m3`
adapter could therefore *hold* Remote Compose content without any author being able to *put* it
there.

The source of those bytes is the serving catalog's own published document. Every sticker in
`yschimke/wear-m3-catalog`'s `:remote-catalog` travels as data — `CapturingWearWidgetPreview` and
`RemoteSticker` offer the captured `RemoteDocument` to `IrSidecarChannel`, `BundlePreviewTask` packs
it as the preview's IR, and the server serves it verbatim at `GET /{system}/render/{id}.rc`. The
UI builder now reads that lane:

1. `GET /{catalog}/api/previews` names which previews publish one. That is a new `remoteCompose`
   field on the previews API, additive since `compose-preview-serve/v3` and set from the host's own
   `hasRemoteComposeDoc`. `modes` could not answer it — a Remote Compose sticker and a Jetpack
   Compose preview are both `snapshot` — and probing `.rc` per preview is 476 requests to learn
   something the host already knows.
2. **Add** fetches `render/<id>.rc`, Base64-encodes it, and the reducer decodes it before building
   the operation. The renderer decodes it too, because playing it is what it does; refusing here is
   what stops a catalog lane's HTML error page from becoming a saved design revision that every
   collaborator sees as an error box.
3. The insert lands as one `InsertNode` carrying the bytes, not an insert followed by a property
   write. The intermediate state — a `remote-compose/document` with no document — renders as its
   own diagnostic, and collaborators would watch it appear.

The palette is **not** a set of components. Declaring `remote-m3`'s 476 published stickers as
`ComponentCapability` entries would put the whole sheet through the capability wire, the validator
and the exporter to describe content that is always the same component with different bytes. The
component stays one; a source is a named set of bytes it can be given.

Nothing new is configured to connect the two halves: `/ui-builder/remote-m3/` authors against the
capability adapter named `remote-m3` and `/remote-m3/` serves the published catalog of the same
name from the same box, so the palette finds its content by that shared id. A box serving one
without the other gets an empty palette rather than an error, exactly as a box with no device-preset
route gets the inspector's raw fields.

| Before: the editor's catalog panel | After: the same panel with the palette |
| --- | --- |
| ![Editor chrome with only the component catalog](evidence/ui-builder-remote-compose/palette-before.png) | ![The same panel with a Remote Compose documents section grouped by component family](evidence/ui-builder-remote-compose/palette-after.png) |

Both are `@Preview`s in `:ui-builder` — `UiBuilderLayoutInspectorPreview` and the new
`UiBuilderRemoteComposePalettePreview`, at the same size and the same selection — so the next change
to either state of the panel is diffed without anyone remembering to render it.

This is the transport-neutral resolver the decision above anticipated, in its smallest honest form:
bytes are loaded and verified outside the renderer and the same decoded document is supplied. What
it is not yet is the *suspendable, size-limited resolver with content hashes and caching* of
follow-up 1 — the bytes are copied into the design, so a design does not track the catalog when the
catalog republishes, and 476 rows are listed but only the added ones are fetched.

## Native options for the visual editor

The editor's canvas is already native Compose, not a picture of one: `/ui-builder/` is a Compose
Multiplatform/Wasm application, `UiBuilderRenderer` composes the document with real Material 3, and
a `remote-compose/document` node inside it is played by `RcComposePlayer` from `rc-player-compose`
in the same composition. There is no PNG round trip and no server render in the authoring loop, and
a nested document keeps its own runtime state across recomposition.

That answers "could the editor use the CMP Wasm player?" with "it is what it uses". The remaining
question is which *other* players the same document can be checked against, and this repository
already runs four of them over the identical `ir/<id>.rc` bytes:

| Player | Where it runs | What it is for here |
| --- | --- | --- |
| `cmp-wasm` | the browser, in-process | the editor canvas, and the `/wasm/<system>/` catalog frontend |
| `cmp-jvm` | the server, isolated desktop-player subprocess | headless render evidence and the parity wall's published rasters |
| `cmp-android` | the Android daemon | the on-device Compose rendition |
| `java` | AOSP's view-backed `RemoteComposePlayer`, drawing into a framework `Canvas` | the non-Compose reference the others are checked against |

So the useful framing is not "is there a more native editor than the Wasm one" — the same
`rc-players` API compiles for JVM, Android, iOS and Wasm, and a desktop or Android host of this
editor would render the identical node tree. It is that a Remote Compose document has four
independent rasterisers, the compare wall exists to show where they disagree, and an authoring
surface that draws with one of them should be read beside it rather than trusted alone.

Two adjacent native surfaces are deliberately **not** this editor, and should not be confused with
it:

- `:wasm-ui`'s UI Composer (`/wasm/compose-m3/?compose=1`) composes *compiled* catalog composables
  and fills regions their preview source declares with `PreviewSlot`. It is native in a stronger
  sense — the real component, not a document — and correspondingly cannot author anything the
  frontend was not compiled with, which is why it substitutes only for `compose-m3` and falls back
  to snapshots elsewhere. Remote Compose needs the opposite property: content that arrives as data.
- The JVM Compose render port (`ServeUiBuilderNativePreview`, the editor's **Native** button)
  renders a saved revision with real Compose on the host. It is a second opinion on the canvas, not
  a second canvas.

## Named slots

Every key in the node's `slots` map registers a Remote Compose custom-component config of the same
name. When the child document emits that custom component, the player renders the UI Builder nodes
listed in the matching slot inside the custom component's measured bounds.

For example, the outer node can declare a `hero.card` slot and the nested Remote Compose document
can emit a custom component configured as `hero.card`. Slot names are authored by documents, so the
existing `DynamicSlots` trait marks components that accept document-authored names without changing
the released catalog wire shape. Existing named slots participate in validation, tree editing,
moving, duplication, and deletion.

Unregistered custom configs remain a player support issue rather than silently selecting arbitrary
outer content. Multiple children in one slot deliberately overlay in a `Box`; authors that need
linear layout should put a UI Builder layout node in the slot.

## State and events

Each nested player owns its Remote Compose runtime state, including animations, gestures, and
document-local variables. Recomposition does not recreate that state.

The optional `namedValues` property is the explicit host-to-child boundary. Entries are keyed by
the Remote Compose named-variable name and use one of these shapes:

```json
{
  "USER:title": { "type": "text", "value": "Featured" },
  "USER:progress": { "type": "float", "value": 0.5 },
  "USER:selectedId": { "type": "integer", "value": 7 },
  "USER:query": { "type": "stateText", "variable": "searchQuery" }
}
```

Literal `long` and ARGB `color` values are also supported. `stateText` follows an outer UI Builder
state variable. Updates are applied incrementally through the player's snapshot-backed named-value
holder, so they do not reset the child runtime.

Child-to-host events reuse UI Builder event bindings:

- a named host action dispatches the binding with that exact name;
- numeric and metadata host actions dispatch `hostAction:<id>`;
- debug messages are not application events.

This boundary is intentionally directional. Remote Compose document-local mutations stay local;
documents publish values they want the host to observe through host actions.

## Theme and other player services

The node defaults to `inherit`, mapping the outer light/dark/system environment to the nested
player. It may override this with `light`, `dark`, or `system`. Typefaces, system colours, resource
resolution, logging, haptics, time, and accessibility are player host services rather than fields
in the composite document. Defaults are used in the first slice; a shared host-services object is
the next extension if applications need to inject those consistently across nested players.

## Follow-up features useful to every CMP player

1. A suspendable, size-limited document resolver with content hashes, caching, cancellation, and
   cycle/depth limits. Browser fetch, Android resources, files, and bundles become adapters.
2. Player support preflight exposed before composition, including missing custom configs, fonts,
   images, opcodes, and capability versions.
3. Bidirectional observable named values, avoiding the current need for a host action when the host
   wants to observe document-local changes.
4. Structured event payload bindings so outer state actions can consume a named action's value or
   metadata instead of only reacting to its name.
5. A stable host-services interface for typography, system colours, resources, diagnostics,
   accessibility, clocks, and effects, inherited through recursively nested players.
6. Explicit recursion budgets and stable instance keys for child documents so navigation and
   editor selection can address nested content safely.
7. Renderer-neutral export policy: flatten through a capable player, preserve the nested document,
   or report a blocking unsupported feature. The first slice blocks structured SVG export rather
   than producing a misleading fallback.
8. Declarative mappings between custom-component properties/return channels and the outer
   document's typed state, so filled slots can consume child parameters without adopting RC wire
   property ids throughout the UI Builder tree.
