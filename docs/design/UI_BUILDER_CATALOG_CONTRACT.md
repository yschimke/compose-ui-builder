# The UI builder on a catalog contract

Status: **plan** (2026-09). The successor to
[`UI_BUILDER_ON_THE_COMPONENT_RECORD.md`](UI_BUILDER_ON_THE_COMPONENT_RECORD.md), which generates the
*components* of a catalog from its record. This document is about everything else a catalog tells
the builder — its platform, its shelves, its templates, its screen frame, how its screens are written
and where they are rendered — and about moving that out of this repository's Kotlin and into the
catalogs that own it.

## The problem, in one sentence

The builder serves three catalogs, and two of them are written in Kotlin in this repository rather
than published by the repositories whose components they describe.

`m3-catalog`, `wear-m3` and `remote-m3` are the three "modes" — mobile, Wear, Remote Compose. A
person adding a fourth (a TV catalog, an XR one, an application's own design system) would have to
write, in this repository:

- a value in the `UiBuilderCatalogPlatform` enum, and a `when` branch on it in the New design chooser;
- a `fun fourthCatalog(base): CatalogCapabilityV1` in `ProductionUiBuilderRuntime.kt`, the way
  `wearM3Catalog` and `remoteM3Catalog` are written — ~340 and ~110 lines of authored capabilities,
  shelves, variant properties and slot policy, derived by mutating the packaged Material 3 JSON;
- a screen emitter, if the catalog's screens cannot be written from a component record, the way
  `WearScreenCodeExporter` (1,406 lines) writes a Wear screen and `WearWidgetCodeExporter` a widget;
- a `when` branch routing the emitter by root component id in `RecordFreeExport`;
- canvas knowledge in `UiBuilderRenderer.kt`: the frame shape, the measured content-padding table,
  the colour scheme, which ids draw as stand-ins and which as placeholders;
- starter content, templates, a seed environment (which device a new design opens on);
- an entrypoint default binding the builder catalog to the served catalog that carries its classes
  (`--ui-builder-native-catalog wear-m3=wear-m3-catalog`);
- and the tests that pin each of those as literal sets.

Then a release of this repository, because none of it is data the server reads at runtime.

That is the shape the component-packs work already refused for *components*: "nobody transcribes a
capability table for Confetti; the record already says what its composables take"
([`UI_BUILDER_COMPONENT_PACKS.md`](UI_BUILDER_COMPONENT_PACKS.md)). This plan applies the same
refusal to the catalog.

## What is where today

The inventory, so the rest of the document can point at it. Line references are as of `e0ce003`.

| Knowledge | Lives in | Form |
| --- | --- | --- |
| The three platforms | `ui-builder-export/…/UiBuilderCatalogPlatform.kt:32-62` | a Kotlin enum |
| Which catalogs exist | `ProductionUiBuilderRuntime.kt:95-100, 303-308` | a `mapOf` of three ids and three constants |
| `m3-catalog`'s capability catalog | `docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json`, packaged into `:ui-builder-runtime` | a hand-transcribed Jetcaster catalog, unrelated to `yschimke/m3-catalog` |
| `m3-catalog`'s component record | `docs/design/fixtures/ui-builder/m3-catalog-components-v1.json`, staged into the image | a checked-in copy of a build output |
| `wear-m3`'s capability catalog | `ProductionUiBuilderRuntime.kt:589-659, 881-937, 974-1776` | Kotlin, synthesised from the M3 JSON at startup |
| `remote-m3`'s capability catalog | `ProductionUiBuilderRuntime.kt:546-572, 661-866` | Kotlin, the same way |
| Which emitter writes a screen | `ui-builder-export/…/RecordFreeExport.kt:56-70` | `when` on root component id |
| The Wear screen emitter | `ui-builder-export/…/WearScreenCodeExporter.kt` | 24 id constants, ~45 conditional imports, scaffold/list/overlay structure, state hoisting |
| The Wear widget emitter | `ui-builder-export/…/WearWidgetCodeExporter.kt`, `RemoteContentEmitter.kt:410-473` | the Remote Compose import list and host-frame erasure |
| Templates and seed environments | `ui-builder-export/…/UiBuilderTemplates.kt`, `UiBuilderNewDesignSeed.kt:27-40` | Kotlin builders; `wear-list` is wear-m3-catalog's list, transcribed |
| The Wear frame on the canvas | `ui-builder/…/UiBuilderRenderer.kt:444-480, 1287-1510` | measured geometry (`WEAR_CONTENT_PADDING`, 26dp corner, five sampled colours), copied from a test in wear-m3-catalog |
| Which Wear ids draw as lookalikes | `UiBuilderRenderer.kt:3074-3080` (`wearScreenStandIn`) | a three-line table, pinned by `WearCanvasStandInTest` |
| The widget host frame on the canvas | `UiBuilderRenderer.kt:631-654` | 216×76 / 216×124dp, copied from wear-m3-catalog's preview contract |
| Starter content | `ui-builder/…/StarterContent.kt:116-167, 284-295` | 12 `wear-m3/*` entries beside the M3 ones |
| The New design chooser | `ui-builder/src/wasmJsMain/…/Main.kt:1523-1620` | `when (catalogSystemId)` producing "Mobile" / "Wear" / "RemoteCompose" and each catalog's template list |
| Builder catalog → served catalog | `deploy/image/entrypoint.sh:265` | an operator flag |
| The record ids the variant table names | `ui-builder-export/…/export/ScreenDocumentProjection.kt:1929-1951` | 16 `m3-catalog/androidx.compose.material3.…` literals |
| The MCP export description | `mcp/…/UiBuilderMcpAdapter.kt:82-85` | "Jetpack Compose for a Material 3 screen, Remote Compose for a Wear widget, Wear Compose for a Wear screen" |

Against that, what is already **declared by the catalog and read generically** — the pattern this
plan extends rather than invents:

| Declared | Read by | Carried in |
| --- | --- | --- |
| `platform` | the chooser's grouping, the pack merge | `statusSemantics.platform` |
| `previewSurfaces` (which renderer is honest, which daemon) | the editor's Preview, the native lane's backend choice | `statusSemantics.previewSurfaces` |
| `componentMenu` (shelves, variant properties) | the insert panel | `statusSemantics.componentMenu` |
| `componentPacks` | the palette, the export | `statusSemantics.componentPacks` |
| the component record | packs, the record-driven export, the native lane | `components.json`, declared as `componentsFile` on `catalog.json` |
| designs | the design library | `ui-builder/designs/index.json` on the delivery branch |
| device presets | the frame menu | `DeviceDimensions`, the render lane's own catalog |

Every row of the second table is a fact the catalog states once and two surfaces read. Every row of
the first is a fact restated in Kotlin here, for one catalog, by hand. The plan is to move rows from
the first table to the second until the first is empty.

And what the catalog repositories publish today, which is the substrate:

- **`yschimke/m3-catalog`** and **`yschimke/wear-m3-catalog`** are Gradle projects applying the
  published `ee.schimke.composeai.preview` plugin. Their inventory is `@CatalogGroup` /
  `@CatalogComponent` / `@CatalogVariant` annotations beside the `@Preview`s; `catalog.spec.json` is
  a cover sheet (`system`, `title`, `library[]`, `modes[]`, `breakpoints[]`, `display`). The
  design-artifacts pipeline in compose-ai-tools renders the previews and publishes
  `catalog.json`, `components.json`, the images and the live bundle to `design-artifacts/<system>`.
- **wear-m3-catalog's `:remote-catalog` module publishes the `remote-m3` system** — the same id the
  builder's `remote-m3` adapter authors against — and its `ScreenScaffoldContentPaddingTest` measures,
  under Robolectric, the padding table the canvas here carries as constants. The KDoc on that test
  says it exists so the builder's copy fails loudly when it goes stale. The number is measured in the
  right place and published in the wrong one.
- **Neither publishes anything the builder reads as a catalog.** `catalog.json` has no builder
  section; `components.json` describes composables, not shelves or templates or frames.

## The decision

**A builder catalog is a published artifact of the catalog repository, read by this server over the
same route as `components.json`.** The server ships a host for catalogs and one packaged fallback;
it synthesises none. Concretely:

1. **The catalog declares itself in one file, `ui-builder.json`**, published beside `catalog.json` and
   `components.json` on the delivery branch and named on `catalog.json` as `uiBuilderFile`, the way
   `componentsFile` and `tokensFile` are. It is *generated* by the design-artifacts pipeline from three
   inputs the catalog repository owns: the component record, the cover sheet, and an authored
   policy file. Nothing in it is typed twice.
2. **A platform is a word, not an enum.** Compatibility is declared equality: a pack lands in a
   catalog whose `platform` matches its own; the chooser groups by the word and labels the group with
   the catalog's own `platformLabel`. `UiBuilderCatalogPlatform` becomes a value class over a string
   with no fixed members, and `--ui-builder-packs` validates a platform against the enabled catalogs
   rather than against a list.
3. **A screen is written by one generic emitter driven by the record plus catalog-authored structural
   templates.** The Wear screen emitter is not deleted; it is decomposed into the handful of
   *structural roles* it actually knows (the screen root, the scrolling list, the list-item wrapper,
   an overlay sibling, a controlled-state hoist, the preview annotations) and those become templates
   the catalog publishes. Leaf call sites come from the record, as they already do for packs.
4. **The canvas draws a component through an adapter the catalog names, or as a placeholder.** The
   `when (componentId)` in `UiBuilderRenderer.kt` becomes a registry keyed by adapter id
   (`material3/Text`, `foundation/Column`, `frame/round-screen`, `frame/widget-host`,
   `placeholder`), and the catalog maps each component id to one. `wear-m3/text → material3/Text` is
   then a statement the Wear catalog makes about its own stand-in, not a table here.
5. **Frame geometry is data the catalog publishes from the test that measures it.** The content
   padding table, the corner radius, the colours, the host-frame sizes: emitted by wear-m3-catalog's
   own Robolectric probe into `ui-builder.policy.json`, checked in there, published in
   `ui-builder.json`, read by the round-screen frame adapter. A Wear Compose bump changes the number
   where the number is measured.
6. **A builder catalog is a served catalog.** The id the builder authors against is the id the server
   serves; the classes the native lane compiles against are that catalog's own bundle.
   `--ui-builder-native-catalog` becomes unnecessary for a catalog that publishes `ui-builder.json`,
   and stays as an override for one that does not.
7. **The packaged `m3-catalog` becomes the packaged fallback and nothing more.** A standalone builder
   with no served catalogs still opens on it. The deployed `m3-catalog` reads what
   `yschimke/m3-catalog` publishes, generated from a record that describes the fifty-nine components
   it actually renders rather than a Jetcaster transcription of twenty-five.

What is *not* decided here: the record's own shape (owned upstream by
[`COMPONENT_RECORD.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/COMPONENT_RECORD.md)),
the design document format, the pin, or the Remote Compose lane's decision that
`remote-compose/document` is a typed embed rather than a component
([`UI_BUILDER_REMOTE_COMPOSE.md`](UI_BUILDER_REMOTE_COMPOSE.md)).

## The contract

Three files, three owners, one direction of dependency
([`REPOSITORY_LAYERS.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/REPOSITORY_LAYERS.md)):

