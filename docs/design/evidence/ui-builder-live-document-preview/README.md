# Live exported-document playback in the existing editor

The existing **Preview** button now plays the RC export of a saved design when its catalog and host
advertise binary export. **Design** retains the semantic layout tree and editing canvas. The additional
interactive pane uses the same compiled preview host. No additional app or document-operation tree
is introduced.

The host lowers the saved tree to authoring JSON and compiles it with `remotecompose-json`; Preview
fetches the exact revision's `.rc` bytes and uses the existing CMP/WASM `RcComposePlayer`. It waits
for optimistic edits to be saved, checks the returned revision, cancels stale requests, and displays
compilation or player-support diagnostics. It does not replace refused operations with lookalikes.

## Browser and MCP proof

`sample.document.json` is the semantic design; `sample.json` and `sample.rc` are its actual server
exports. The `sample-density-2` files are a separate actual export at density 2, used to verify
displayed size and transformed click coordinates. The three empty Box children have different backgrounds and click actions that select
the next state. The cases match 10 and 20; 30 takes the fallback.

The unedited Chrome screenshots show:

- `first.png`: the exported initial branch in Preview.
- `second.png`: after clicking the first branch.
- `fallback.png`: after clicking the second branch.
- `first-again.png`: after clicking the fallback.
- `mcp-edited.png`: Preview automatically refreshed to revision 1 after `ui_builder_apply` changed
  the declared initial state to 20. The editor stayed open throughout.
- `design-after.png`: returning to the semantic editing canvas at that saved revision.

`verification.json` records the observed colors and bounds, exact MCP mutation, and both revisions'
digests. At revision 0 the
browser loaded 790 bytes with SHA-256 `105c3660935928be0976638aab9583418c427c1e100ca5e0d22f4667e20d53dd`;
hosted MCP returned byte-identical content. Revision 1 also loaded 790 bytes, now with digest
`8c38b6adc65002bef353b493ec7cc3296be84f6f2a3039ecf63dc96dccb62ec4`, again identical to hosted MCP.
No browser errors occurred. Captures move the pointer away after clicks so normal hover indication
does not tint the authored branch colors.

Run against the local server with staged contracts, compiler and player:

```sh
python3 scripts/stage-local-dependency.py --checkout /path/to/rc-players --module :rc-player-protocol --module :rc-player-runtime --module :rc-player-trace --module :rc-player-compose --output build/local-dependencies/remote-export
VERIFY_REMOTE_DOCUMENT_PREVIEW=true ./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties -Pkotlin.incremental.wasm=false :ui-builder:jvmTest :ui-builder:wasmFrontendDist
npm ci --prefix preview-harness
UI_BUILDER_TEST_TOKEN='<local-token>' node preview-harness/capture-live-document-preview.mjs http://127.0.0.1:5621
UI_BUILDER_TEST_TOKEN='<local-token>' node preview-harness/capture-live-document-preview.mjs http://127.0.0.1:5621 sample-density-2
```

Stage contracts and compiler into that same output as described in the implementation tracker.
Set `CHROME_PATH` to use installed Chrome. The capture script creates its own local sample and
checks playback, the revision-pinned network bytes, MCP export equality and live MCP mutation.
The density-2 run records separate verification data and first/updated screenshots.

The final local link used Kotlin's [non-incremental Wasm option](https://kotlinlang.org/docs/wasm-configuration.html#kotlin-wasm-incremental-compilation)
after the incremental linker reported `Declaration redefinition happened on vTableGcTypes` while
switching local dependency builds. The non-incremental build succeeded and produced the browser
artifacts checked here.

## Validation and remaining work

The full staged editor suite passes 943 tests and the WASM build passes. Six new tests cover actual
RC playback/actions through the full editor at densities 1 and 2, waiting for a save, refusing a wrong revision, and
discarding a late response, and routing the additional interactive pane to the same host.
`VERIFY_REMOTE_DOCUMENT_PREVIEW=true` makes unsupported playback fail rather than skip. The released
dependency floor compiles and passes the four lifecycle/routing tests; its
known empty-Box player defect skips the two density variants of the playback fixture until [rc-players#92](https://github.com/yschimke/rc-players/pull/92)
is selected. The locally staged player passes all six.

Formatting, project boundary checks and golden regeneration pass; regeneration changes no committed
goldens.

This covers the current export subset for saved designs. Local-storage documents still use the
existing browser authoring preview; unsaved source compilation and broader component/operation
coverage remain required. JSON compiler ownership, catalog coverage, independent String state,
nullable/computed values, host callbacks and loop/reusable-component fidelity retain the scope in
the completeness review.
