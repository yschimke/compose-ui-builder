# Capability-generated Jetcaster Compose/Wasm fixture

This module compiles the readable Compose source generated from the frozen 99-node Jetcaster
document and its pinned capability catalog. `:ui-builder:generateJetcasterComposeFixture` replays
the public operations and overwrites `JetcasterDiscoverExpanded.kt` using only
`CapabilityComposeCodeExporter`; the host `Main.kt` supplies the Wasm viewport and pinned dark
theme, not screen content.

Run:

```shell
../gradlew :ui-builder-generated-jetcaster:wasmFrontendDist
```

The Playwright Jetcaster harness compares the compiled result with the independently built
Compose/Wasm reference at expanded 1280 × 800 and compact 412 × 800. The current same-browser
mismatches are 2.003% expanded and 2.817% compact at pixelmatch threshold 0.1, with interim spike
ceilings of 2.1% and 3.0% respectively. These are convergence guards, not the exact product release
gate. The expanded render also has a committed generated-render golden.

Remaining located export gaps are retained as `TODO[...]` comments in the generated source. The
material gaps are adaptive posture/motion beyond the two tested widths, Material carousel masking
and gestures, saved scroll state/stable item keys, parent-authored accessibility descriptions,
selected toolbar/follow visual states, and a few document events. The generator supplies the
explicit `compose-preview-project-owned-jetcaster-artwork/v1` provenance adapter, mapping the two
exact catalog asset keys to the checked-in `:ui-builder-artwork` raster resources. The generic
exporter contains no Jetcaster key matching or benchmark-specific painted fallback.
Missing bindings produce a located `ASSET_BINDING_REQUIRED` diagnostic, a declared
`asset-placeholder:<key>` fallback, and a visible magenta placeholder. No opaque screen component
or full-screen bitmap is used.

`../gradlew :ui-builder:checkJetcasterComposeFixture` regenerates and formats an isolated copy under
`build/`, then byte-compares it with the checked-in source. The verification task never rewrites
the source tree, and the generated header records the deterministic raw-export SHA-256.
