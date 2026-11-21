# Catalog document formats: one seam instead of a branch per catalog

**Status: step 1 taken, 2026-11-21.** Follows
[`UI_BUILDER_CATALOG_AGNOSTICISM.md`](UI_BUILDER_CATALOG_AGNOSTICISM.md), which moved a catalog's
*facts* into its published declarations, and
[`UI_BUILDER_CATALOG_RENDERER_RUNTIME.md`](UI_BUILDER_CATALOG_RENDERER_RUNTIME.md), which moves a
catalog's *drawing* into a runtime the catalog builds. This document does the same for everything a
catalog does **besides** drawing: what its designs export as, what the editor allows in them, and
what it reports about them.

## The problem

Three catalogs whose designs are not Compose source arrived one after another: Home Assistant
dashboards (`lovelace`), Wear OS watch faces (`watchface`, and the DWF runtime pane), and Kobento
(`kobento/*`, Kobento component JSON). Each one was added the same way: a new exporter in
`:ui-builder-export`, then `if (catalog is X)` in every place that cares. Today that is:

| Where | Branches on a specific catalog | What they decide |
| --- | --- | --- |
| `UiBuilderEditorState.kt` | 25 | which exporter fills the code pane and the Issues panel; whether state variables, bindings and "make component" are allowed; how a palette entry is previewed; how a colour is held |
| `UiBuilderEditor.kt` | 9 | the code pane's caption and "no source" heading; which pieces promote where |
| `ProductionUiBuilderRuntime.kt` | 9 | which export flags a catalog advertises; the Kobento artifact |
| `RecordFreeExport.kt`, `CatalogExportRouting.kt` | 9 | which emitter a platform routes to; "is a data document" |
| canvas, inspectors, device presets, new-design types, host bridges | 19 | device groups, colour controls, Lovelace import in the web host |

That is 71 branch sites across 15 core files, plus about 15,000 lines of format-specific code:
12,761 for watch faces (exporter, importer, validation, canvas, palette previews, runtime pane,
publish panel), and about 600 each for Lovelace, A2UI and Kobento.

The cost goes past this repository. Every new format so far has also needed:

1. **a contracts release.** `ExportFormatV1` grew `JSON`/`RC`, then `WFF_XML`/`WFF_BUNDLE`/`WFF_APK`,
   then `KOBENTO_JSON`, each with its own `ExportCapabilitiesV1` flag;
2. **a server change.** Each enum entry breaks an exhaustive `when` in compose-preview-server, and
   compose-ui-builder's design-render lane builds that server against this checkout, so the lane
   is red until the server catches up. That happened with Kobento: compose-ui-builder#1334, then
   compose-preview-server#2055;
3. **a host executor in the server** (`WatchFaceBundleExportExecutor`, `LovelaceJsonExportExecutor`,
   `A2uiJsonExportExecutor`), chained around the runtime's.

The fourth such catalog would cost the same again. The aim is that it costs a declaration in its
policy and one self-contained module, and nothing in the editor, the runtime, contracts or the
server.

## What is generic already, and the rule this extends

- A catalog's components, slots, properties, traits, templates, frame geometry and canvas adapters
  are declarations (the agnosticism audit).
- Compose source export is already chosen by declaration: `composeSourceExport {adapter, version}`
  resolves against `CatalogComposeSourceExportAdapters`, "a name, never Kotlin", and an unknown
  declaration is *unsupported*, never a guess.
- Drawing moves into a catalog-built runtime behind `:ui-builder-renderer-sdk`.

The rule this document applies to the rest: **a catalog declares what it is, and the builder
dispatches on the declaration through one interface.** No core file names a catalog, a platform
word other than through that interface, or a format's wire spelling.

## The design

### 1. A document format is an interface, chosen by declaration

