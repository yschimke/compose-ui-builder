# UI Builder beyond Compose: Home Assistant Lovelace dashboards

**Status: handover note, 2026-10-16.** It is not a decision record. It hands one piece of work
to a new session: making UI Builder edit things that are not Compose. The first such thing is
Home Assistant Lovelace dashboards (views, sections and cards), saved back to Home Assistant.
It came out of work on [harc](https://github.com/yschimke/homeassistant-remotecompose), the
dashboard-first Home Assistant client.

## The ask

UI Builder has two output kinds today: Jetpack Compose (Kotlin) and Remote Compose (`.rc`). This
note proposes a third kind whose output is not code or a player document, but a **data document
another system owns**. The first case is a Lovelace dashboard config, which is what Home
Assistant's `lovelace/config` returns and `lovelace/config/save` accepts.

A user should be able to:

1. Open a Home Assistant dashboard in UI Builder, with each card drawn as it really looks where
   harc can render it, and a labelled placeholder where it can't.
2. Drag cards within and between sections, reorder sections, and add or remove cards from a
   palette.
3. Resize cards on the sections grid (`grid_options`) and edit a card's options in the inspector.
4. Save, and get back the dashboard JSON for the host to write to Home Assistant.

**Lovelace templates** are the second deliverable: reusable sections or views with entity slots,
such as "a heating section for one boiler" or "a room view". They are filled with a real home's
entities when placed. These are UI Builder's seed templates, applied to Lovelace.

**Not in scope:** the free-form canvas. A Lovelace design is a constrained tree, and the builder
must stop the user making a layout Home Assistant cannot hold.

## Why UI Builder can take this: A2UI already did

UI Builder already has a non-Compose output: **A2UI**, which lowers a design to A2UI v0.9 JSON
messages. A Lovelace target follows the same path. The paths below are as of `5384e9ff`.

| Seam | Where | A2UI precedent | Lovelace |
|---|---|---|---|
| A catalog's platform | `ui-builder-export/.../export/UiBuilderCatalogPlatform.kt:16-51` (`MOBILE`, `WEAR`, `REMOTE_COMPOSE`, `A2UI`) | `A2UI("a2ui")` | add `LOVELACE("lovelace")` |
| Export lane | `export/CatalogExportRouting.kt` (`recordFree` 46-52, `route()` 84-100) | `a2ui-program` | `lovelace-config` |
| Exporter | `export/RecordFreeExport.kt:186-193, 259-275, 316-318` | `A2uiDocumentExporter` (lowering, refusals with reasons) | `LovelaceConfigExporter`: the node tree to Lovelace JSON, refusing what Lovelace cannot hold |
| Server capability | `ui-builder-runtime/.../ProductionUiBuilderRuntime.kt:418-431` (`remoteJson` for A2UI), `:1305-1328` (format dispatch) | reuses `JSON` | reuse `JSON`; YAML is a later, contract-level change (`ExportFormatV1` lives in compose-preview-contracts) |
| Editor export | `ui-builder/.../editor/UiBuilderExportActions.kt:18-60`, `wasmJsMain/.../BrowserExportHost.kt` | `A2uiJson` on the `json` route | `LovelaceJson` |
| New-design chooser | `NewDesignTypes.kt:115`, `UiBuilderEditorToolbar.kt:930` | "A2UI surface" | "Home Assistant dashboard" |

The export routes and MCP tools live in compose-preview-server (`:server`, `:mcp`). A new format
there follows the contracts change, but reusing `JSON` avoids touching it at first.

## The catalog: structure is expressible today

A Lovelace catalog is a normal published catalog (`ui-builder.policy.json`, `catalog.spec.json`;
see [UI_BUILDER_CATALOG_CONTRACT.md](UI_BUILDER_CATALOG_CONTRACT.md)). It would be owned by the
homeassistant-remotecompose repo, as its `ha-cards` catalog already is. The structure rules fit
the slot model `CapabilityCatalog.kt:188-221` already enforces (`acceptedRoles`, `acceptedTraits`,
`cardinality`):

- **`lovelace/dashboard`** is the `screen-root` builtin and the design's one root. A dashboard
  holds several views (its tabs), and a design may have only one root
  (`validateTopology`, `PersistentUiBuilderServiceSupport.kt:757-766`). It has a `views` slot that
  accepts only the `LovelaceView` trait, plus the dashboard's own `title`.
- **`lovelace/view`** has the trait `LovelaceView`, a `sections` slot that accepts only the
  `LovelaceSection` trait, and properties `title`, `path`, `icon` and `max_columns`. The editor
  shows one view at a time, like Home Assistant's tabs.
- **`lovelace/section`** has the trait `LovelaceSection`, a `cards` slot that accepts only
  `LovelaceCard`, and properties `title`, `column_span` and `row_span`.
- **`lovelace/card/<type>`** has the trait `LovelaceCard`, one component per card type (`tile`,
  `entities`, `gauge`, `thermostat`, …), and properties from the card's config schema. Every card
  also has `grid_options.columns` and `rows` with `allowedValues` on the 12-column grid.
- **`lovelace/card/custom`** has the trait `LovelaceCard`. It is an opaque card holding raw config
  for anything the catalog doesn't model (`custom:*` HACS cards). It round-trips untouched.

The exporter turns each node's properties back into the card's JSON. One rule matters above all:
**round-tripping must not lose data.** Home Assistant cards carry fields no catalog will model.
Each node keeps the card's original config and overlays only what the editor changed, the way
harc's `CardConfig.raw` keeps the whole object.

## The canvas: draw the real card

The canvas should look like the dashboard, not a wireframe. This is where Home Assistant differs
from A2UI, and where harc already has what is needed. In order of effort:

1. **Placeholders (free).** `canvasMapping` points the dashboard, views and sections at existing adapters
   (`layout/column`, `layout/box`), and cards fall back to `NativeOnlyPlaceholder` (a named dashed
   box). It is enough to prove structure editing.
2. **Real cards as Remote Compose (recommended).** harc renders every Lovelace card to a Remote
   Compose document, and its design server already serves those documents.
   - **Rendering one card:** `POST /api/schema` on ha.coo.ee gives the schema for any card JSON. A
     sibling route that returns the card's `.rc` with its example values (or live values the host
     pushes) would be small.
   - **Drawing it:** UI Builder already plays `.rc` in the canvas (`remote-compose/document`,
     `UiBuilderRenderer.kt:1188`; `RcComposePlayer`).
   - **The adapter:** a canvas adapter (`CanvasAdapterRegistry`, `JvmCanvasAdapterProvider`) for
     `lovelace/card/*` that fetches and plays that document gives true-to-life cards. They come in
     whichever card style the user picked (LCD, Braun, …), drawn exactly as the app will draw them.
   - **Not every card type renders.** harc's converters are hand-written one type at a time.
     Some types are unfinished (picture, map, the graph cards), and iframe and most `custom:*` HACS
     cards are out of scope. harc shows a custom card that names entities as a generic entities
     card, and anything else as a "not yet supported" placeholder. The adapter must fall back the
     same way, keeping the card's raw config, so a dashboard with unsupported cards still opens,
     arranges and saves. Converting more types is work on harc's side; Home Assistant's own
     frontend in an iframe is a possible later fallback for the rest.
