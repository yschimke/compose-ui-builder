# UI builder renderer runtime

This module produces the renderer-only Compose Multiplatform/Wasm runtime. It is not the editor
application and does not contain editor chrome. The `wasmRendererDist` task writes an exact runtime
directory with `runtime-manifest.json`; `rendererArchive` packages the same directory as a
consumable ZIP.

```shell
./gradlew :ui-builder-renderer:check :ui-builder-renderer:wasmRendererDist
compose-preview-server \
  --ui-builder-dir ui-builder/build/wasmDist \
  --ui-builder-runtime-dir m3-2026.09-protocol1=ui-builder-renderer/build/wasmRendererDist
```

Open `/ui-builder/?rendererRuntimeId=m3-2026.09-protocol1` to exercise the isolated vertical slice.
The editor resolves only that exact id and mounts the manifest entrypoint in an `allow-scripts`
iframe. The frame has an opaque origin. Both sides verify `MessageEvent.source`, origin, protocol,
runtime identity and request correlation.

Protocol v1 supports:

- `initialize` → `initialized`;
- `renderDocument` → `rendered`, containing authored node bounds and immediate-slot union bounds in
  root-render pixels; and
- `dispatchAction` for a measured node's registered `activate` action or vertical `scrollBy` action
  → `actionDispatched`, containing the post-action inspection and resolved preview state.

The editor maps returned bounds onto a pointer-inert sibling overlay. The overlay is never an
ancestor of the native renderer and therefore cannot alter Compose measurement or pixels. Every
action names the exact document id, revision, and inspected node id. Activation is available only
when that node is measured, visible, enabled, declares a click action, and has a callback registered
by the current Compose composition. Vertical scrolling uses the registered state of the targeted
Compose lazy container. Horizontal scrolling, pointer/wheel event forwarding, keyboard, and focus
are explicitly unsupported. Stale revisions, malformed fields, unavailable actions, duplicate
request ids, and mismatched source/origin/runtime/protocol/correlation are rejected or ignored.

The protocol deliberately does not claim browser pointer or wheel injection: script-created DOM
events are untrusted and cannot faithfully reproduce Compose hit testing, gesture arbitration,
clipping, z-order, nested scrolling, or focus. The node-id semantic protocol is the honest narrow
surface until the renderer exposes a real Compose input-injection API.