```text
catalog repo        ui-builder.policy.json   authored: what no signature can say
                    catalog.spec.json        the cover sheet (exists)
                    @CatalogComponent…       the inventory (exists)
                    a Robolectric probe      measured frame geometry → written into the policy
        │
        ▼  ./gradlew composePreviewDiscover + design-artifacts pipeline   (compose-ai-tools, layer 1)
        │
delivery branch     catalog.json             + "uiBuilderFile": "ui-builder.json"
                    components.json          the record (exists)
                    ui-builder.json          the builder catalog: generated, never edited
                    ui-builder/designs/      templates and project designs (exists)
        │
        ▼  ServeCatalogStore fetches it like the record            (compose-preview-server, layer 2)
        │
the builder         one loader, one platform word, one emitter, one adapter registry
```

### What the catalog authors, and in which of two places

Both catalog repositories are **annotation-first by rule**: the inventory is `@CatalogGroup` /
`@CatalogComponent` / `@CatalogVariant` beside the `@Preview`s, and `catalog.spec.json` is a cover
sheet that carries only what is not per-component code metadata. The contract keeps that split
rather than introducing a second inventory in JSON:

- **Per-component policy is an annotation on the sticker**, `@BuilderComponent` in
  `preview-annotations`, discovered by the same ClassGraph scan that reads `@CatalogComponent` and
  attached to the record entry the preview's target inference already binds it to. It carries what
  the record cannot say about that component: its menu group where `@CatalogGroup`'s section is not
  it, its variant property, starter content, which callbacks are state-updating (the
  `stateCallback` role of the record plan), which slots accept which roles and traits, and how the
  canvas may draw it.
