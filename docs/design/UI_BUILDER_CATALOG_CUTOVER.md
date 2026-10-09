# The catalog-owned cutover: one flag, the evidence for flipping it, and how the code moves

**Status: staged, 2026-10-07. The flag exists and defaults off; nothing reads the new paths in a
deployment.** This is the plan that finishes [#64](https://github.com/yschimke/compose-ui-builder/issues/64),
[#191](https://github.com/yschimke/compose-ui-builder/issues/191) and
[#333](https://github.com/yschimke/compose-ui-builder/issues/333) without another flag day. It replaces
the parked big-bang PR ([#329](https://github.com/yschimke/compose-ui-builder/pull/329), and the
draft before it, [#231](https://github.com/yschimke/compose-ui-builder/pull/231)) with the same end
state reached in reversible steps: first every reader learns the catalog-owned answer behind one
flag, then a test proves each catalog would be served correctly with the flag on, then the flag is
flipped per catalog, and only then is the Kotlin deleted.

Read [`UI_BUILDER_CATALOG_AGNOSTICISM.md`](UI_BUILDER_CATALOG_AGNOSTICISM.md) for why each of these
facts is in the wrong place, and [`UI_BUILDER_CATALOG_CONTRACT.md`](UI_BUILDER_CATALOG_CONTRACT.md)
for the published files this reads.

## Where #333 stood

#333 parked #329 until two things had happened: a builder release on contracts 3.12.0, and the
server adopting it. Both are long past (the server is on contracts 3.20.0 and ui-builder 3.91.0),
so nothing outside this repository blocks the cutover any more. What blocked it in practice was
that #329 was one breaking change across two repositories, with no way to land it switched off
and no measurement of what would change for each catalog. It is now 196 commits behind `main`, with
eight files in conflict. Its branch is still the best reference for the *deletion* step below, but
it should be redone rather than revived.

## The flag

`CatalogOwnership` (`:ui-builder-export`) says which catalogs answer for themselves: `none` (the
default), `all`, or a comma-separated list, parsed like the server's
`--ui-builder-published-catalogs` so an operator reads the two the same way. An unset value is
`none`, never `all`.

`--ui-builder-published-catalogs` already decides where a catalog's **definition** comes from, and
production has run every catalog published since 3.27.0. `CatalogOwnership` covers everything else
this repository still decides by catalog id:

| Fact | Flag off (today) | Flag on (catalog-owned) |
| --- | --- | --- |
| Definition | published file, else the synthesised Kotlin catalog | published file only; a catalog with no usable file is refused at startup, and its generator never runs |
| Platform | declared, else the synthesised catalog's | declared, or the catalog is refused |
| Pins of designs made against a synthesised catalog | computed from the generator | read from `LegacySynthesisedReferences`, frozen and held equal to the generator by a test |
| Seed templates | `UiBuilderNewDesignSeed`'s `when` on the id | the documents the policy's `templates` names (`CatalogSeedTemplates`); the generic blank if it names none |
| New-design chooser | `newDesignCatalog`'s per-id table and `NEW_DESIGN_CATALOG_ORDER` | `catalogOwnedNewDesignCatalog`, in served order |
| Export route | platform and root id (`RecordFreeExport`, `CATALOG_SYSTEM_IDS`) | the catalog's `composeSourceExport` declaration (`CatalogExportRouting`); no declaration, no export |
| Canvas in the browser | the catalog's published renderer runtime | unchanged (already catalog-owned) |
| Canvas in a JVM host | the in-process Kotlin adapters | unchanged until the move below |

Where each reader takes the flag:

- `CurrentM3UiBuilderCatalogExecutor.Builder.catalogOwnership` (`:ui-builder-runtime`).
- `UiBuilderNewDesignSeed.templateIds(id, ownership, published)` and
  `UiBuilderNewDesignSeed.document(…, ownership, published)` (`:ui-builder-export`). The old
  overloads are untouched, so the server's compiled calls keep their meaning.
- `CatalogExportRouting.route(catalog, ownership)` (`:ui-builder-export`).
- The browser chooser reads the shell's `<meta name="ui-builder-catalog-ownership">` (or a
  `?catalogOwnership=` override; `Main.kt`'s `catalogOwnership()`); the host that serves the page
  is the one that knows whether it seeds owned catalogs from their templates.

### What the server has to do to flip it

The server half is one PR to compose-preview-server, all behind the same value and default off:

1. Parse `--ui-builder-catalog-ownership` / `SERVE_UI_BUILDER_CATALOG_OWNERSHIP`, default `none`. Refuse
   an id that is not also in `--ui-builder-published-catalogs`, for the same typo reason that flag
   refuses ids it does not serve.
2. Hand it to the executor's `Builder.catalogOwnership`.
3. Have `ServeCatalogStore` fetch the documents a published `templates` list names, beside
   `ui-builder.json`, and read them with `CatalogSeedTemplates.read`. Pass them, with the flag, to
   the new `UiBuilderNewDesignSeed` overloads in `ServeUiBuilderCreate` and `ServeWeb`, and validate
   a template id against `templateIds(id, ownership, published)`.
4. Compute `composeExportFor` with `CatalogExportRouting.exportsCompose(route(catalog, ownership)) {
   <today's expression> }`, and ask `RecordFreeExport` as `recordFreePlatform(route)`.
5. Write `<meta name="ui-builder-catalog-ownership" content="<value>">` into the builder shell's head.

Each step is a no-op at `none`, which is what makes it safe to land before any catalog is ready.

## The evidence: `CatalogCutoverReadinessTest`

`ui-builder-runtime/src/test/.../CatalogCutoverReadinessTest.kt` answers "could we flip it?"
against what each catalog repository actually publishes. `scripts/capture-catalog-cutover-fixtures.sh`
captures each catalog's `ui-builder.json`, `components.json`, seed documents and renderer runtime id
from one delivery commit into `ui-builder-runtime/src/test/resources/catalog-cutover/<id>/`, with
the commit in `source.json`. Re-capturing is a reviewed change, like any golden.

With `CatalogOwnership.ALL`, it asserts outright that:

- all five catalogs (`m3-catalog`, `wear-m3`, `remote-m3`, `a2ui-catalog`, `glimmer-catalog`) and
  `compose-foundation` compose from their own files;
- every catalog is served as `published`, **no synthesised generator runs**, and each is drawn by
  the renderer runtime its own repository published;
- every frozen legacy pin still resolves, so no stored design strands (#796), and the frozen pins
  equal what the generators produce today;
- an owned catalog with no published file, or no declared platform, is refused rather than guessed;
- **every seed template every catalog publishes validates against the catalog it is served as**;
- with the flag off, seeds and the chooser are exactly what they were.

And it asserts the **gap ledger** exactly, so the list can only shrink deliberately.

## The gap ledger

As captured on 2026-10-07, with `a2ui-catalog` and `glimmer-catalog` re-captured on 2026-10-08. Each
line is work in a catalog repository, not here.

| Catalog | Gap | Fix, in that repository |
| --- | --- | --- |
| `wear-m3` | declares no `composeSourceExport`, so an owned Wear catalog offers no export | add `"composeSourceExport": {"adapter": "wear-compose-screen", "version": 1}` to `ui-builder.policy.json`, **after** the server that serves it ships `CatalogExportRouting` (a server that does not know the id refuses the catalog's exports)  The server has shipped it (3.111.0, on 3.95.0): declared in yschimke/wear-m3-catalog#733 |
| `wear-m3` | preview.coo.ee's shadow report: the published catalog lacks the colour properties the Wear exporter reads (`card`/`edge-button` `containerColor`, `contentColor`; `progress-indicator` `indicatorColor`, `trackColor`; `icon` `color`), and makes `screen-scaffold`'s `content` slot required | declared, drawn by the runtime adapters and the slot relaxed in yschimke/wear-m3-catalog#733 |
| `wear-m3`, `m3-catalog` | `variable-font-text` is in the Kotlin catalogs only: it calls a declaration flexpress generates at export, not a library composable, so no catalog record has it | catalog-specific for now: each catalog publishes it as a policy builtin (yschimke/wear-m3-catalog#733, yschimke/m3-catalog#523) |
| `remote-m3` | the same | `{"adapter": "remote-compose", "version": 1}`, same ordering  Declared in yschimke/remote-m3-catalog#40 |
| `glimmer-catalog` | declares no `composeSourceExport` | glimmer has no emitter in this build at all, so an owned glimmer catalog offers no export until one exists here and the catalog declares it. Its seeds (`glasses-card`, `glasses-list`, `glasses-prompt`) are published |
| `m3-catalog` | preview.coo.ee never serves its published `ui-builder.json`: the image configures an authored 34-component record for it, which a configured record always won with, and the file joins only on the 112-component record published beside it | compose against the delivery branch's record and prefer it once the file composes (yschimke/compose-preview-server#1461); the live pair composes 110 components |
| `m3-catalog` | the published `adaptive-navigation` seed holds `m3/icon` nodes without an `imageVector`, which the generic export refuses | give the template's icons a vector (the Kotlin `AdaptiveScreenTemplates` seed exports), or export icons from the asset registry |
| `remote-widgets` | declares no `composeSourceExport` (it now has a delivery branch, and preview.coo.ee serves it from its published `ui-builder.json`) | `{"adapter": "remote-compose-launcher-widget", "version": 1}`, declared in yschimke/remote-m3-catalog#40 |

`declaring the export route is all the export half needs` proves the first two rows are the whole
fix: with the declarations added, every published seed of those catalogs exports through the
emitter it reaches today by id. `a2ui-catalog` closed its rows by publishing both: the
`a2ui-program` declaration and its seeds, whose `a2ui-column` is held equal to the Kotlin built-in
by `a2ui-catalog's published a2ui-column is the built-in seed it replaces`.

Not in the ledger, because the flag keeps them and they are the builder's own: the packaged
`compose-foundation-components-v1.json` record (layout, shape and asset call sites). m3-catalog's
published `compose-foundation` record does not carry them yet; a test pins that, so that when it does,
the server can read the builder vocabulary's record from the delivery branch too.

Also not in the ledger, and still true: the policy's `templates` entries are bare paths, so an owned
chooser card names a template after its file rather than the label and supporting text the built-in
table carries. Object-shaped entries (`UI_BUILDER_SEED_TEMPLATES.md`) fix it; until then it is
copy, not correctness.

## Launcher widgets: `remote-widgets`

`remote-widgets` is the phone launcher widget design system remote-m3-catalog publishes from
`:widget-catalog` (its `docs/design/REMOTE_WIDGETS_UI_BUILDER.md`), with
`LauncherWidgetCodeExporter` and catalog-declared frame sizes on this side (compose-ui-builder#556). It arrived after
the flag and is the catalog the cutover fits best:

- **Nothing here synthesises it**, so its definition was only ever its own published file. It has
  no Kotlin catalog to delete and no legacy pin to freeze.
- **Its sizes are already catalog data.** `frame.geometry.sizesDp` is the launcher grid (`1x1` …
  `5x2`), read by `UiBuilderFrameGeometry.sizes` for the frame picker. Under the flag the exporter
  names its preview from the same block (`CatalogExportRouting.frameSizes`) instead of
  `LauncherWidgetGrid`, the Kotlin copy of the table.
- **It publishes its own seeds** (`launcher-widget-2x1`, `counter-widget`). Off the flag it is
  offered `hello-widget`, the builder's Kotlin launcher seed (`LauncherWidgetTemplates`,
  compose-ui-builder#562); under the flag it is offered the catalog's own, and the Kotlin seed is
  one more copy to delete.
- **Its export is the last id-routed emitter.** Off the flag `RecordFreeExport` reaches the launcher
  emitter only because the root is spelled `remote-widgets/launcher-widget`
  (`isLauncherWidget()`). Under the flag the catalog declares
  `CatalogExportRouting.LAUNCHER_WIDGET` (`remote-compose-launcher-widget` v1) and
  `RecordFreeExport.generate(document, route, frameSizes)` writes the widget around whatever screen
  root the catalog publishes. `LauncherWidgetCutoverTest` renames the root to one this build has
  never seen and shows it still exports declared, and does not when routed by id.

`LauncherWidgetCutoverTest` holds all of this against the policy and templates the repository wrote,
captured by `scripts/capture-catalog-cutover-fixtures.sh` from the source repository with
`"published": false` until the delivery branch exists. The ledger row above is the branch.

**Recommendation: flip `remote-widgets` first.** It has nothing to fall back to and nothing to
delete, so owning it is only gain, and it is the smallest proof of the whole path on a real box.

## Shadow mode: measuring a flip on the box before making it

`CatalogCutoverShadow` (`:ui-builder-runtime`) is the readiness check as a library call, so a
deployment can run it against what a catalog publishes *today* instead of against fixtures captured
on one day. `CatalogCutoverProbe` delegates to it, so the gap ledger above and a box's report are
the same measurement.

`CatalogCutoverShadow.report(catalogId, published, templates, …)` answers two questions and changes
nothing served:

- **`findings`**: would serving the catalog owned work? Seeds it publishes, that they validate,
  its declared export route, and that every seed exports through it.
- **`differences`**: what would an editor see change if the Kotlin catalog were deleted? The Kotlin
  catalog as this build serves it, against the published one as served owned (both with the
  builder vocabulary added, so that is never reported as lost), component by component: role,
  traits, modifiers, properties and slots. Null for a catalog nothing here synthesises.
- **`losses`**: the differences that take something away. That covers:
  - a component only the Kotlin catalog has;
  - a changed role;
  - a dropped trait, modifier, property, slot, or a slot's accepted role or trait;
  - a property type that drops an alternative (`["boolean","object"] -> "boolean"` is the loss of
    binding it to state);
  - a property that becomes required;
  - allowed values or a slot cardinality that narrow.

  Additions and relaxations are not losses.

**Ready means no findings and no losses.** Until 2026-10 it meant no findings alone, and
preview.coo.ee reported `remote-m3` "ready to own" while owning it would have dropped
`RemoteAuthorable`, `remoteCall` and state-bound `enabled` from about 25 components.

On compose-preview-server it is a per-catalog `"shadow": true` beside `"owned"` in `catalogs.json`'s
`uiBuilder` block: the box keeps serving what `owned` says, logs the report at startup and serves it
from the admin config route. Flip a catalog when its shadow report has been clean for long enough to
trust, not when a capture happened to be.

## Switching over

Not done, and deliberately not by this change. When the ledger rows for a catalog are closed and the
server half has shipped:

1. Set `SERVE_UI_BUILDER_CATALOG_OWNERSHIP=<that catalog>` on one box. It is per catalog and
   reversible; the legacy pins mean a design created either side of the switch opens on both.
2. Re-capture, re-run `CatalogCutoverReadinessTest`, and delete the closed ledger lines.
3. When all five are owned for a release with no reversal, set the default to `all`.

## Moving the code

The code moves in **two directions**, and most of it is deleted rather than moved. A fact a catalog
already publishes is deleted here once the flag is on. Only code that has no copy in a catalog
repository yet has to be carried across, and the emitters stay here on purpose.

### What is deleted, not moved

The catalog repositories already own a copy of all of these. The deletion is safe exactly when the
readiness test is green with that catalog owned, and `ledger empty for it` is the gate:

| Here | Lines | Owned copy | Delete when |
| --- | --- | --- | --- |
| `WearM3Catalog.kt` (`wearM3Catalog`, `wearDesignTokens`) | 1,684 | wear-m3-catalog's `ui-builder.policy.json` + record | `wear-m3` owned for a release |
| `RemoteM3Catalog.kt` | 802 | remote-m3-catalog's `remote-catalog/ui-builder.policy.json` | `remote-m3` owned for a release |
| `A2uiCatalog.kt` | 897 | a2ui-catalog's `ui-builder.policy.json` | `a2ui-catalog` owned for a release |
| `UiBuilderTemplates.kt`'s Wear screen, Wear list and widget builders, `WearWidgetSample`, `AdaptiveWearWidget.newDocument` | ~800 | `ui-builder/designs/*.json` in wear-m3-catalog and remote-m3-catalog (round-trip tested there) | the matching catalog owned |
| `AdaptiveScreenTemplates` | 491 | m3-catalog's `ui-builder/designs/*.json` | `m3-catalog` owned, after its `adaptive-navigation` ledger row |
| `UiBuilderNewDesignSeed`'s `when`s, `newDesignCatalog`'s table, `NEW_DESIGN_CATALOG_ORDER`, the home screen's three-id list | ~250 | the catalog-owned readers above | all catalogs owned |
| `synthesisers`, `LEGACY_*` fallbacks in `ProductionUiBuilderRuntime` | — | — | all catalogs owned; `LegacySynthesisedReferences` **stays** (it is the only record of those pins) |
| `docs/design/fixtures/ui-builder/{wear-m3,remote-m3,a2ui-catalog}-capabilities-v1.json` goldens and their `--strict` gates | — | the catalog-cutover fixtures | together with their generator, as contract phase 5 says |
| `LauncherWidgetTemplates` and its `UiBuilderNewDesignSeed` branches | ~145 | remote-m3-catalog's `widget-catalog/ui-builder/designs/*.json` | `remote-widgets` owned; move `hello-widget` into that directory first if it should stay on offer |
| `isLauncherWidget()`'s root-id test, in `RecordFreeExport` and in the editor model's minimum frame size, and `LauncherWidgetGrid`'s cell table | ~60 | `remote-widgets`' `composeSourceExport` declaration and `frame.geometry.sizesDp` | `remote-widgets` owned; the editor's minimum then reads "this catalog declares sizes" from the frame block it already parses |

`blankUiBuilderDocument`, `ComposeFoundationCatalog` and the packaged `m3-catalog` capability stay:
they are the builder's floor, not knowledge of somebody else's catalog.

### What stays here, by design

- **The emitters**: `WearScreenCodeExporter`, `WearWidgetCodeExporter`, `WearContentEmitter`,
  `RemoteContentEmitter`, `RemoteMaterial3`, `A2uiComposeExporter`, `A2uiDocumentExporter`. A catalog
  names an interpreter this build ships and never ships code to the host
  (`CatalogComposeSourceExportAdapters`). What changes is how they are *chosen*: by declaration,
  through `CatalogExportRouting`. Their Wear- and Remote-specific constants
  (`NATIVE_ONLY_COMPONENT_IDS`, the widget container ids) become the interpreter's own vocabulary,
  checked against the declaring catalog's policy, rather than a routing rule.
- **The Remote Compose lane** (`RemoteComposeCanvas`, `RcComposePlayer`, `remote-compose/*`), the
  sanctioned exception in `UI_BUILDER_CATALOG_AGNOSTICISM.md` §7.

### What has to be carried across: the in-process Wear canvas

The browser already draws every published catalog through that catalog's own renderer runtime
(`CatalogRuntimeCanvas`), so the editor's canvas is already catalog-owned on the web. The Kotlin
canvas remains for the **JVM hosts** — the desktop app, the IntelliJ plugin's host, the server's
PNG render bundle, and the host bridge — which cannot run a Wasm runtime:

| Here | Lines | Destination |
| --- | --- | --- |
| `UiBuilderRenderer.kt`'s ~25 `"wear-m3/…"` branches and the `remote-m3/widget-container-*` placement | ~400 of 3,161 | already duplicated in wear-m3-catalog `ui-builder-wear-adapters` (`WearCanvasAdapters.kt`, `WearScreenAdapters.kt`, `WearWidgetCanvasAdapters.kt`) as `CanvasAdapterRegistry` adapters |
| `WearCanvasComponents.kt` | 1,085 | the same module |
| `jvmMain/preview/Wear*Preview.kt`, `WearPreviewDevices.kt` | — | wear-m3-catalog's `catalog` previews (they are stickers of the catalog, not of the builder) |
| `jvmTest/Wear*Test.kt`, `Remote*CanvasTest.kt` that draw | — | `ui-builder-wear-adapters/src/jvmTest` (`WearAdapterPropertyParityTest` is the precedent) |

The mechanism is the one #231 designed and the renderer SDK already has a seam for:

1. **wear-m3-catalog publishes its adapters as a JVM artifact.** `ui-builder-wear-adapters` (and the
   foundation adapters it depends on) already compile for `jvm()`; add the maven-publishing plugin
   and publish to the catalog's `-out` Maven branch the way the CMP ports are published. The same
   for remote-m3-catalog's copy, or better, make remote-m3-catalog depend on wear's instead of
   keeping the duplicate.
2. **The JVM hosts load adapters through a `UiBuilderCanvasAddon` seam** (#231's name): a
   `ServiceLoader`-discovered `CanvasAdapterRegistry` contribution, consulted by `RenderCanvasNode`
   before the compatibility table, exactly as `UI_BUILDER_CATALOG_RENDERER_RUNTIME.md` already
   orders it. The desktop app and the render bundle add the catalog artifacts they ship; a host
   that adds none draws Wear as placeholders, which is the honest answer for a host that bundles no
   Wear library.
3. **Prove equivalence while both exist**: `WearCanvasDrawsRealComponentsTest` run twice, once on
   the in-process branches and once with only the addon registered, comparing inspection snapshots.
4. **Delete** the branches, `WearCanvasComponents.kt` and the `wearcmp` dependency from
   `:ui-builder`, with `wear-port-canvas-only.sh` (#231) holding the line afterwards.

That move is not behind `CatalogOwnership`: it changes which jar draws, not what is drawn, and its
proof is step 3, not the readiness test.

### Keeping history

These files came from compose-preview-server with history, and the moves above should not lose it.
For a file that moves to a catalog repository rather than being deleted, use
`git filter-repo --path <file> --path-rename <file>:<destination>` on a scratch clone, then merge
that unrelated history into the catalog repository (`git merge --allow-unrelated-histories`), so
`git log --follow` and `git blame` keep answering there. For a deletion, the commit message names
the catalog commit that owns the fact, so the trail is one click.

### Order

1. **This change**: the flag, the readers, the readiness test, the plan. Default off. *Done.*
2. **compose-preview-server**: the server half above, default `none`. No behaviour change.
3. **Catalog repositories**: close the ledger rows (the `composeSourceExport` lines only after step 2
   has shipped).
4. **Flip** per catalog, by environment variable, on one box first.
5. **Delete** the Kotlin catalogs, templates and tables, one catalog per PR, each citing a green
   readiness test with that catalog owned.
6. **Move the canvas** through the addon seam. Independent of 3–5, and the largest piece.
