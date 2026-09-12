# The UI builder on a catalog contract

Status: **plan** (2026-09; sequence revised 2026-09-08). The successor to
[`UI_BUILDER_ON_THE_COMPONENT_RECORD.md`](UI_BUILDER_ON_THE_COMPONENT_RECORD.md), which generates the
*components* of a catalog from its record. This document is about everything else a catalog tells
the builder — its platform, its shelves, its templates, its screen frame, how its screens are written
and where they are rendered — and about moving that out of this repository's Kotlin and into the
catalogs that own it.

The 2026-09-08 revision changed two things and no decisions: the phases are ordered so the **catalog
repositories publish before this repository reads** ([Sequence](#sequence)), and the generator moved
from the design-artifacts pipeline into the Gradle plugin's discovery task so a builder catalog is
reachable by every consumer rather than only by a deployment reading a delivery branch
([One artifact, many builders](#one-artifact-many-builders)).

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
   `componentsFile` and `tokensFile` are. It is *generated* by the Gradle plugin's discovery task from
   three inputs the catalog repository owns — the component record, the cover sheet, and an authored
   policy file — and the design-artifacts pipeline publishes it the way it already publishes the
   record. Nothing in it is typed twice, and the same file reaches a local
   `compose-preview-server ui` with no delivery branch involved.
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
        ▼  ./gradlew composePreviewDiscover                               (compose-ai-tools, layer 1)
        │      writes build/compose-previews/ui-builder.json — also what `ui` reads locally
        ▼  design-artifacts pipeline: publish + stamp uiBuilderFile
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

### `ui-builder.json` — what the generator produces

The full `CatalogCapabilityV1` the runtime loads today, produced by one function in the Gradle
plugin's discovery task from the three inputs, with the generation rules of
`UI_BUILDER_ON_THE_COMPONENT_RECORD.md` §1 (parameter roles, `jsonType` from the Kotlin type,
`allowedValues` from enum constants, `code.symbol`/`code.imports` from the record's callable) and the
policy layered on top. `ComponentRecordPacks.derive` in this repository is the existing prototype of
that function for the no-policy case; it moves upstream into discovery, where the catalog is built
and where a local `compose-preview-server ui` can reach it without a delivery branch, and this
repository keeps only the pack case that has to run at server startup against a record with no
policy.

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
startup, with the packaged `m3-catalog` as the one exception the fallback exists for. That refusal
is the *end* state; through the cutover a catalog with no published file falls back to the frozen
golden and a startup line says which source it came from, so the migration is reversible per
catalog.

### One artifact, many builders

`components.json` was designed for one reader and has four, and `ui-builder.json` will have the
same four from the day it exists. Naming them changes what the file may contain and where it has to
be generated:

| Consumer | How it reaches the file | Which build reads it |
| --- | --- | --- |
| the deployed server | `ServeCatalogStore` fetches the delivery branch | whatever the deployment is pinned to |
| a `serve` on a laptop, against the same branch | the same fetch | usually older than the deployment |
| `compose-preview-server ui`, against a local Gradle project | the module's own build output, the way [`LocalUiBuilder.publishRecord`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/LocalUiBuilder.kt) already copies `build/compose-previews/components.json` | whatever `brew` or the wrapper last installed |
| the VS Code extension, an agent over MCP | through one of the above | not its own |

A catalog repository publishes **once**; every one of those reads the same bytes, and none of them
can be asked to upgrade first. Four rules follow, and they are the reason this section exists rather
than being left implicit:

1. **The file is a claim, not an instruction.** A reader takes the fields it understands, ignores
   the ones it does not, and never fails a load over an unknown key — the catalog is published by a
   pipeline that will run ahead of the oldest builder reading it, permanently. `schema` is there to
   refuse a *future major*, not to pin a minor.
2. **Adapters and template roles are negotiated, never required.** A catalog naming
   `frame/round-screen` on a build that ships no such adapter gets the placeholder and a startup line
   naming the adapter and the catalog — the rule already written for the canvas below, restated here
   because the multi-consumer case is what makes it load-bearing rather than tidy. A structural
   template naming a role the engine does not know refuses *that export*, with the role in the
   message; it does not cost the catalog its palette. The failure a builder must never produce is
   "this catalog does not load", because the operator of that builder cannot fix it.
3. **The generator belongs in the Gradle plugin, and the pipeline only publishes.** The original
   sketch put `generate-ui-builder-catalog.mjs` in the design-artifacts workflow, which is a lane
   that exists only in CI and only for a catalog with a delivery branch. `compose-preview-server ui`
   has neither, and it is the consumer that most wants the file: pointed at wear-m3-catalog's
   `:catalog` module, it should offer that module's own components, frame and templates rather than
   the packaged Material 3 palette it falls back to today. So `ui-builder.json` is written by the
   same discovery task that writes `components.json`, into `build/compose-previews/`, from the same
   scan plus `ui-builder.policy.json` and `catalog.spec.json` in the checkout; the pipeline's job is
   the one `catalog-component-record.mjs` already does for the record — copy it to the branch root
   and stamp `uiBuilderFile` on the manifest. One generator, two lanes, and the local lane is not a
   reimplementation that can disagree.
4. **The revision, not the version, is the compatibility unit.** A design pins a catalog revision
   ([`UI_BUILDER_PROJECT_DESIGNS.md`](UI_BUILDER_PROJECT_DESIGNS.md)); two builders resolving that
   revision must read the same bytes, which is true for the delivery branch and is *not* true for a
   local build output. A locally generated builder catalog is therefore unpinned by construction and
   says so — the same status a locally discovered component record already carries.

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

**The catalogs get ready first; this repository cuts over once they are.** A catalog repository can
publish a builder catalog that nothing reads yet, and that file can be proved byte-equal to what the
server synthesises today *before* a single reader here changes. So the risky half — the platform
word, the emitter routing, the canvas mapping, the loader — is written last, against files that
already exist, rather than first, against a contract that is still only this document.

That inverts the order this plan was originally written in, where phase 0 rewired every reader here
to load-instead-of-synthesise and the catalogs followed. Both orders end in the same place; this one
is reversible for longer. At the end of phase 3 the catalogs publish, nothing consumes it, and
abandoning the plan costs two policy files. Under the old order the same point was reached with
every reader in the builder already rewritten.

One thing must still happen here first, and it is small: the **freeze**. wear-m3-catalog cannot
reduce its policy from a description that exists only as Kotlin in this repository, and the
equivalence gate needs something to compare against.

Each phase is releasable on its own and leaves every catalog working.

### Phase 0 — this repository: freeze what is synthesised, change no reader

1. **Freeze the synthesised catalogs as JSON.** A test writes `wearM3Catalog(base)` and
   `remoteM3Catalog(base)` to `docs/design/fixtures/ui-builder/wear-m3-capabilities-v1.json` and
   `remote-m3-capabilities-v1.json` and asserts the checked-in files match. **The runtime keeps
   constructing them** — this is the one change from the original phase 0, and it is what makes the
   phase free: no loader, no fallback, no rollback. The goldens exist to be read by people and by
   the equivalence gate.
2. **The equivalence gate**, `.github/scripts/ui-builder-equivalence.sh`: fetch a catalog's published
   `ui-builder.json` from its delivery branch, normalise both sides (key order, formatting, and a
   checked-in list of reviewed differences), diff it against the frozen golden, and report. It lives
   here because the golden lives here, and because "is wear-m3-catalog ready?" is a question this
   repository has to answer before it deletes anything. Non-blocking until phase 4: a catalog that
   publishes nothing reports "not yet", not red.

   Under `--strict` — what the cutover PR turns on — seven things fail: an unaccepted difference, a
   fact the frozen catalog states that the catalog is silent about, **a fact the catalog states that
   the frozen one cannot check**, a stale exemption, a missing policy file, **a policy that is not
   this catalog's**, and a schema this gate cannot vouch for.

   The third is the mirror of the second and was informational for too long: `platformLabel`,
   `frame.adapter`, `frame.seedDevice`, `frame.geometry`, `code` and `templates` are absent from
   every frozen catalog — the server holds them as `when (catalogSystemId)` branches and transcribed
   constants rather than as data — and phase 4 *consumes* all of them. An arbitrary label or a wrong
   padding table would have reached the cutover with the gate reporting ready. They are accepted per
   catalog with `"frozen": null`, which is somebody recording that they read a value nothing here
   can check; wear-m3's geometry is reviewed against its own Robolectric probe, which is a stronger
   check than the golden could have been, and both seed devices against the frame this build already
   opens a new design on.

   **Read that table as a checklist.** Seven fields have now been added to this comparison one review
   round at a time — `previewSurfaces`, `code`, `templates`, `frame.seedDevice`,
   `componentMenu.components`, `componentPacks` and `assetRegistry.keys` — every one of them a fact
   a surface reads that nothing was comparing.

   Two of them are compared as **sets** rather than as arrays: `colorTokens.roles` and
   `assetRegistry.keys` are read by one `declaredStrings` helper in both the runtime and the export,
   and it returns a `Set<String>`, so a reordering no consumer can observe must not block a cutover.
   And `assetRegistry.keys` is compared only when the *catalog* states one: the frozen registry
   holds the packaged catalog's artwork and the editor's own insert placeholder, so treating a
   catalog's silence as a gap would demand it declare the builder's assets — and a gap cannot be
   waived, which would have left all three catalogs permanently not-ready with no route through. No frozen catalog carries a
   `componentPacks` and the generator emits none, so that last one is silent today; a field that
   cannot differ is cheapest to fix while nothing states it and dearest once something does.

   The last three of the six were added late, and the reason is worth keeping: a field left out of
   the comparison **cannot differ**, so `code`, `templates` and `frame.seedDevice` were carried past
   the gate unread while the platform word and the shelf order agreed. `componentMenu.components`
   arrived the same way and for the same reason: `groupOrder` names the sections, and only the
   sections were compared, so a generated catalog could move every component to a different shelf or
   drop every `variantProperty` and still report ready — the insert panel at cutover bearing no
   resemblance to the frozen one while the headings matched. It is compared as a **subset**, in both directions: the
   frozen catalog's menu also carries the builder's own components (`asset/image`, `layout/box`,
   `remote-compose/*`), so what is checked is the entries whose id carries the catalog's
   `componentIdPrefix` — each stated entry against the frozen one, *and* each frozen one against the
   catalog's. Sweeping only the catalog's keys caught a shelf that moved and not one that vanished,
   so a generated menu omitting a component — an empty map included — reached no comparison at all
   and passed while that component lost its shelf and its variant control. Which ids are the catalog's is asserted by the caller with
   `--component-id-prefix`, exactly as its identity is asserted with `--catalog-id` and for the same
   reason: corroborating the document's own prefix against the golden proves it matches *something*,
   not that it is this catalog's. The remote-m3 golden carries both `m3/…` (the packaged Material 3
   catalog's) and a single `remote-m3/…` of its own, so a generated Remote catalog declaring `m3/`
   named a real, corroborated prefix, reproduced those entries, dropped its own component and
   passed. `--strict` requires the assertion wherever the frozen catalog has a per-component menu
   for it to scope. The document's own prefix is read from the
   **published** `componentIdPrefix` and never derived from the catalog id — m3-catalog's components
   are `m3/…` while its id is `m3-catalog`, so a derived prefix would match none of its 25 frozen
   entries and disable the sweep as surely as a wrong one. A prefix the frozen catalog has never
   heard of blocks, as does one that disagrees with the caller's. A capability document publishes
   none at all — its builtins are materialised in — which is what `--component-id-prefix` supplies.
   A missing entry is a
   difference rather than a gap, and reviewable: the catalog publishes a menu and that menu does not
   list the id, which is an assertion that the component is gone, not the silence of a catalog that
   has not been written yet. An authored policy states none of them — a component's shelf comes from
   its `@CatalogGroup`, not from the policy — so this asks nothing of the three catalogs today and
   everything of the generated files they will publish. That is the same omission
   `previewSurfaces` was fixed for. What the gate does *not* do is validate them — whether
   `code.strategy` names a strategy that exists, or a `templates` path resolves to a document, is
   compose-ai-tools' `validate-ui-builder-policy.mjs` and a second implementation here would
   disagree with the real one exactly where it matters.

   A builtin the frozen catalog carries no component for lands on that same path, as
   `builtins.<id>` — one field per builtin. It used to be counted straight into the difference
   total, outside the model, so it could not be waived at all: the failure told the reader to record
   the decision in `--differences` and the sweep for waivers naming no compared field then reported
   that entry a second time. Per id rather than one entry for the set, because a catalog that
   deliberately adds one builtin has reviewed *that* builtin.

   Two of the seven read wider than their names suggest. "A policy that is not this catalog's" also
   covers a document that names **two** catalogs: a capability document is identified by
   `benchmark.catalogSystemId` while carrying `catalog.id` as ordinary payload, so reading the
   identifiers in a fixed order rather than by shape let the payload shadow the identity and the
   gate approve the wrong catalog. "A schema this gate cannot vouch for" also covers a **known
   family on the wrong shape**: the shape is detected structurally, so an authored policy labelled
   `compose-ui-builder-catalog/v1` was read as a policy and compared field by field. A document
   whose label and whose contents disagree has one of the two wrong, and which one is not for this
   gate to guess. `--catalog-id` states which catalog
   the caller MEANT, and is checked against the golden's id and the policy's alike — so asking for
   one catalog while holding another's policy *and* its matching golden fails, which no comparison
   of those two files against each other can catch. Required for a catalog whose policy defaults its
   id from the cover sheet (`:remote-catalog` and m3-catalog both do; wear-m3-catalog declares
   `catalogId` because its builder id and its delivery system differ), and worth passing always.
   Semantics never identify a catalog: a second `wear` catalog can agree on every compared field, so
   without an id the gate cannot tell "ready" from "you fetched the wrong file". The three
   invocations, of which **only the `wear-m3` one passes today** (measured 2026-09-12 against each
   catalog repository's current `main`; `remote-m3` reports 0 differences and **29** unusable
   exemptions, `m3-catalog` 0 and **107**, because those two differences lists have outlived the
   differences they were written for — their policies moved to agree with the frozen catalog and
   nobody retired the exemptions):

   ```
   .github/scripts/ui-builder-equivalence.sh --strict \
     --policy <wear-m3-catalog>/ui-builder.policy.json \
     --golden docs/design/fixtures/ui-builder/wear-m3-capabilities-v1.json \
     --differences docs/design/fixtures/ui-builder/wear-m3-differences.json --catalog-id wear-m3
   # …/remote-catalog/ui-builder.policy.json  → remote-m3
   # <m3-catalog>/ui-builder.policy.json      → m3-catalog
   ```

Everything else that was phase 0 — the platform word, the emitter routing, the canvas mapping, the
loader — is now phase 4.

**Freezing the seed documents is NOT phase 0**, and the earlier draft of this list was wrong to put
it here. `UiBuilderNewDesignSeed` builds the Wear screen, the widget samples and the blank seeds in
Kotlin, and serialising them to `ui-builder/designs/*.json` with a `template` marker is the same
kind of freeze as step 1 — but it freezes a different artifact for a different consumer, and phase 0
delivers neither the fixtures nor the assertions. Saying otherwise made phase 0 look complete while
one of its three steps had not been done.

It belongs immediately before a catalog authors its first `templates` entry, which is a real
dependency rather than a preference: a catalog repository is told to *copy* these documents, and
without a frozen reference there is nothing for phase 2 or 3 to be checked against — the same
argument that put the catalog goldens in phase 0. Neither wear-m3-catalog nor m3-catalog declares a
template today (both policies carry a `$comment_templates` saying the seeds are still Kotlin here),
so nothing is blocked by the ordering; what was blocked was the claim.

Sequenced as **phase 3a**, below.

### Phase 1 — compose-ai-tools: a catalog *can* publish a builder catalog

4. **The annotation.** `@BuilderComponent` in `preview-annotations`, read by `PreviewDiscovery` into
   the record's authored fields, beside `@CatalogComponent`'s existing ClassGraph scan. Ships in the
   train both catalog repositories are moved onto.
5. **The schema and the generator.** `ui-builder.policy.schema.json`, and the generator **in the
   Gradle plugin's discovery task**, writing `build/compose-previews/ui-builder.json` beside
   `components.json` from the same scan plus the checkout's `ui-builder.policy.json` and
   `catalog.spec.json`. The design-artifacts pipeline copies it to the branch root and stamps
   `uiBuilderFile` on `catalog.json`, exactly as `catalog-component-record.mjs` already does for the
   record — see [One artifact, many builders](#one-artifact-many-builders) for why the generator is
   not in the pipeline. A catalog with no policy file publishes no builder file, so the catalog
   repositories change nothing for this step.
6. **The structural template engine**, in `screen/generator` beside `ScreenGenerator`, so the browser
   and the server share it the way they share the call-site printer. Proven by a functional test that
   renders the frozen `wear-m3` templates over the `wear-list` document and compiles the result
   against real Wear Compose — `samples/design-catalog-wear-m3` already compiles that exact output.
7. **Contracts.** `platform`, `platformLabel`, `previewSurfaces`, `frame`, `code` and `templates` as
   typed fields on `CatalogCapabilityV1` in compose-preview-contracts, with `statusSemantics` read as
   the fallback for one contracts major. Not a prerequisite for anything below.

### Phase 2 — wear-m3-catalog: the Wear and Remote Compose catalogs describe themselves

Nothing here reads the result yet. The output of the phase is a green equivalence gate.

8. `@BuilderComponent` on the `:catalog` stickers and `ui-builder.policy.json` beside the cover
   sheet (`wear`) — the frozen `wear-m3` golden of phase 0 is the starting point, reduced to what
   the record cannot say. Shelves come from `@CatalogGroup` sections already; what is authored is
   the frame, the stand-in mapping, the structural templates, starter content, controlled-state
   declarations and slot policy. Both catalogs bump to one compose-ai-tools train first: m3-catalog
   is on 1.85.0 and wear-m3-catalog on 2.0.0, and the annotation ships in one of them.
9. `ScreenScaffoldContentPaddingTest` writes the `frame.geometry` block and asserts the committed
   policy matches. The canvas here still carries its own copy; phase 4 deletes it.
10. `ui-builder.policy.json` for `:remote-catalog` (`remote-compose`): the widget host frame, the
    reviewed subset, the Lottie element, the creation-DSL templates.
11. A round-trip test in that repository: every template design → `ui-builder.json` → generated
    Kotlin → compiles against the module's own classpath → renders on Robolectric. It consumes the
    published `ui-builder-export` and `screen-model` coordinates, which the layer rule allows (a leaf
    depends down).
12. **Readiness:** the equivalence gate is green for `wear-m3` and `remote-m3` — the generated file
    equals the frozen golden modulo a reviewed difference list checked in beside it. That list is
    the deliverable of the phase as much as the policy is: every entry is a decision someone made
    about a discrepancy, and an empty list is the ideal rather than the requirement.

### Phase 3 — m3-catalog: the served Material 3 catalog describes itself

13. `@BuilderComponent` on the `:catalog` stickers and `ui-builder.policy.json` (`mobile`): the
    variant table, editor bounds and colour suggestions from `CapabilityCatalogParser.EDITOR_OVERRIDES`,
    starter content, `frame/rect` with `seedDevice: id:pixel_6`, and the three non-composable
    builtins (`shape/*`, `asset/image`) declared as such.
14. The equivalence gate for `m3-catalog` compares against the *packaged Jetcaster* catalog, which
    is a different kind of comparison: the two describe different component sets on purpose
    (fifty-nine rendered components against twenty-five transcribed ones), so here the reviewed
    difference list is the point and a byte-equal result would be the surprising outcome. The record
    plan's phase 2 asks for exactly this diff; the gate is where it gets written down.

### Phase 3a — this repository: freeze the seed documents

Only needed before a catalog authors its first `templates` entry, and no catalog does yet. Listed as
its own step because it was previously miscounted as part of phase 0, which made that phase look
finished while this was not done.

15. **Templates become documents.** `UiBuilderNewDesignSeed`'s Wear screen, the widget samples and
    the blank seeds are serialised to `ui-builder/designs/*.json` fixtures — one per
    `templateIds(catalog)` entry, per catalog — with a `template` marker and the seed device in
    `environment`, and a golden test asserts the Kotlin builders still produce them. The same
    `-PuiBuilderGoldens=write` mechanism and the same input declaration as the catalog goldens, for
    the same reason: a golden the build can skip reading is not a golden.
16. `UiBuilderNewDesignSeed` keeps reading the Kotlin. The documents are what a catalog repository
    copies onto its delivery branch and names in `templates`, and what the gate can then check a
    catalog's copies against — without a frozen reference, "did wear-m3-catalog reproduce the seed?"
    has no answer, which is the argument that put the catalog goldens in phase 0.

### Phase 4 — this repository: read the published file

Everything deferred from the original phase 0, now written against three files that exist and are
proven equivalent.

**Items 18 and 20 have landed.** `ServeCatalogStore` stages a catalog's declared `uiBuilderFile` and
fetches it at startup; `PublishedUiBuilderCatalog` composes it with the component record into a
`CatalogCapabilityV1`; `CurrentM3UiBuilderCatalogExecutor` takes those as `published` and prefers
them per catalog, with `catalogSources` saying where each came from. A catalog id nothing here
synthesises is servable, which `PublishedUiBuilderCatalogTest` proves against a `test-catalog` this
binary has never heard of. Items 15, 16, 17 and 19 remain: the platform word, the emitter routing,
the canvas mapping and the `compose-preview-server ui` lane still read Kotlin, so a published catalog
is served but a **Wear-shaped** one is not yet drawn from its own declarations.

**The lever item 18 promised is now a flag, and it defaults to `none`.** Item 18 says the cutover is
"per catalog and reversible"; as shipped, the only reversal an operator had was
`SERVE_UI_BUILDER_CATALOGS`, which withdraws a catalog from the builder entirely rather than
returning it to its synthesised definition. `--ui-builder-published-catalogs` is the finer lever:
`all`, `none`, or a subset of the served catalogs, refusing an id this host does not serve so a typo
is a startup error rather than a silent no-op. `SERVE_UI_BUILDER_PUBLISHED_CATALOGS` reaches it from
the deployment image.

**It defaults to `none` because neither catalog this image serves is ready, and one of them would
have broken.** Measuring the two against their frozen goldens, rather than assuming:

| Builder catalog | Served from | Publishes `ui-builder.json`? | Gate |
| --- | --- | --- | --- |
| `remote-m3` | `yschimke/wear-m3-catalog` `design-artifacts/remote-m3` | yes, 42 KB, and the cover sheet declares it | 0 differences against the committed snapshot, `--strict` green in CI |
| `m3-catalog` | `yschimke/m3-catalog` `design-artifacts/m3-catalog` | yes, 188 KB, and the cover sheet declares it | 0 differences against the live document; **2** unusable exemptions |

**Both rows were rewritten on 2026-09-12; the table had outlived its measurements.** It recorded
`remote-m3` as declaring no `uiBuilderFile` and `m3-catalog` as publishing a 48 KB file scoring 25
differences. Neither holds: every one of the five delivery branches now stamps
`"uiBuilderFile": "ui-builder.json"` on its cover sheet, and the two figures below are what the gate
actually reports.

`m3-catalog` was the dangerous one and it was on. When this paragraph was written its published file
declared **zero** components and **zero** builtins under `statusSemantics`, so composing it against
the then-104-component record derived every id from the `m3/` prefix in place of a curated shelf of
41 and left the rest ungrouped. **The components half has since been fixed** — the published file
now declares 110 components with a 102-entry menu over 39 shelves, and scores 0 differences. The
builtins half has not: it still declares **zero**, so a catalog composed from it has no screen root
to put any component into. That is why the lever stays `none` rather than why it was set — the
original reason is gone and this one replaces it. Nothing in the loader would say so either way: the
file is well-formed, so `PublishedUiBuilderCatalog` composes it happily. Turning the published path
off by default and naming a catalog to turn it on is the shape phase 4 should have shipped with.

**`wear-m3` is out of the image's default allowlist, for a different and simpler reason.** It is a
Wear/Android catalog — Robolectric previews, an Android SDK for its native lane — that nobody is
authoring against on this deployment, so it stopped earning its dependency. Nothing about the
catalog changed: the adapters, the frozen templates and the render behind the wear-m3 claim all
still work, and `--ui-builder-native-catalog wear-m3=wear-m3-catalog` is kept in the entrypoint,
inert, so putting `wear-m3` back is one variable rather than a catalog whose native render compiles
against the wrong bundle.

**Corrected again, by running the server rather than reading the file.** The paragraph above said
m3-catalog's published file "declares zero components, so 104 ids are derived from the `m3/` prefix
in place of a curated shelf of 41". The first half is right and the conclusion was wrong in a way
worth keeping, because the wrong step is a recurring one. Composing the real file against the real
104-component record yields **41** components — the frozen catalog's count exactly — and that
coincidence is what the earlier text mistook for agreement. The id sets share **one** entry.

What actually happens: `derivedId` takes the leaf of a record entry's first `componentIds` value,
and m3-catalog's are a `Group/Variant` taxonomy — `Dialog/Basic`, `TopAppBar/Small`,
`Buttons/Filled`. The leaf is the **variant**. `Filled` alone is claimed by 15 components,
`Standard` by 9, `Small` by 5; 63 of 104 collide, and the 41 that survive are named `m3/filled`,
`m3/small`, `m3/standard`, `m3/on`, `m3/checked`. Every builder-owned id — `layout/*`,
`asset/image`, `shape/*`, `remote-compose/*` — is absent too, because the file declares no builtins.

So the shelf a default-on `m3-catalog` would have served is not a degraded version of the curated
one; it shares a single component with it. Two things follow, and both have landed:

1. **The reader refuses a file whose ids collide in bulk.** `PublishedUiBuilderCatalog` returns
   `Unusable` naming the numbers when collisions exceed a tenth of the record, with a floor of one
   so a single stray duplicate is still a skip rather than a withdrawn catalog. wear-m3-catalog's
   28-of-78 would trip it too.
2. **The equivalence gate compares component ids.** `--record <components.json>` makes it report
   how many ids the catalog would offer, how many collided, and which the frozen catalog has that
   this one would not — the check that answers "is this catalog ready?" without running a server.
   It compares ids only and composes nothing, so the header's objection to reimplementing the
   loader in bash still stands; the one shared piece, `slug`, is pinned to the same four cases
   `PublishedUiBuilderCatalogTest` pins against the Kotlin.

**And the real gap is phase 3's.** m3-catalog's authored `ui-builder.policy.json` declares
`componentIdPrefix`, `frame`, `menu`, `platform` and `colorTokens` — and no `components` at all. The
contract's § *What the catalog authors* has each catalog declaring its components; m3-catalog never
did, and until it does no derivation heuristic recovers the curated shelf. Taking the group instead
of the variant would raise the overlap from 1 to 10 of 25, which is the measurement that settles it:
this is authoring work in the catalog, not a derivation to be tuned here.

**The lesson, since it is the third time in this document.** A count is not an identity. The
composition produced 41 against a frozen 41, and every check comparing sizes — including the
sentence in the previous revision of this section — reported a match.

Worth recording because it was a wrong guess corrected by measurement: `wear-m3-catalog` *does*
publish a `ui-builder.json` with 28 colliding derived ids, and it looked like the catalog at risk.
It is not. The `wear-m3` **builder** catalog is served from `yschimke/compose-ai-tools`
`design-artifacts/wear-m3`, which declares no `uiBuilderFile`; wear-m3-catalog's file rides the
`wear-m3-catalog` system, which is not an enabled builder catalog and is never read. The loader
resolves a published file by the **builder catalog id used as a served system id**, and that
indirection is not obvious from either end of it.

15. **Platform becomes a word.** `UiBuilderCatalogPlatform(wireValue, label)` over a string; `label`
    from `statusSemantics.platformLabel` with the three known words as fallback labels for one
    release. The chooser groups the enabled catalogs by platform word and labels each group by its
    catalogs' label. `--ui-builder-packs` validates the word against the enabled catalogs.
16. **The emitter is chosen by declaration.** `RecordFreeExport` routes by
    `statusSemantics.code.strategy` (`record` | `templates`) rather than by root component id.
17. **The canvas reads a mapping.** *(Also `WearWidgetHostShape`'s geometry table — the widget
    host's content box, padding and radius per shape and size — which per §3 the catalog's own
    Robolectric probe should measure, exactly as it already measures `ScreenScaffold`'s content
    padding. It is worth watching rather than merely listing: #605 added it with four transcribed
    rows and #609 grew it to six with per-diameter selection, while removing the `wear-m3-catalog`
    mention that made `ui-builder-catalog-literals.sh` able to see it at all. That gate matches
    catalog NAMES, and the debt is catalog KNOWLEDGE, so this table is now invisible to it and
    growing. Nothing is wrong with either change — both consolidated copies that were previously
    duplicated between the canvas and the native lane — but the trajectory is the argument for
    doing this item sooner rather than later.)* `wearScreenStandIn`, the `WEAR_NATIVE_ONLY` set, the root
    alignment rule and the frame choice become lookups on `statusSemantics.components[id].canvas`
    and `statusSemantics.frame`. The adapter registry is the existing `when`, keyed by adapter id
    instead of component id, and an adapter this build lacks draws a placeholder and logs.
18. **The loader.** `ServeCatalogStore` stages `ui-builder.json` ahead of the load;
    `ProductionUiBuilderRuntime` takes a `List<CatalogCapabilityV1>` and constructs none. The
    cutover is **per catalog and reversible**: a published file is preferred, the synthesised
    catalog is the fallback, and a startup line says which one each catalog came from. The frozen
    goldens of phase 0 serve the same catalog with no reachable branch **for the duration of the
    cutover only** — they go with the synthesisers in phase 5, and from there the packaged
    `m3-catalog` is the one offline catalog (§ *A builder catalog is a served catalog*, item 7) and
    an unreachable branch refuses that catalog by name rather than serving a stale copy of it.
19. **`compose-preview-server ui` publishes the local file.** The lane that copies
    `build/compose-previews/components.json` also copies `ui-builder.json` when discovery wrote one,
    and the local project becomes a builder catalog rather than a record hanging off a packaged
    palette. This is the consumer that pays for the contract most visibly, and it needs no delivery
    branch at all.

    Two things about that lane are *not* free, and are written down here because both are easy to
    discover only after implementing the easy half:

    - **The catalog is chosen before the module is known.** `commandLane` resolves
      `LocalUiBuilder.catalog(args)` and builds the whole `serve` argument list up front, because
      options are constructed before any Gradle work runs; the discovery callback can only fill in a
      path that was *pre-declared*, which is exactly the trick `--ui-builder-components` and the
      temporary `components.json` already use. A pre-declared path is not enough for a catalog,
      because the enabled catalog's **id** is also fixed up front and is not knowable from a file
      that does not exist yet. The way out is that `ui-builder.policy.json` is committed source: it
      is in the checkout before anything is built, so `ui` can read `catalogId` (or the cover
      sheet's `system`) from it at startup, enable *that* id, and pre-declare the path discovery
      will fill. Without this step, `ui` pointed at wear-m3-catalog still opens packaged Material 3
      and looks like the feature landed.
    - **A template design is a second file, and there is no branch to fetch it from.** A policy's
      `templates` are branch-relative paths (`ui-builder/designs/wear-list.json`); locally there is
      no delivery branch and no `--ui-builder-designs` source, so a catalog whose New-design chooser
      offers templates would load and then fail to open any of them. Either `ui` stages the
      referenced documents beside the record in the same per-invocation directory, or — better, and
      the option this plan prefers — the generator **inlines template documents into
      `ui-builder.json` when it writes the local copy**, which makes the artifact self-contained and
      is the same property [One artifact, many builders](#one-artifact-many-builders) asks of it
      everywhere else.
20. **The grep gate.** `.github/scripts/ui-builder-catalog-literals.sh` fails a pull request that
    introduces `wear-m3`, `remote-m3` or `wear-m3-catalog` into main sources of `:ui-builder`,
    `:ui-builder-export`, `:ui-builder-runtime` or `:server` outside an allowlist that shrinks every
    step and is empty at phase 5. The same shape as `ui-builder-project-boundary.sh`, for the same
    reason: an undrawn line rots.

### Phase 5 — this repository: delete

21. `wearM3Catalog`, `remoteM3Catalog`, `wearComponentMenu`, `wearNativeOnlyComponents`,
    `WearScreenCodeExporter`, `WearWidgetCodeExporter`'s structural half, the Wear entries in
    `StarterContent`, the Wear constants in `UiBuilderRenderer`, the frozen fixtures, the
    equivalence gate they existed for, and `--ui-builder-native-catalog` as a required flag. The
    entrypoint's `SERVE_UI_BUILDER_CATALOGS` default stays as the allowlist.
22. The grep gate's allowlist is empty.
23. **The proof: Material 4 arrives without a release of this repository.** A `material4-catalog`
    publishes a policy and appears in the chooser, is drawn on, exports, and renders natively,
    against a server binary that has never heard of it. It is a deployment, not a unit test, and
    its cheaper twin is available from phase 4: the same catalog opened locally with
    `compose-preview-server ui`, against a binary that has never heard of it either.

    Material 4 rather than a TV catalog on purpose. A new *platform* exercises the one cost this
    plan accepts — a frame adapter here, once — and so proves the least interesting thing. Material 4
    shares `mobile` and `frame/rect`, needs no adapter, and lands squarely on every hardcoded
    Material 3 fact instead:

    - **Two catalogs under one platform word.** `m3-catalog` and `material4-catalog` are both
      `mobile`, both in the chooser's Mobile group, each with its own label and its own templates.
      Today's `when (catalogSystemId)` cannot express that at all: the chooser's three branches
      *are* the three catalogs.
    - **A variant table that is not Material 3's.** The sixteen
      `m3-catalog/androidx.compose.material3.…` literals in `ScreenDocumentProjection` are the only
      place the export names a catalog, and a Material 4 button's variants are not among them.
    - **Starter content, colour roles and editor bounds that are not Material 3's.** Every one is a
      `StarterContent` entry or a `CapabilityCatalogParser.EDITOR_OVERRIDES` row today.
    - **Canvas adapters this build does not ship.** A Material 4 catalog would name `material4/*`,
      and drawing it through `material3/*` would be a lookalike of an upstream nobody here
      compiles — the thing the never-fake rule forbids. So it draws in placeholders, and **the
      acceptance is that it is usable and honest that way**: the palette, the tree, the properties,
      the export and the native render all work while the canvas says "not drawn here". A builder
      that instead refuses the catalog, or silently draws it as Material 3, has failed this test
      even though every file loaded.

    The last bullet is the one to watch, because it is the tempting failure. "Material 4 works" must
    not come to mean "we added `material4/*` adapters", which is a release of this repository per
    catalog — precisely what the plan exists to end. Adapters are welcome afterwards, on their own
    merits, and only afterwards.

## Success criteria

- No catalog id and no platform word appears in main sources of the builder or the server, other
  than the packaged fallback's, and CI says so.
- A catalog's frame geometry lives beside the test that measures it, and nowhere else.
- The New design chooser, the palette, the Code pane, the export and the native lane read one file
  per catalog, and that file is generated from inputs the catalog repository owns.
- The three existing catalogs render, export and round-trip byte-identically across the cutover, and
  within a dp once the geometry is read from the catalog rather than compiled in (the same numbers,
  moved). The equivalence gate is what says so, per catalog, before any of it is deleted.
- Adding a platform costs at most one frame adapter here; adding a catalog costs nothing here.
- **Material 4 is the test of that sentence.** A second `mobile` catalog, with its own ids,
  variants, starter content and colour roles, reaches a person through a server binary released
  before it existed — drawn in placeholders where this build has no adapter for it, and usable.

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
- **Two contract versions.** compose-ai-tools pins contracts 2.5.0 and this repository 2.9.0. The contracts
  step lands in contracts first and both consumers bump; until then `statusSemantics` carries the
  fields, which is why it is not on the critical path.
- **The `remote-m3` id.** The builder's `remote-m3` and wear-m3-catalog's published `remote-m3` are
  the same id by convention today; under this plan they are the same catalog by construction. A
  deployment serving the system under a different id would need `--ui-builder-native-catalog`
  spelled the old way, which is why the flag stays as an override.