3. **A catalog-owned runtime.** A `nativeRuntimeId` iframe renderer
   ([UI_BUILDER_CATALOG_RENDERER_RUNTIME.md](UI_BUILDER_CATALOG_RENDERER_RUNTIME.md)) only if
   the adapter route proves too limited.

## The host: the builder never talks to Home Assistant

UI Builder must stay unaware of Home Assistant. The **host** owns the connection, the same shape as
[ui-builder-embedding.md](https://github.com/yschimke/homeassistant-remotecompose/blob/main/docs/architecture/ui-builder-embedding.md)
in harc:

```
harc / ha.coo.ee ── dashboard JSON + entity list + card renders ──▶ UI Builder (lovelace catalog)
       ▲                                                                      │
       └──────────── edited dashboard JSON (export, JSON lane) ◀──────────────┘
       │
       └─▶ Home Assistant: lovelace/config/save (with undo), only after a conflict check
```

- **Importing a dashboard** is the inverse of the exporter: Lovelace JSON to a `.uid` design. It
  is lossless by the round-trip rule above. It can live on the host side, as harc's `HaCardsSeed`
  already builds `.uid` documents from card schemas.
- **Saving** belongs to the host. harc already has `HaClient.fetchDashboardConfig`,
  `replaceDashboardConfig` (which returns the previous config, for undo) and a save screen with
  undo. Before saving it must check that nobody changed the dashboard since it was loaded.
- **Entities** come from the host: the entity picker, the device-type suggestions
  (`DeviceMatch`, yschimke/homeassistant-remotecompose#830) and live values for the canvas.

## Lovelace templates

A template is a section or view design whose cards name **slots** (`climate.s0`, `sensor.s1`)
instead of entity ids. That is the slot convention harc's card designs already use. Placing a
template asks the host to bind each slot to a real entity, filtered by domain and device class.
The host can also pick the slots for you from a device: "this boiler" fills a heating section.
Templates are shared through ha.coo.ee the same way card designs are.

## Suggested order

1. **Structure, placeholders, export (this repo).** Add the platform word, the export lane and
   `LovelaceConfigExporter` with round-trip tests against real dashboard JSON. harc's
   `ha-model` fixtures and its demo dashboards are a good corpus. Use a minimal hand-written
   Lovelace catalog for tests.
2. **The catalog (harc).** Publish `lovelace` alongside `ha-cards`, generating card components
   from the card schemas harc already knows.
3. **Real cards on the canvas.** Add the `.rc` card adapter and the design-server route that
   renders a card.
4. **Import and save (harc).** Lovelace JSON to `.uid`, then a host flow: open a dashboard in the
   builder, export, check for conflicts, save, undo.
5. **Templates.** Slot binding on placement.

## Open questions

- **Admin rights:** saving a dashboard probably needs a Home Assistant admin account. This hasn't
  been checked. If so, what does a non-admin get: a draft only, or a request to an admin?
- **YAML-mode dashboards** can't be saved over the API. Read-only, or offer to copy into a
  storage-mode dashboard?
- **Masonry and panel views** (not `sections`) have no grid. Support them read-only first?
- **Where the editor runs:** in harc's own UI (embedded builder, like the card studio) or on
  ha.coo.ee through a browser-side Home Assistant session (as harc's card embed already signs in)?
- **Generalising the target:** the same seam would serve other host-owned data documents. Worth
  naming the target kind generically ("a catalog whose exporter emits the host's own document")
  rather than special-casing Lovelace?
