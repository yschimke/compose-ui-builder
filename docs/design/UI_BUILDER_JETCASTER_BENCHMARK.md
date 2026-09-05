# UI Builder primary benchmark: Jetcaster Discover

**Status:** proposed primary fidelity target

**Source:** [`android/compose-samples@018c5207fb63c4f78e5841bd8ddd4faabdf19d3a`](https://github.com/android/compose-samples/tree/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster)

**License:** Apache-2.0

## Why this replaces Confetti as the primary target

The Confetti Schedule fixture remains a useful small regression test, but it is dominated by one
vertical sequence: app bar, chips, tabs, and schedule rows. A renderer can match it while still
avoiding several hard properties of a production screen.

The primary benchmark is now Jetcaster's **Discover home with a selected podcast in the supporting
pane**. This is one real adaptive screen state from the official Compose samples. It combines:

- an outer `SupportingPaneScaffold` and inner Material 3 `Scaffold`;
- responsive one/two-pane behaviour and an adaptive lazy grid;
- a full-width search bar, loading and snackbar slots;
- category filter chips;
- image-backed, clipped carousel cards with gradients and overlay controls;
- episode content with typography, metadata, images, and queue actions;
- a floating bottom toolbar; and
- a second, independently scrolling podcast-detail pane at expanded width.

Matching this screen cannot be reduced to arranging a decorated list. It requires the builder to
represent real scaffold slots, overlays, clipping, content scaling, adaptive layout, repeated data,
z-order, and two independent scroll regions.

## Pinned source

- [`Home.kt`](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster/mobile/src/main/java/com/example/jetcaster/ui/home/Home.kt)
  owns the supporting-pane scaffold, search app bar, inner scaffold, adaptive grid, background,
  snackbar, and floating toolbar.
- [`Discover.kt`](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster/mobile/src/main/java/com/example/jetcaster/ui/home/discover/Discover.kt)
  owns the category chip row.
- [`PodcastCategory.kt`](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster/mobile/src/main/java/com/example/jetcaster/ui/home/category/PodcastCategory.kt)
  owns the podcast carousel and episode grid items.
- [`EpisodeListItem.kt`](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster/mobile/src/main/java/com/example/jetcaster/ui/shared/EpisodeListItem.kt)
  owns the rich repeated episode cards and actions.
- [`PodcastDetailsScreen.kt`](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster/mobile/src/main/java/com/example/jetcaster/ui/podcast/PodcastDetailsScreen.kt)
  owns the supporting pane.
- [`PreviewData.kt`](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster/core/domain-testing/src/main/java/com/example/jetcaster/core/domain/testing/PreviewData.kt)
  supplies deterministic titles, categories, and episode text.
- [`LICENSE`](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/LICENSE)
  supplies the attribution terms for derived fixture code.
- [`docs/screenshots.png`](https://github.com/android/compose-samples/blob/018c5207fb63c4f78e5841bd8ddd4faabdf19d3a/Jetcaster/docs/screenshots.png)
  is the authoritative visual product reference.

## Fixed scene

- Viewports: expanded `1280 x 800dp` and compact `412 x 800dp`; density/DPR `1`; browser zoom
  `100%`. Both replay the same frozen document. Expanded composes the selected podcast supporting
  pane; compact composes only the main pane and reports the supporting-pane subtree as uncomposed.
- Theme: Jetcaster dark theme, non-dynamic; locale `en-US`; font scale `1.0`.
- Window posture: flat, no hinge.
- Main category: `Crime` selected; categories are `Crime`, `News`, and `Comedy`.
- Selected podcast: `Android Developers Backstage`, shown in the supporting pane.
- Episode: `Episode 140: Lorem ipsum dolor`, using the pinned preview summary and publication time.
- Loading: false; snackbar: hidden; search query: empty.
- Time is fixed for all relative-date labels.
- Animation clocks are disabled or sought to their settled state.
- Network access is disabled during capture. Cover artwork, icons, and fonts are checked-in assets
  with recorded source/license and content hashes.

The official Android rendering is the product reference, not an exact cross-platform pixel oracle.
The exact oracle is a separately built Compose/Wasm port of the pinned source hierarchy using the
same fixed fixture data and assets. It is authored and frozen before the builder fixture, retains
the upstream attribution, shares no renderer/exporter implementation with the builder, and records
the upstream commit plus source/data/asset hashes. Builder, generated-Compose, SVG-raster, and clean
reference captures all use the same browser, fonts, viewport, density, and animation state.

## Required semantic hierarchy

```text
Surface
└─ SupportingPaneScaffold
   ├─ mainPane: HomeScreenBackground
   │  ├─ radial gradient scrim
   │  └─ Scaffold
   │     ├─ topBar: SearchBar
   │     │  └─ InputField(search icon, placeholder, account icon)
   │     ├─ snackbarHost: SnackbarHost
   │     └─ content: LazyVerticalGrid(adaptive 362dp)
   │        ├─ full-width LazyRow
   │        │  └─ FilterChip × 3
   │        ├─ full-width HorizontalUncontainedCarousel
   │        │  └─ podcast card × 2
   │        │     ├─ cover image
   │        │     ├─ follow icon button
   │        │     ├─ gradient overlay
   │        │     └─ title
   │        ├─ EpisodeListItem
   │        │  ├─ episode artwork
   │        │  ├─ title, podcast, date, and summary
   │        │  └─ queue action
   │        └─ floating HorizontalFloatingToolbar
   │           ├─ Library button
   │           └─ Discover button (selected)
   └─ supportingPane: PodcastDetailsScreen
      ├─ podcast artwork and title/header actions
      ├─ description and metadata
      └─ independently scrolling episode list
```

The document must contain those component identities and relationships. A benchmark-only
`JetcasterScreen` component, a full-screen image, or manually persisted pixel coordinates does not
pass.

## Catalog capability gap

Confetti already proves the basic scaffold, app bar, row/column, chip, tab, list item, surface,
divider, text, and icon path. Jetcaster adds the following required capabilities:

- supporting/adaptive pane scaffold with main and supporting slots;
- `SearchBar`/input field and icon-button slots;
- adaptive lazy grid with full-span items;
- carousel semantics, item masking, and stable repeated-item keys;
- image/asset content with crop/content-scale and deterministic loading state;
- gradients, aspect ratio, alignment, match-parent size, z-order, and overlay composition;
- floating toolbar and selected button styling;
- independent scroll state per pane; and
- responsive constraints that generate recognizable Compose code rather than fixed canvas bounds.

The first implementation may model Material 3 experimental components as version-pinned catalog
capabilities. It may not silently substitute a different layout when exporting code or SVG.

## Operation-replay and visual test

The checked-in candidate contracts are:

- [`jetcaster-discover-operations-v1.json`](fixtures/ui-builder/jetcaster-discover-operations-v1.json):
  109 public operations reducing to 108 semantic nodes and canonical document hash
  `dbd6d052f9b766db76aa7541927bacc5b6d993367f66ff05d98383be7be04cdc`.
- [`m3-catalog-capabilities-v1.json`](fixtures/ui-builder/m3-catalog-capabilities-v1.json):
  24 generic component capabilities, including explicit planned/unsupported Wasm states and SVG
  evidence scoped to each authored usage in this frozen benchmark.

The acceptance test starts with an empty design and replays only public operations (the same batch
shape used by MCP):

1. create the design and pin its catalog/environment;
2. insert the two-pane scaffold and main-screen scaffold hierarchy;
3. insert the category row, cards, episode content, toolbar, and supporting-pane content;
4. set deterministic properties/assets/state; and
5. commit the immutable revision used for every export.

The test then produces:

- canonical reduced document and hash;
- clean builder PNG and independent-reference PNG, with a diagnostic diff;
- editor-overlay PNG plus a bound manifest proving the overlay changed no design bounds;
- Figma-compatible SVG rasterized against the same reference; and
- generated Compose rendered in Wasm against the same reference.

Same-browser builder/reference geometry must be exact. A separately declared cross-platform raster
tolerance may cover text antialiasing only; it must not hide layout, clipping, image, or colour
differences. Structural assertions verify component ids, slots, repeated-item order, asset hashes,
and that the exported SVG is not a single full-screen bitmap.

## Delivery order

1. **Reference fixture:** independently authored static Wasm scene, pinned assets, semantics/bounds
   manifest, and committed PNG.
2. **Main pane:** operation fixture and catalog coverage through the search bar, chips, carousel,
   first episode, and floating toolbar.
3. **Adaptive state:** supporting pane and responsive compact/expanded assertions.
4. **Exports:** code compilation/render parity and editable SVG/Figma import parity.

Confetti stays in CI as the fast, no-network compact regression. Jetcaster is the release gate.

### Current implementation status

- The independent direct-Compose Wasm oracle, provenance record, and reviewable PNG are checked in
  under `ui-builder-reference-jetcaster` and `preview-harness/snapshots`.
- The builder replays all 109 public operations and has explicit native dispatch for all 24
  capability ids used by the resulting 108-node document.
- Strict capability validation reports unknown ids/properties/modifiers/slots and keeps planned or
  unsupported Wasm status visible rather than substituting silently.
- The expanded independent-oracle comparison reports `1.498%` differing pixels at threshold
  `0.1`; the compact `412 x 800` comparison reports `1.255%`. Both replay the same frozen
  revision-108 document, and CI enforces an interim ceiling of `2%`. This is a convergence guard,
  not the exact release gate.
- Revision-keyed bounds, text baselines, authored semantics, clean/editor invariance, and compact
  supporting-pane exclusion are executable. Merged accessibility semantics and off-screen lazy
  content remain open.
- Capability-driven code generation covers the 108-node fixture with located diagnostics and now
  compiles as the standalone `ui-builder-generated-jetcaster` CMP/Wasm application. Its independent
  reference comparison differs by `2.003%` expanded and `2.817%` compact at pixelmatch threshold
  `0.1`, with `2.1%` and `3.0%` interim spike ceilings respectively and a committed expanded-render
  golden. These guards are not the exact release gate. Cover art is supplied by the explicit
  `compose-preview-project-owned-jetcaster-artwork/v1` exact-key provenance adapter; the generic exporter emits a
  located diagnostic, declared fallback, and visible placeholder for any unbound asset. The
  checked-in source has a stale-generation verification task. Remaining source gaps are adaptive
  motion, carousel masking/gestures, saved scroll/stable keys, parent semantics, and selected-state
  styling.
- A saved-revision JVM `ComposeScene -> Skia SVGCanvas` bridge exports the full frozen Jetcaster
  document under its checked-in capability catalog. Known `iconKey` assets remain vector paths; the
  four authored images are named embedded-raster fallbacks with measured bounds and payload
  provenance. A real Figma import retains exact 1280x800 root bounds, 37 editable text nodes, 83
  vector nodes, and all four image paints. Its raster differs from the clean Wasm snapshot by
  `5.597%` at threshold `0.1`, so the result is evidence of structural import—not visual parity.
  All lanes now consume the same project-owned 512×512 offline PNG bytes, removing the known
  generated/SVG-versus-Compose artwork source drift. The current deterministic SVG also correlates
  all 37 emitted text fragments to 25 authored nodes and records exact node/token provenance,
  explicit Inter adapter provenance, normal style, and 25 regular/12 medium weights. Focused source
  tests cover regular, medium and bold plus stable bytes. No new real Figma import has measured
  either fix: the fresh private draft is still empty because uploading the repository SVG awaits
  explicit authorization. The previous import's all-Inter-Regular normalization, current Figma
  weight handling, and current raster parity therefore remain open. See
  `docs/design/fixtures/ui-builder/jetcaster-discover-figma-import-v1.json`.
