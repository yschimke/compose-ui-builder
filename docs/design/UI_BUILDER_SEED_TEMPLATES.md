# Moving the UI builder's seed templates into the catalog repositories

> **Written in `compose-preview-server` before the extraction; this is now the authoritative copy.**
> The eight seed templates, the two export lanes that judge them and the readiness gate below all
> live in **this** repository, so "this repository" throughout means `compose-ui-builder`. What stays
> in `compose-preview-server` is the serving half: `ServeUiBuilderCreate` and `ServeWeb` call
> `UiBuilderNewDesignSeed` across the `compose-preview-ui-builder-export` seam,
> `ServeCatalogStore` fetches a catalog's `ui-builder.json`, and
> `--ui-builder-published-catalogs` is the lever step 6 waits on. Step 5 therefore spans both
> repositories and is two pull requests — the cost
> [`UI_BUILDER_PROJECT_BOUNDARY.md`](UI_BUILDER_PROJECT_BOUNDARY.md) predicted for a split, now paid.
> That repository keeps a copy of this document for the links its catalog notes and its own contract
> already point at.

The builder ships eight starting points — a blank Material screen, a blank and a worked Wear screen,
two Wear widget host frames and two worked widget samples — and every one of them is Kotlin in
**this** repository, under `ui-builder-export/src/commonMain/…/UiBuilderTemplates.kt` and
`UiBuilderNewDesignSeed.kt`. Six of the eight draw components this repository does not own and cannot
render: they are wear-m3-catalog's screens and widgets, transcribed here.

[`UI_BUILDER_CATALOG_CONTRACT.md`](UI_BUILDER_CATALOG_CONTRACT.md) already says where they belong — a
catalog declares its own `templates` in `ui-builder.policy.json`, pointing at documents on its
delivery branch — and sequences the freeze as **phase 3a**. This document is that phase's plan, plus
the measurement the phase turns out to need first: **do the templates validate, and do they generate
source?** A template a catalog repository is asked to carry and cannot export is a template that
repository cannot test, and the answer is not the same for all eight.

## The measurement

`SeedTemplateCatalogReadinessTest` (this repository's `:ui-builder` `jvmTest`) walks
`UiBuilderNewDesignSeed.templateIds` for all three served catalogs, seeds each template against the
frozen capability document for its catalog, and asks both questions — `CapabilityValidator` for the
first, and for the second the same two lanes the editor's Code pane asks in the same order:
`RecordFreeExport` (the dedicated Wear-screen and Remote-widget emitters) and then `ScreenExportGate`
over the component record. It writes each generated file to `build/seed-template-sources/`, so "it
generates" can be read rather than believed.

| Catalog | Template | Validates | Generates source | The Kotlin needs |
| --- | --- | --- | --- | --- |
| `m3-catalog` | `blank` | yes | yes | `androidx.compose.material3` |
| `m3-catalog` | `jetcaster` | yes | **no** | — |
| `wear-m3` | `wear-screen` | yes | yes | Wear Compose Material 3, Horologist-shaped previews |
| `wear-m3` | `wear-list` | yes | yes | Wear Compose Material 3 |
| `remote-m3` | `wear-widget-small` | yes | yes | `wear-compose-remote`, `glance-wear` |
| `remote-m3` | `wear-widget-large` | yes | yes | `wear-compose-remote`, `glance-wear` |
| `remote-m3` | `hello-widget` | yes | yes | `wear-compose-remote`, `glance-wear` |
| `remote-m3` | `weather-widget` | yes | yes | `wear-compose-remote`, `glance-wear` |

Two things in that table decide the plan.

**The classpath column is the argument for the move, stated in imports.** The generated
`hello-widget` writes `androidx.glance.wear.GlanceWearWidget`, `WearWidgetDocument`,
`androidx.wear.compose.remote.material3.RemoteText` and three `WearWidgetPreview` parameter sets;
`wear-screen` writes `AppScaffold`, `ScreenScaffold`, `TransformingLazyColumn` and
`rememberTransformationSpec`. Nothing in this repository compiles either. `:remote-catalog` and
`:catalog` in wear-m3-catalog compile both today — which is what makes phase 2's round-trip test
(document → generated Kotlin → compiles → renders) possible there and impossible here.

**`jetcaster` is not a template and should not move.** It refuses, and it refuses for reasons no
component record fixes: an adaptive `SupportingPaneScaffold` layout mode that has no parameter to be
written to, a carousel whose `items` is a `CarouselScope` DSL, grid spans belonging to the wrapper
around a node, and `selected` properties comparing a state variable. Those are values this
vocabulary has no Kotlin for, not components the export has not been shown — the document validates
against `m3-catalog` cleanly. It is also not offered in the New design chooser: it is
`UiBuilderNewDesignSeed.DEFAULT_TEMPLATE`, the document a URL naming no template gets, and it is
this repository's own Jetcaster reference fixture (`ui-builder-reference-jetcaster`, the benchmark of
[`UI_BUILDER_JETCASTER_BENCHMARK.md`](UI_BUILDER_JETCASTER_BENCHMARK.md)). So the move carries
**seven** documents to two repositories, and `jetcaster` stays here as what it already is.

