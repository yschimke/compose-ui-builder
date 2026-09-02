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
