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
- `dispatchInput` for validated pointer phases and pixel-mode wheel deltas → `inputDispatched`,
  containing the post-input inspection and resolved preview state.

The editor maps returned bounds onto a pointer-inert sibling overlay. The overlay is never an
ancestor of the native renderer and therefore cannot alter Compose measurement or pixels. Input
coordinates use the same root-render-pixel space as inspection bounds, so the editor mapping is
reversible. Every input names the active document revision; stale revisions, malformed fields,
coordinates outside the viewport, duplicate request ids, unsupported kinds, and mismatched
source/origin/runtime/protocol/correlation are rejected or ignored before dispatch. Keyboard and
focus input remain explicitly unsupported.