## What moves where

| Repository | Module | Catalog | Templates |
| --- | --- | --- | --- |
| `m3-catalog` | `:catalog` | `m3-catalog` | `blank` |
| `wear-m3-catalog` | `:catalog` | `wear-m3` | `wear-screen`, `wear-list` |
| `wear-m3-catalog` | `:remote-catalog` | `remote-m3` | `wear-widget-small`, `wear-widget-large`, `hello-widget`, `weather-widget` |

`wear-list` is the clearest case: it is already wear-m3-catalog's own activity list, row for row,
transcribed into Kotlin here because there was nowhere else to put it. `hello-widget` and
`weather-widget` reproduce [android/wear-os-samples' `WearWidget`
sample](https://github.com/android/wear-os-samples/pull/1386) as designs, against the catalog that
draws widgets. The two host frames are `WearWidgetScaffoldSize`'s two sizes, whose dimensions
`remote-catalog/ui-builder.policy.json` already declares in `frame.geometry.sizesDp` — the document
and the frame it is drawn in would finally be authored in one place.

**Starter content is a different thing and moves differently.** `StarterContent` in `:ui-builder`
seeds a slot when a component is inserted; it is per-component, not a document, and the contract
moves it as `@BuilderComponent(starter = …)` on the sticker. It is not in this plan's scope beyond
saying so, because "the samples" reads as covering it and it does not.

## The contract needs one change first

The contract sketches `templates` as a list of paths:

```jsonc
"templates": ["ui-builder/designs/wear-list.json", "ui-builder/designs/blank.json"]
```

A path list cannot carry what the chooser prints. `Main.kt`'s `newDesignCatalog` holds, per template,
an **id**, a **label** ("Activity list") and a line of **supporting text** ("Six title cards under a
list header, row for row the reference's"), and `UiBuilderNewDesignSeed.DEFAULT_TEMPLATE` says which
one a URL naming none gets. None of that is in the document, and deriving a label from a filename
gives "Wear list" for a card that should read "Activity list". So each entry is an object:

```jsonc
"templates": [
  {
    "id": "wear-list",
    "path": "ui-builder/designs/wear-list.json",
    "label": "Activity list",
    "supportingText": "Six title cards under a list header, row for row the reference's.",
    "order": 2
  },
  { "id": "wear-screen", "path": "…", "label": "Wear screen", "supportingText": "…", "default": true, "order": 1 }
]
```

`order` because the chooser's order is authored today and is a decision — the blank starting point
comes before the worked sample in both Wear catalogs, and the comments in `Main.kt` say why. `default`
because exactly one template per catalog answers "a URL named no template", and a catalog that
declares templates should answer it rather than inheriting `jetcaster`.

## Steps

**1. Freeze the seven documents here** (phase 3a, item 15), beside the capability fixtures that
came across with the code. One JSON per template under
`docs/design/fixtures/ui-builder/designs/templates/<catalog>/<template>.json`, with the seed device in
`environment` and a `template` marker, written by the same `-PuiBuilderGoldens=write` mechanism as the
catalog goldens and asserted against the Kotlin builders by a golden test. The `catalogPin` is a
placeholder, not a live revision: `UiBuilderNewDesignSeed.document` rewrites `systemId`,
`catalogRevision` and `nativeRuntimeId` from the catalog actually being served, so a frozen pin is
noise that would go stale and read as meaningful.

**2. Extend the readiness gate to the frozen documents.** `SeedTemplateCatalogReadinessTest` asks its
two questions of the Kotlin builders today. Once the documents exist it asks them of the documents,
which is the form a catalog repository's copy can be checked in — and the form that keeps answering
after the builders are deleted.

**3. `templates` as objects, in the schema and the contract.** `ui-builder.policy.schema.json` and
`validate-ui-builder-policy.mjs` live in compose-ai-tools; the entry shape above is theirs to accept,
and the generator's to copy through onto `ui-builder.json`. Nothing reads it yet, so this step is
safe to land ahead of every other one.

**4. The catalog repositories carry their documents.** Per repository: copy the frozen JSON to
`ui-builder/designs/<template>.json`, declare the `templates` entries in the module's
`ui-builder.policy.json`, and add the round-trip test phase 2 item 11 already asks for — every
template document → `ui-builder.json` → generated Kotlin → **compiles against that module's own
classpath**, and for `:remote-catalog` renders on Robolectric. That test is the deliverable, not the
copy: it is the first time any of these seven designs has been compiled against the libraries it
names. The design-artifacts pipeline copies `ui-builder/designs/` to the delivery branch beside
`ui-builder.json`.

**5. The builder reads `templates`, and the server passes them through.** In this repository:
`UiBuilderNewDesignSeed.templateIds` stops being a `when` on the system id and becomes a question
about the served catalog; `document` returns the catalog's frozen document with the pin rewritten;
and `newDesignCatalog` in `ui-builder/src/wasmJsMain/…/Main.kt` builds its cards from the catalog's
entries instead of three hardcoded branches. In `compose-preview-server`: the `POST` and `PUT`
callers in `ServeUiBuilderCreate` and `ServeWeb` validate a template id against the served
catalog's entries rather than against `templateIds(systemId)`. Two pull requests, and the seam
version is what orders them — the server's side needs the released
`compose-preview-ui-builder-export` carrying the new signature, or a composite build against this
checkout. The Kotlin builders stay as the fallback for a catalog that declares no templates — which
is every catalog that host synthesises, and the `else` branch the chooser already has for a catalog
it has never heard of.

**6. Delete the transcriptions.** Only once all three catalogs declare templates AND
`compose-preview-server`'s `--ui-builder-published-catalogs` includes them: the published path is on for `remote-m3` and off for
the other two, so deleting `wearScreenUiBuilderDocument` before `wear-m3` is served published would
take the Wear templates away from the running deployment. `WearWidgetSample`, the widget document
builders, the Wear screen builders and the chooser's three branches go together, and
`blankUiBuilderDocument` is the one that stays — an empty scaffold is what the `else` branch gives a
catalog with nothing authored.

Steps 1–3 are independent of each other and of the catalog repositories. Step 4 needs 1 and 3; step 5
needs 4 for at least one catalog and is per-catalog reversible through the existing lever; step 6
needs 5 for all three.

## Risks

- **A design pinned to a synthesised catalog strands when the lever flips**
  ([#796](https://github.com/yschimke/compose-preview-server/issues/796)). Step 5 changes no catalog's
  source by itself, but step 6's precondition is that the lever is on for all three, so this plan
  inherits the issue rather than introducing it.
- **`remote-m3/lottie` is not in the published catalog**
  ([#795](https://github.com/yschimke/compose-preview-server/issues/795)). None of the four `remote-m3`
  templates uses it, so the move is not blocked — but a widget template that wanted it could not be
  authored against the published catalog.
- **Two of the seven are somebody else's sample.** `hello-widget` and `weather-widget` reproduce an
  upstream Wear OS sample's design. wear-m3-catalog's rule for vendored sources is that upstream's
  bytes declare their inventory in `groups` and are never annotated; these are not upstream's bytes —
  they are designs drawn from its screenshots, authored here — so they enter as ordinary authored
  documents. Worth stating in that repository's own note, because "it came from a sample" is exactly
  the condition its `AGENTS.md` treats specially.
- **A template is a document with a schema version.** `compose-ui-builder-document/v1-candidate` is
  what the builders write today. A catalog repository carrying documents becomes a consumer of that
  schema, which is one more thing a candidate schema's next revision has to migrate.