- **Catalog-level policy is a file**, `ui-builder.policy.json` beside `catalog.spec.json`: the
  platform word and label, the preview-surface claims, the frame, the structural code templates and
  the template designs. The precedent is `compose-usage.json` in m3-catalog — a catalog-authored,
  server-facing file the playground already reads — and the reason it is a file rather than an
  annotation is that none of it belongs to a component.

The record already carries everything a signature can say; the annotations and the file are the
residue. `UI_BUILDER_ON_THE_COMPONENT_RECORD.md` §1 lists the per-component part (roles, editor
bounds, slot value parameters); this plan adds the catalog-level part.

```kotlin
@CatalogComponent(id = "Toggles/CheckboxButton", reference = "figma:…")
@BuilderComponent(
  id = "wear-m3/checkbox-button",           // the builder id; defaults to a slug of the catalog id
  canvas = "placeholder",                   // an adapter this build ships, or "placeholder"
  stateCallbacks = ["onCheckedChange=checked:boolean"],
  starter = ["label=Checkbox"],
)
@CatalogModes @Composable fun CheckboxButtonSticker() = Sticker { CheckboxButton(…) }
```

```jsonc
// ui-builder.policy.json — the catalog, not its components
{
  "schema": "compose-ui-builder-policy/v1",
  "platform": "wear",                       // a word; equality is compatibility
  "platformLabel": "Wear",                  // what the chooser prints over the group
  "previewSurfaces": {                      // exactly today's statusSemantics block
    "wasm":   { "fidelity": "approximate", "reason": "…" },
    "native": { "fidelity": "authoritative", "backend": "android" }
  },
  "frame": {
    "adapter": "frame/round-screen",        // a canvas adapter this build ships
    "seedDevice": "id:wearos_small_round",  // defaults to the first breakpoint's device in catalog.spec.json
    "geometry": {                           // written by the probe, never by hand
      "contentPadding": [ {"screenDp":192,"horizontalDp":10,"verticalDp":20}, … ],
      "cardCornerRadiusDp": 26,
      "colors": { "background": "#000000", "cardSurface": "#332E3C", "title": "#F6EDFF", … },
      "timeText": { "topDp": 5.5, "frozen": "10:10" }
    }
  },
  "builtins": {                             // components no record can carry
    "wear-m3/screen-scaffold": { "role": "screen-root",
      "slots": { "content": {…}, "edgeButton": {"acceptedTraits":["Action"]}, "overlays": {"acceptedTraits":["Overlay"]} } },
    "wear-m3/transforming-lazy-column": { "role": "list" }
  },
  "menu": { "groupOrder": ["Screens", "Layout", "Lists", "Actions", …] },
  "code": {
    "imports": [ … ],                       // always-on; per-component imports come from the record
    "templates": {                          // structural roles: Kotlin with named holes
      "screen-root": "AppScaffold {\n  ScreenScaffold(scrollState = ${listState}, ${edgeButton}) {\n    ${content}\n  }\n  ${overlays}\n}",
      "list":        "TransformingLazyColumn(state = ${listState}, contentPadding = ${contentPadding}) {\n  ${items}\n}",
      "list-item":   "item {\n  ${call(modifier = \"Modifier.transformedHeight(this, spec)\", transformation = \"SurfaceTransformation(spec)\")}\n}",
      "overlay":     "${call(visible = ${state})}",
      "controlled":  "var ${name} by remember { mutableStateOf(${initial}) }",
      "previews":    "@WearPreviewDevices\n@Composable\nfun ${name}Preview() { ${name}() }\n…"
    }
  },
  "templates": ["ui-builder/designs/wear-list.json", "ui-builder/designs/blank.json"]
}
```

