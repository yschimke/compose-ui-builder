# Catalog agnosticism: what this builder still knows about specific catalogs

**Status: audit, 2026-09-19.** The contract
([`UI_BUILDER_CATALOG_CONTRACT.md`](UI_BUILDER_CATALOG_CONTRACT.md)) makes a catalog **data**: it
publishes `ui-builder.policy.json` and a generated `ui-builder.json`, and this builder reads them
instead of knowing anything about the system behind them. Phase 4 has landed the loader —
`PublishedUiBuilderCatalog` composes a catalog it has never heard of, and
`--ui-builder-published-catalogs` serves it — but the *readers* are still Kotlin in places, so a
Wear-shaped catalog served from its own file is not yet **drawn** from its own declarations.

This document is the inventory of what is still baked in, in the order it hurts. Every row names the
file and the contract field that should carry the fact instead. Nothing here is a new decision: the
contract already decided each one, and the differences list in `docs/design/fixtures/` already
records three of them as accepted gaps.

## What is already generic, and worth not re-litigating

| Fact | Where it comes from |
| --- | --- |
| Which components a catalog offers, their slots, properties and traits | the served catalog (`CatalogCapability`), composed from the record + the published file |
| Which canvas drawing a component uses | `statusSemantics` → `LocalUiBuilderCanvasAdapters` |
| Which components the canvas must draw as placeholders | `statusSemantics.componentPacks` → `LocalUiBuilderNativeOnly` |
| The preview-surface claims, the platform word, the colour-token vocabulary, the menu order, the asset registry | `statusSemantics.*`, read by `UiBuilderPreviewSurfaces`, `ComponentMenu`, … |
| Device presets — ids, labels, geometry | the **host** supplies them; `UiBuilderDevicePreset` carries width/height/density and states no geometry of its own |
| A catalog this build has never heard of | `PublishedUiBuilderCatalog` + the lever, proven by `PublishedUiBuilderCatalogTest` |

## The findings

### 1. The screen frame's geometry is in the renderer, and in the catalog it is already declared

`UiBuilderRenderer.kt` holds every constant the contract's `frame.geometry` block describes:

| Constant | Line | Contract field |
| --- | --- | --- |
| `WEAR_CONTENT_PADDING` (192/10/20, 227/12/23, 240/13/24) | `wearScreenContentPadding` | `frame.geometry.contentPadding` |
| `WEAR_SMALL_ROUND_DP` / `WEAR_XL_ROUND_DP` (the accepted diameter range) | `wearScreenWidthDp` | the same table's ends |
| `WEAR_CARD_CORNER_RADIUS_DP` (26f) | `WearDarkColorScheme`'s neighbours | `frame.geometry.cardCornerRadiusDp` |
| `WEAR_SCREEN_BACKGROUND`, `WEAR_SCREEN_TIME_TEXT`, `WEAR_SCREEN_SURFACE_CONTAINER`, `WEAR_SCREEN_ON_SURFACE`, `WEAR_SCREEN_ON_SURFACE_VARIANT` | the colour block | `frame.geometry.colors` |
| `WEAR_TIME_TEXT_CENTRE_DP`, `WEAR_TIME_TEXT_SP` | the clock | `frame.geometry.timeText` |
| `WEAR_EDGE_BUTTON_INSET` | the edge button's placement | `frame.geometry` (a sibling of the above) |

`wear-m3-catalog`'s own `ui-builder.policy.json` already declares the first two rows, written by a
test (`ScreenScaffoldContentPaddingTest` composes the real `ScreenScaffold` at each round size and
asserts the committed file equals its measurement), and
`docs/design/fixtures/ui-builder/wear-m3-differences.json` records the gap in three `frame.*`
exemptions — one of which says outright that the numbers are "transcribed constants in
UiBuilderRenderer rather than in the catalog". So this is a known, measured, tested fact in the
wrong place: the catalog holds it, the builder hardcodes it, and the two are held equal by a human
reading a differences file.

**Correction:** read `statusSemantics.frame` (the published block, or the synthesised catalog's
transcription of it) into a composition local the renderer consumes, exactly as
`LocalUiBuilderCanvasAdapters` is consumed, and delete the three exemptions.

