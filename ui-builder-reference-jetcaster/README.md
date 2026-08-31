# Jetcaster Discover Compose/Wasm reference

This standalone module is the independent pixel oracle for the UI Builder Jetcaster Discover
benchmark. It directly composes the fixed density-1 dark scene at `1280 × 800dp` as an expanded
two-pane layout and at `412 × 800dp` as the compact main-pane layout. It neither depends on nor
imports the `ui-builder` module, its reducer, renderer, operation fixture, or code exporter.

The reference is independently authored from the screen hierarchy and deterministic labels in
Android's official Jetcaster sample, pinned at
[`android/compose-samples@018c5207fb63c4f78e5841bd8ddd4faabdf19d3a`](https://github.com/android/compose-samples/tree/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster).
That upstream work is Copyright 2020–2025 The Android Open Source Project and licensed under the
[Apache License 2.0](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/LICENSE).

No upstream artwork is copied. The two documented asset keys resolve through the shared
`:ui-builder-artwork` module to original, project-owned offline raster assets:

- `jetcaster.cover.android-developers-backstage`
- `jetcaster.cover.google-developers-podcast`

The artwork manifest records its source variants, license scope, and encoded/decoded pixel hashes.
The same attribution and capture metadata are shipped as `provenance.json` and `NOTICE.txt` in the
Wasm distribution. At runtime the page sets
`document.documentElement.dataset.uiBuilderReferenceJetcasterReady` and publishes
`globalThis.__uiBuilderReferenceJetcaster` for capture tooling.

The distribution copies the repository's pinned `js-joda.esm.js` runtime compatibility asset. It
does not depend on the `ui-builder` project or any of its implementation classes; its only project
dependency is the artwork resource module shared by all benchmark lanes.

## Standalone build

From this directory:

```shell
../gradlew --settings-file settings.gradle.kts wasmFrontendDist
```

The static output is written to `build/wasmDist`. Serve that directory over HTTP and capture it at
exactly `1280 × 800` or `412 × 800` CSS pixels with browser zoom 100% and device scale factor 1.

The repository root includes this module for formatting, checks, and visual-harness builds. It has
no project dependency on `:ui-builder` and remains independently buildable with its standalone
settings file.