The structural templates are not new writing for wear-m3-catalog. Its own sticker frames —
`ScreenSticker` (`AppScaffold` + frozen `TimeText`), `EdgeButtonScreen` (`AppScaffold` →
`ScreenScaffold(scrollState, edgeButton)` → `TransformingLazyColumn`) in that repository's
`CatalogTheme.kt` — are the same structure the builder's Wear emitter reproduces here. The templates
are those frames with holes where the sticker has content.

Three rules keep this from becoming a second hand-written catalog:

- **Every `@BuilderComponent` must sit on a preview whose target the record resolved**, and the
  pipeline reports every record component with no builder annotation. The honest shelf of
  `UI_BUILDER_COMPONENT_PACKS.md` is the default; annotations widen or narrow it deliberately.
- **A builtin is the only kind of component the file may declare**, and a builtin must name a
  structural role. A component with a call site belongs in the record.
- **Geometry blocks are written by a test, not a person.** The Robolectric probe that measures them
  asserts the committed file equals its measurement, the way this repository's goldens work, so the
  numbers cannot be edited by hand without the test saying so.

### `ui-builder.json` — what the pipeline generates

The full `CatalogCapabilityV1` the runtime loads today, produced by one function in the
design-artifacts pipeline from the three inputs, with the generation rules of
`UI_BUILDER_ON_THE_COMPONENT_RECORD.md` §1 (parameter roles, `jsonType` from the Kotlin type,
`allowedValues` from enum constants, `code.symbol`/`code.imports` from the record's callable) and the
policy layered on top. `ComponentRecordPacks.derive` in this repository is the existing prototype of
that function for the no-policy case; it moves upstream into the pipeline, where the catalog is
built, and this repository keeps only the pack case that has to run at server startup against a
record with no policy.

Until compose-preview-contracts can carry the fields, `platform`, `previewSurfaces`, `componentMenu`,
`frame`, `code` and `templates` ride in `statusSemantics` exactly as the first three do now. Moving
them into typed fields on `CatalogCapabilityV1` is a contracts change and the right one, sequenced
below; it is not a prerequisite, because every reader already reads `statusSemantics`.

### What the server reads

`ServeCatalogStore` stages `ui-builder.json` beside `components.json` on every load and offers it
ahead of the load through the same `fetchComponentRecord`-shaped call, because a builder catalog is a
startup fact and catalogs load for minutes. `ProductionUiBuilderRuntime` takes a
`List<CatalogCapabilityV1>` and validates them; it constructs none. The runtime's authoring
allowlist stays exactly `--ui-builder-catalogs` — operator policy, per §4 of the component-record
plan — and a catalog on the allowlist that publishes no `ui-builder.json` is refused by name at
startup, with the packaged `m3-catalog` as the one exception the fallback exists for.

## The hard parts, stated honestly

### The canvas cannot draw what it cannot link, and this does not change that

The rule in [`UI_BUILDER_WEAR_SCREEN.md`](UI_BUILDER_WEAR_SCREEN.md#the-line-a-component-is-never-faked-so-it-can-run-in-wasm)
stands word for word: no component is hand-assembled in Wasm to stand in for a library the canvas
cannot link. What moves is the *mapping* (which id draws through which adapter, or as a placeholder)
and the *frame geometry*, and both become claims the catalog makes and can check. The adapters
themselves — the Material 3 and foundation drawing, the round-screen and widget-host frames, the
placeholder — stay compiled into `:ui-builder`, because Wasm draws only what it was built with.

The objection the rule was written against was a replica "maintained here, against an upstream
nobody in this repository compiles". A frame whose padding table is emitted by wear-m3-catalog's
Robolectric probe is the opposite: measured where the upstream *is* compiled, and stale only when
that repository's own test fails. The line moves from "never a number" to "never a number this
repository made up".

What a catalog may ask of the canvas is therefore fixed by the adapter registry this build ships, and
a catalog asking for an adapter the build lacks gets a placeholder and a startup log line naming
it. A fourth platform whose frame is neither a rectangle, a round screen nor a widget host needs a
frame adapter here, once — that is the residual per-platform cost, and it is one adapter, not a
catalog.

### Structural code cannot be printed from a signature

`WearScreenCodeExporter` exists because `ScreenScaffold` takes a scroll state that has to be the same
object the `TransformingLazyColumn` inside it holds, because `SurfaceTransformation(spec)` is
declared on `TransformingLazyColumnItemScope` and can only be written inside `item { }`, because a
dialog is a sibling of the scaffold rather than a child, and because a `CheckboxButton` without a
hoisted `remember` is a picture of a checkbox. None of that is in a signature and the record plan says
so ("`ScreenScaffold` takes a scroll state that has to agree with the list inside its content lambda,
which `ScreenGenerator`'s call-site emitter cannot write from a record").

Two ways to move it out of this repository were weighed:

| | Templates as data | An emitter as a published jar |
| --- | --- | --- |
| What the catalog ships | Kotlin fragments with named holes, in the policy file | a JVM module implementing an SPI from `:ui-builder-export` |
| Who runs it | one template engine here, in the browser and on the server | the server JVM, loading a third-party class |
| Expressiveness | the six structural roles above; anything else is a record call site | unbounded |
| Version skew | a schema version on the policy | a binary API on the export module, pinned per catalog |
| Trust | data | code on the server's own classpath, from a repository the operator allowlisted |
| Runs in Wasm | yes — the Code pane keeps working | no; the Code pane would round-trip through the server |

**Templates.** The Wear emitter's 1,406 lines decompose into six roles plus record call sites, and
the Remote Compose emitter into the same six under a different vocabulary; what is left over is
per-component and belongs to the record's authored overrides (`state`, the modifier and
transformation parameters the row treatment reaches through). An emitter jar would let a catalog
write anything, which is exactly the property that makes it the wrong tool: the point of the contract
is that the builder can *validate* what a catalog asks for, and the compile+render round trip is the
validation. If a fifth platform needs a seventh role, the role is added to the engine once, with a
test, rather than every catalog shipping a compiler.

The template engine is the one new piece of code in this repository that this plan creates rather
than deletes. It is a hole-filler, not a language: `${name}` substitution, `${call(...)}` for a
record call site with named argument overrides, `${children}`/`${items}` for a slot's nodes each
wrapped in the role their parent declares for them. No conditionals — a template that would need one
is two roles.

### Remote Compose is a different kind, and stays one

`remote-m3` is a widget body played rather than composed. Under this contract it is an ordinary
catalog with `platform: "remote-compose"`, a `frame/widget-host` adapter whose two sizes it
publishes, structural templates for the creation DSL instead of Compose, and the same typed-embed
admission rule §4 of the record plan already grants it. The one thing it keeps that no other catalog
has is the vocabulary switch and `remote-compose/document`, which stay builtin components of the
builder ([`UI_BUILDER_REMOTE_COMPOSE.md`](UI_BUILDER_REMOTE_COMPOSE.md)) rather than anything a
catalog declares. wear-m3-catalog's `:remote-catalog` already publishes the `remote-m3` system; it
grows a policy file and nothing else.

### `m3-catalog` is two things called one name, and this is where that ends

The packaged catalog is a Jetcaster transcription; the served one is the fifty-nine-component
reference. The record plan's step 1 renames the resource with a compatibility alias; this plan is
where the served `m3-catalog` stops reading it at all. The variant table in
`ScreenDocumentProjection.kt:1929-1951` — sixteen `m3-catalog/androidx.compose.material3.…` record
ids — is the one place the *export* names that catalog, and it becomes the `variantProperty` /
`variants` entries of `m3-catalog`'s policy, read from the catalog rather than compiled in.

## Sequence

Each phase is releasable on its own and leaves every catalog working. The proof at the end is
mechanical: **a fourth catalog reaches the builder by publishing files, with no change to this
repository.**

### Phase 0 — this repository: load, don't synthesise (no behaviour change)

1. **Freeze the synthesised catalogs as JSON.** A test writes `wearM3Catalog(base)` and
   `remoteM3Catalog(base)` to `docs/design/fixtures/ui-builder/wear-m3-capabilities-v1.json` and
   `remote-m3-capabilities-v1.json` and asserts the checked-in files match. The runtime loads all
   three from resources. The Kotlin generators stay only as the thing the goldens are checked
   against, and are deleted in phase 4. `WearM3ScreenCatalogTest` and `WearWidgetContainerCatalogTest`
   read the JSON.
2. **Platform becomes a word.** `UiBuilderCatalogPlatform(wireValue, label)` over a string;
   `label` read from `statusSemantics.platformLabel` with the three known words as fallback labels
   for one release. The chooser's `when` in `Main.kt:1529-1620` becomes: group the enabled catalogs
   by platform word, label each group by its catalogs' label, list each catalog's declared
   templates. `--ui-builder-packs` validates the word against the enabled catalogs.
3. **Templates become documents.** `wearScreenUiBuilderDocument`, the widget samples and the blank
   seeds are serialised to `ui-builder/designs/*.json` fixtures with a `template` marker and the
   seed device in `environment`; `UiBuilderNewDesignSeed` looks a template up by id in the
   catalog's declared list. The Kotlin builders become the golden generators, then go.
4. **The emitter is chosen by declaration.** `RecordFreeExport` routes by
   `statusSemantics.code.strategy` (`record` | `templates`) rather than by root component id; the
   Wear and widget emitters register under `templates` with their catalog's id for now.
5. **The canvas reads a mapping.** `wearScreenStandIn`, the `WEAR_NATIVE_ONLY` set, the root
   alignment rule and the frame choice become lookups on `statusSemantics.components[id].canvas` and
   `statusSemantics.frame`; the geometry constants move into the frozen `wear-m3` JSON. The adapter
   registry is the existing `when`, keyed by adapter id instead of component id.
   `WearCanvasStandInTest` pins that the frozen JSON names exactly three `material3/*` stand-ins.
6. **A grep gate.** `.github/scripts/ui-builder-catalog-literals.sh` fails a pull request that
   introduces `wear-m3`, `remote-m3` or `wear-m3-catalog` into main sources of `:ui-builder`,
   `:ui-builder-export`, `:ui-builder-runtime` or `:server` outside an allowlist that shrinks every
   phase and is empty at phase 4. The same shape as `ui-builder-project-boundary.sh`, for the same
   reason: an undrawn line rots.

### Phase 1 — compose-ai-tools: the pipeline publishes a builder catalog

7. **Annotation, schema and generator.** `@BuilderComponent` in `preview-annotations`, read by
   `PreviewDiscovery` into the record's authored fields; `scripts/design-artifacts/ui-builder.policy.schema.json`;
   `generate-ui-builder-catalog.mjs` (record + cover sheet + policy → `ui-builder.json`), wired into
   the reusable design-artifacts workflow beside `catalog-component-record.mjs`; `catalog.json`
   gains `uiBuilderFile`. The generation rules are the record plan's, and `ComponentRecordPacks`'s
   exclusion reasons are the no-annotation default. The catalog repositories change nothing for
   this step: the reusable workflow is theirs by `uses:`, and a catalog with no policy publishes no
   builder file.
8. **The structural template engine**, in `screen/generator` beside `ScreenGenerator`, so the browser
   and the server share it the way they share the call-site printer. Proven by a functional test that
   renders the frozen `wear-m3` templates over the `wear-list` document and compiles the result
   against real Wear Compose — `samples/design-catalog-wear-m3` already compiles that exact output.
9. **Contracts.** `platform`, `platformLabel`, `previewSurfaces`, `frame`, `code` and `templates` as
   typed fields on `CatalogCapabilityV1` in compose-preview-contracts, with `statusSemantics` read as
   the fallback for one contracts major. Not a prerequisite for phases 2–4.

### Phase 2 — wear-m3-catalog: the Wear and Remote Compose catalogs come home

10. `@BuilderComponent` on the `:catalog` stickers and `ui-builder.policy.json` beside the cover
    sheet (`wear`) — the frozen `wear-m3` JSON of phase 0 is the starting point, reduced to what the
    record cannot say. Shelves come from `@CatalogGroup` sections already; what is authored is the
    frame, the stand-in mapping, the structural templates, starter content, controlled-state
    declarations and slot policy. Both catalogs also bump to one compose-ai-tools train first:
    m3-catalog is on 1.85.0 and wear-m3-catalog on 2.0.0, and the annotation ships in one of them.
11. `ScreenScaffoldContentPaddingTest` writes the `frame.geometry` block and asserts the committed
    policy matches. The canvas here stops carrying the numbers.
12. `ui-builder.policy.json` for `:remote-catalog` (`remote-compose`): the widget host frame, the
    reviewed subset, the Lottie element, the creation-DSL templates.
13. A round-trip test in that repository: every template design → `ui-builder.json` → generated
    Kotlin → compiles against the module's own classpath → renders on Robolectric. It consumes the
    published `ui-builder-export` and `screen-model` coordinates, which the layer rule allows (a leaf
    depends down). `samples/design-catalog-wear-m3` in compose-ai-tools becomes redundant and is
    retired.

### Phase 3 — m3-catalog: the served Material 3 catalog describes itself

14. `@BuilderComponent` on the `:catalog` stickers and `ui-builder.policy.json` (`mobile`): the
    variant table, editor bounds and colour suggestions from
    `CapabilityCatalogParser.EDITOR_OVERRIDES`, starter content, `frame/rect` with
    `seedDevice: id:pixel_6`, and the three non-composable builtins (`shape/*`, `asset/image`)
    declared as such. The generated `ui-builder.json` is diffed against the packaged Jetcaster catalog once,
    as the record plan's phase 2 asks, and the differences are reviewed rather than reconciled.
15. The deployed `m3-catalog` switches to the published file; the packaged JSON stays as the
    standalone fallback under its compatibility name.

### Phase 4 — this repository: delete

16. `wearM3Catalog`, `remoteM3Catalog`, `wearComponentMenu`, `wearNativeOnlyComponents`,
    `WearScreenCodeExporter`, `WearWidgetCodeExporter`'s structural half, the Wear entries in
    `StarterContent`, the Wear constants in `UiBuilderRenderer`, the frozen fixtures, and
    `--ui-builder-native-catalog` as a required flag. The entrypoint's `SERVE_UI_BUILDER_CATALOGS`
    default stays as the allowlist.
17. The grep gate's allowlist is empty.
18. **The proof.** A fourth catalog — Confetti's Wear module, or a TV catalog under `platform: "tv"`
    with `frame/rect` — publishes a policy and appears in the chooser under its own label, with its
    own templates, exporting and rendering natively, against a server binary that has never heard of
    it. That is the acceptance test for the whole plan, and it is a deployment, not a unit test.

## Success criteria

- No catalog id and no platform word appears in main sources of the builder or the server, other
  than the packaged fallback's, and CI says so.
- A catalog's frame geometry lives beside the test that measures it, and nowhere else.
- The New design chooser, the palette, the Code pane, the export and the native lane read one file
  per catalog, and that file is generated from inputs the catalog repository owns.
- The three existing catalogs render, export and round-trip byte-identically across phase 0 and
  within a dp across phases 2–3 (the geometry is the same numbers, moved).
- Adding a platform costs at most one frame adapter here; adding a catalog costs nothing here.

## What this deliberately does not do

- **It does not fake anything new on the canvas.** The Wasm adapter set is unchanged; the catalog
  chooses among what exists or gets a placeholder.
- **It does not let a catalog ship code the server executes.** Policy is data; the only code that
  runs is the generated Kotlin, in the daemons, as today.
- **It does not change the design document, the pin, or the record.** A design pinned to a catalog
  revision keeps meaning what it meant; `ui-builder.json` is versioned by the same revision the pin
  names, which is also the answer to the pack plan's follow-up 3 for catalogs, if not yet for packs.
- **It does not move the Material 3 canvas adapters out of this repository.** They are the one
  place a catalog's components are genuinely drawn, and Wasm can only draw what it links.

## Risks and open questions

- **Template expressiveness.** Six roles cover Wear screens and widgets today. The first catalog
  that needs a seventh decides whether the engine grows a role or the catalog is refused; the
  answer should be a role, and the test that the role is general is that two catalogs use it.
- **Startup ordering.** `ui-builder.json` is a startup fact and catalogs load in the background; the
  ahead-of-load fetch exists for the record and is reused, but a catalog whose delivery branch is
  unreachable at startup now costs a builder catalog rather than a shelf. Refuse by name, keep the
  last staged copy, and say which.
- **Revision drift.** A design authored against `ui-builder.json@rev1` opened after the catalog
  republishes reads `rev2`. The pin exists; what does not exist is retention of old generations for
  the builder file, the same gap `ComponentRecordSource` records for the record. Out of scope here,
  named so it is not discovered.
- **Two contract versions.** compose-ai-tools pins contracts 2.5.0 and this repository 2.9.0. Phase
  9 lands in contracts first and both consumers bump; until then `statusSemantics` carries the
  fields, which is why phase 9 is not on the critical path.
- **The `remote-m3` id.** The builder's `remote-m3` and wear-m3-catalog's published `remote-m3` are
  the same id by convention today; under this plan they are the same catalog by construction. A
  deployment serving the system under a different id would need `--ui-builder-native-catalog`
  spelled the old way, which is why the flag stays as an override.