### 2. The frame *adapter* is a branch on a component id

`wear-m3/screen-scaffold` is special-cased by id in the renderer (`WEAR_SCREEN_SCAFFOLD`), and its
stand-in — the stadium, the clock, the content insets — is Wear-specific code in the generic
renderer. The contract puts both facts in the catalog: `frame.adapter: "frame/round-screen"` (a
drawing this build ships) and `builtins["wear-m3/screen-scaffold"]` (the structural role). The
policy file declares both; nothing reads either.

**Correction:** dispatch the frame on the *adapter* the catalog declares, so a catalog whose screen
root has another id gets the same frame, and one that declares a different adapter gets that.

### 3. The device configuration is gated on a namespace the builder knows

`UiBuilderRenderer.kt` provides the port's `LocalWearDeviceConfiguration` when a design contains a
node whose id starts with `wear-m3/` — the fix for a real bug (the browser reported its viewport as
the watch's size), written against the namespace rather than against the fact. A catalog published
under other ids gets the viewport behaviour back, and nothing about the composition would say so.

**Correction:** the trigger is "this composition will call into the port", which follows from the
frame/adapter facts above — the adapter a catalog declares for its screen root, or the set of
adapters this build draws with the port — not from a prefix in the renderer.

### 4. The templates are Kotlin documents in the export module

`UiBuilderTemplates.kt` builds the Wear screen, the Wear widgets and the blank mobile screen as
Kotlin: the rows, the spacing, the measured `fontSizeSp` pins, `wearScreenEnvironment`'s diameter
and density, `mobileScreenEnvironment`'s `pixel_6` frame, and the export-device lists. A template is
a *document*, which is data, and the contract's phase 3a freezes exactly these to
`ui-builder/designs/*.json` with a `template` marker so the catalog that owns the design owns its
seed.

**Correction:** freeze the seed documents onto the delivery branch and have the builder read them,
as it already reads a design.

### 5. The whole-screen emitters are chosen by id

`WearScreenCodeExporter` and `WearWidgetCodeExporter` are whole-screen generators selected by the
component id at the root (`wear-m3/screen-scaffold`, `remote-m3/widget-container-*`), and
`WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS` is a catalog's policy stated in this
repository's code. The contract's `code.templates` block is where a catalog declares the structural
Kotlin it wants generated, with named holes, and the record supplies the rest.

**Correction:** the largest of these, and the one the contract calls out as hard
([§ Structural code cannot be printed from a signature](UI_BUILDER_CATALOG_CONTRACT.md#structural-code-cannot-be-printed-from-a-signature)).
Until then the routing at least belongs in the catalog's `code` block rather than in a `when` on an
id.

### 6. The platform word is stated in Kotlin

`wearM3Catalog` sets `statusSemantics.platform = "wear"`, and `platformLabel` is derived. The
policy file declares both, and the published path reads them — so this is the phase-4 cutover, not
a defect: it is listed because a *synthesised* catalog still answers a question its own file
answers.

### 7. Sanctioned exceptions, recorded so nobody "fixes" them

- **Remote Compose.** `RemoteDocumentPreviewPane`, the `RcComposePlayer` wiring and the
  `remote-compose/*` builtins are a rendering lane for one document format, and the contract says
  so: "Remote Compose is a different kind, and stays one". A catalog that publishes Remote Compose
  content is asking for that lane, not for a generic one.
- **The bundled floor.** `:ui-builder` ships the packaged `m3-catalog` as the floor a design can
  always be authored against, and its ids (`m3/*`, `layout/*`) appear in the editor. That is a
  dependency, not baked-in knowledge of somebody else's catalog — but it is why `m3/*` literals in
  the editor are not all findings.

## Order

1. **The frame geometry** (§1) — measured, already declared by the catalog, already recorded as a
   gap, and the smallest change that removes a duplicated fact. It also closes three exemptions in
   the equivalence gate, which is how the removal gets verified.
2. **The frame adapter** (§2) — the same change's other half, and what makes §3 fall out.
3. **The templates** (§4) — a data freeze, not a redesign.
4. **The emitters** (§5) — the contract's hard part, and worth doing last.