```kotlin
/** One way a catalog's designs leave the builder that is not Compose source. */
interface CatalogDocumentFormat {
  val id: String                 // "kobento-component-json", "wff-xml", "lovelace-config", "a2ui-messages"
  val version: Int
  val label: String              // "Kobento component JSON": code pane caption, export menu, refusal heading
  val mediaType: String          // "application/json"
  val encoding: ExportEncodingV1 // UTF8 | BASE64
  val extension: String          // "json"

  /** Writes [document], or says why not. [policy] is the catalog's `statusSemantics.<id>` block. */
  fun export(document: UiBuilderDocument, policy: JsonObject): Result

  /** Advisories that do not block export (a complication drawn past its box). */
  fun notices(document: UiBuilderDocument, policy: JsonObject): List<Diagnostic> = emptyList()
}
```

The catalog names it in its policy, beside `composeSourceExport`:

```json
"statusSemantics": {
  "documentFormat": { "adapter": "kobento-component-json", "version": 1 },
  "kobento": { "staticParams": {}, "clickable": [] }
}
```

`CatalogDocumentFormats` resolves the declaration as `CatalogComposeSourceExportAdapters` does: to
`Supported`, `Unsupported(adapter, version)` or `NotDeclared`. It is a fixed registry that the host
assembles, not a plugin loader. Every core branch above becomes one lookup:

- **the code pane and the Issues panel** call `format.export`. Its refusals are the issues, its
  label is the caption and the "No <label>" heading, and the Compose emitter is not asked;
- **the runtime** advertises the format (below) and serves it by calling the same `export`;
- **the server** needs one executor for every format, not one per format.

### 2. What the editor allows is data, not a platform test

Every editor rule now behind `if (platform == LOVELACE || platform == WATCH_FACE)` is a yes/no fact
the catalog can state. These go in a `statusSemantics.editing` block, and each default is today's
Compose behaviour:

| Field | Default | Watch face | Lovelace | Replaces |
| --- | --- | --- | --- | --- |
| `designState` | `true` | `false`, "a watch face's values come from the watch" | `false`, "use the entity picker" | `SetStateVariable` refusals |
| `stateBinding` | `true` | `false` | `false` | `canBindToState` |
| `makeComponent` | `true` | `true` | `false`, with its sentence | `planComponent` |
| `palettePreview` | `"frame"` | `"scene"` | `"frame"` | `previewDocument` |
| `deviceGroups` | the platform's | `["watch"]` | `["dashboard"]` | `relevantDeviceGroups` |

The refusal sentence is part of the declaration, so the editor never writes one per catalog.

### 3. One export format on the wire

In compose-preview-contracts, add **one** generic member, `ExportFormatV1.DOCUMENT` (`"document"`),
and one capability, `ExportCapabilitiesV1.document: DocumentExportV1?`, which carries
`{adapter, version, label, mediaType, encoding, extension}`. A client asks for `document` and gets
the catalog's own format. The server's routes take the extension and media type from the capability
rather than a `when`.

That is the last contracts change any document format needs. The existing members (`JSON`, `RC`,
`WFF_*`, `KOBENTO_JSON`) stay for binary compatibility and are served by the same path. New formats
never add one.

### 4. Each format is its own module

```text
:ui-builder-format-api        the interface, the registry, the editing-policy reader (no format)
:ui-builder-format-kobento    KobentoComponentJsonExporter
:ui-builder-format-lovelace   LovelaceConfigExporter (+ importer)
:ui-builder-format-a2ui       A2uiDocumentExporter
:ui-builder-format-wff        WatchFaceXmlExporter, importer, validation, complication bounds
```

`:ui-builder`, `:ui-builder-export` and `:ui-builder-runtime` depend on `:ui-builder-format-api` only.
The applications (`:ui-builder` web entry, `:ui-builder-desktop`, and the server through the runtime
artifact) list the formats they ship in one place. A format module depends on the API and the
document model, never on the editor.

