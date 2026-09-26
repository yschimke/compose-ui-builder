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

**Since then, most of these rows have gone.** The pane with a viewport draws the Wear port's real
`AppScaffold`, `ScreenScaffold` and `TimeText` inside the port's own `MaterialTheme`, so the
content padding is `ScreenScaffoldDefaults.contentPadding`, the colours are Wear's `ColorScheme()`,
the clock is the library's, and the edge-button inset no longer exists. What is left in the
renderer is the diameter range and `WEAR_CARD_CORNER_RADIUS_DP` (Wear's `shapes.large`, as a
number for mobile nodes). The catalog's `frame.geometry.contentPadding` is still declared, and is
still what the diameter range is read from.

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

### 2. The device configuration was gated on a namespace the builder knows — taken

`UiBuilderRenderer.kt` provided the port's `LocalWearDeviceConfiguration` when a design contained a
node whose id started with `wear-m3/` — the fix for a real bug (the browser reported its viewport as
the watch's size), written against the namespace rather than against the fact. A catalog published
under other ids got the viewport behaviour back, and nothing about the composition said so.

**Taken:** the trigger is the platform the *catalog* declares — `LocalUiBuilderCatalogPlatform`,
provided by the editor from `statusSemantics.platform` and compared against
`UiBuilderCatalogPlatform.WEAR.wireValue`. That is the fact the question actually asks ("is this
composition drawn with a watch library?"), it is declared data, and it holds for a board holding one
Wear card as much as for a whole screen.

### 3. The frame *adapter* was a branch on a component id — taken

`wear-m3/screen-scaffold` was special-cased by id in the renderer, so the frame's drawing — the
stadium, the clock, the content insets — was Wear-specific code in a generic renderer, and a catalog
whose screen root was called anything else could not ask for it.

**Taken, in three steps:**

1. **compose-preview-contracts** grew `canvas` on `WasmCapabilityV1`
   ([#87](https://github.com/yschimke/compose-preview-contracts/pull/87), released as 3.3.0). It is
   optional, like every property added since `unrolled`, because the constructor is internal and a
   required one would remove the old `<init>` signature — the break `unrolled` itself caused.
2. **this repository dispatches on the adapter**: the branch is `ROUND_SCREEN_FRAME ->`, and the two
   other id-based special cases (the screen theme, the root's placement) key on the adapter too.
   `WearCatalogIsCompleteTest` counts a component as drawn when the renderer has a branch for its id
   **or for the adapter its catalog declares**, resolving `const val`s in the renderer's source
   because a branch label may be a constant.
3. **the Wear catalog names it**: the synthesised catalog sets `canvas = "frame/round-screen"` on its
   screen root, and the transitional `wear-m3/` id is gone from the renderer.

The property the change is for: a screen root under a name this build has never seen, with
`frame/round-screen` declared for it, is framed **identically** to the Wear one
(`WearCanvasDeviceSizeTest`).

**Still open in the server:** `PublishedUiBuilderCatalog.wasm(...)` reads a *published* catalog's
canvas word to derive `platformSupported`/`adapterStatus`/`notes` and does not put it on the wire, so
a published catalog's adapters reach the editor only once that one line is added — after that
repository bumps its own contracts pin.

**Superseded as an ownership boundary:** adapter dispatch removed the component-id coupling, but
the implementation still links every known catalog into this editor. The renderer-runtime vertical
slice now makes the stronger boundary possible: the catalog builds the Wasm distribution containing
its adapters, and the editor mounts that exact distribution through the sandbox protocol. Adapter
ids remain useful for placeholders and inspection; they are not the executable extension mechanism.
See [`UI_BUILDER_CATALOG_RENDERER_RUNTIME.md`](UI_BUILDER_CATALOG_RENDERER_RUNTIME.md).

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

1. ~~**The frame geometry** (§1)~~ — taken. It closed three exemptions in the equivalence gate,
   which is how the removal was verified.
2. ~~**The device configuration** (§2)~~ — taken, off the platform word the catalog declares.
3. ~~**The frame adapter dispatch** (§3)~~ — taken. **Moving its implementation** is now tracked by
   the catalog-owned renderer runtime design linked above.
4. **The templates** (§4) — a data freeze, not a redesign.
5. **The emitters** (§5) — the contract's hard part, and worth doing last.