Watch-face *drawing* (`WatchFaceCanvas`, `WffPreviewExpressions`, the palette scenes) is not export.
It belongs in the watch-face catalog's renderer runtime, through the SDK's `CanvasAdapterRegistry`,
like every other catalog's drawing. Its two panes (the DWF runtime and publish-to-watch) are host
features declared by the catalog (`statusSemantics.panes`), and the editor mounts them by id. They
are the one part that is product UI rather than a format.

### 5. A gate so it stays generic

"Make sure" needs a check, not a convention. Add `CatalogAgnosticCoreTest`:

- **no new specific references:** it counts references to `UiBuilderCatalogPlatform.{WATCH_FACE,
  LOVELACE, A2UI}`, `Kobento*`, `WatchFace*`, `Lovelace*` and `A2ui*` in the core source sets
  (`:ui-builder` editor and canvas, `:ui-builder-runtime`, `:ui-builder-export` outside its format
  files). The committed count may only go down, so each migration step lowers it and a new branch
  turns the build red;
- **format modules stay leaves:** a Gradle rule that no core module depends on a
  `:ui-builder-format-*` module except the application entry points.

The end state is a count of zero, after which the test asserts zero.

## Could the formats leave this repository entirely?

Yes, once the seam exists, and it doesn't decide anything sooner. A format module that depends only
on `:ui-builder-format-api` and the document model can be built in its catalog's repository
(kobento-catalog, the watch-face catalog) and published as a KMP artifact (JVM for the server and
desktop, Wasm for the web editor). The applications then pin it by version.

The trade is a pin bump per catalog per release, the same cost the server already pays for
compose-ui-builder. That suits a catalog that changes on its own schedule (Kobento follows Kobento)
and doesn't suit one that moves with the builder. The seam keeps both options open. Without it,
neither is possible.

Executing a catalog's exporter inside its sandboxed Wasm renderer runtime, as drawing does, works
for the editor but not for the server, which exports on the JVM. Export stays a linked artifact,
not runtime-loaded code.

## Order

1. ~~**The seam, and Kobento onto it.**~~ Taken. `CatalogDocumentFormat`, the
   `CatalogDocumentFormats` registry and `BuiltInCatalogDocumentFormats` (the one list of shipped
   formats); `KobentoDocumentFormat` wraps `KobentoComponentJsonExporter`. The editor's code pane,
   Issues and captions, the runtime's capabilities and `ProductionUiBuilderExportExecutor` all go
   through the registry, and no core file names Kobento. `CatalogAgnosticCoreTest` is the gate:
   236 lines across 28 core files at its first count, almost all of them watch faces, Lovelace and
   A2UI. It also asserts the core names no Kobento, apart from the contract's wire names
   (`KOBENTO_JSON`, `kobentoJson`), which step 2 removes. Three differences from the plan above:
   - The seam lives in `:ui-builder-export` (package `ee.schimke.composeai.uibuilder.export`), not
     a new `:ui-builder-format-api` module. A new module is a new published coordinate for the
     server to resolve, and with one format it buys nothing. The split comes with the second
     format to move, when a module boundary has something to separate.
   - A catalog without a `documentFormat` declaration that publishes a format's policy block
     (`statusSemantics.kobento`) resolves to that format's first version. Published catalogs then
     keep exporting while kobento-catalog adds the declaration, which it still should.
   - The `editing` block (§2) waits for step 3: Kobento needs none of its rules, and Lovelace and
     watch faces are what it replaces.
2. **Contracts `DOCUMENT`** (§3), then the server's single executor. That is one last contracts
   release and server change, so that no later format needs either.
3. **Lovelace and A2UI** onto the seam. Each is one exporter plus a handful of branches.
4. **Watch faces.** Export, import and validation onto the seam. Drawing goes into the watch-face
   catalog's renderer runtime. The two panes become declared panes. This is the largest step and the
   one the gate matters most for.
5. **Optionally**, move a format module into its catalog's repository (above).

Each step is independently shippable and lowers the gate's count. Step 1 is small enough to land as
one pull request and proves the interface on the format whose code is freshest.
