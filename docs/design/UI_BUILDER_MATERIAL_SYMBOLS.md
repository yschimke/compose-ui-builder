# UI builder: Material Symbols, one row per icon

How the icon picker stops being a list of 11,385 near-duplicates and becomes what
[fonts.google.com/icons](https://fonts.google.com/icons) is — search a name, then customise it —
and how the icons stop being compiled into the Wasm bundle at all. Revisits
[#710](https://github.com/yschimke/compose-preview-server/pull/710)
([#675](https://github.com/yschimke/compose-preview-server/issues/675)).

## What #710 bought and what it cost

#710 answered "the picker only knows 46 icons" by generating every vector the shipped
`material-icons-extended` artifact carries into Kotlin: 11,385 style-qualified entries, each an
`ImageVector` builder, compiled into every module that draws one. Two costs came with it.

- **The bundle.** The production UI-builder Wasm went from 8,737,944 to 26,519,707 bytes; gzip from
  2,372,654 to 5,275,042. That is **~1.6 KB of Wasm per icon**, paid by every visitor on first load,
  for a catalog of which a design uses five.
- **The list.** Style is baked into identity, so `Icons.Filled.Chat`, `Icons.Outlined.Chat`,
  `Icons.Rounded.Chat`, `Icons.Sharp.Chat` and `Icons.TwoTone.Chat` are five rows. Searching `chat`
  returns near-identical pictures with the style spelled into the label, and `m3/icon`.`iconKey`
  became an enum of 11,431 allowed values — which took the MCP catalog *summary* to 241 KB and is
  the entire reason `ServeUiBuilderMcp.SUMMARY_ALLOWED_VALUES` exists.

Both are the same mistake: a variant was modelled as a separate icon, and an icon was modelled as
code.

## The shape

- **A name is the identity.** `search` is one icon. Style, fill, weight, grade and optical size are
  properties of the node, exactly as they are axes on the Material Symbols font — the four sliders
  and two dropdowns the Google Fonts page shows to the right of the grid.
- **Nothing is generated into the bundle.** Outlines come from the **Material Symbols fonts**,
  loaded at runtime and turned into paths by the Skia that already ships beside the Wasm. The
  picker's grid resolves from the font as it browses; the canvas draws what the document's outline
  registry recorded when the icon was chosen, so it cannot disagree with what an export emits.
- **The export stops depending on `material-icons-extended`.** `builderIcon(key)` emits inline path
  data for the icons a design actually uses, so generated Kotlin is self-contained and any axis
  combination is expressible — which `Icons.*` cannot be, because the old Material Icons set has no
  weight, grade or optical-size dimension at all.

## Why a font and not a data file

The obvious runtime alternative is a data file of SVG path strings. It is the right instinct — path
data is about a third the size of the generated code that draws it — but it does not survive
contact with the axis matrix. Measured, on the upstream artefacts:

| What | Raw | On the wire | Covers |
| --- | --- | --- | --- |
| Today's generated Kotlin | 17.8 MB of Wasm | 2.90 MB gz | 11,385 fixed vectors, always downloaded |
| Path data, one style, one axis point | 3.74 MB | 735 KB gz | 6,614 glyphs, one weight, one fill |
| Path data, 3 styles × fill 0/1 × wght 100–700 | **158 MB** | **35 MB gz** | one grade, one optical size |
| **Material Symbols variable font, per style** | **10.68 MB** | **4.8 MB gz** | **every point of all four axes, continuously** |
| Static instance font, one style/fill/weight | 1.39 MB | 0.53 MB gz | one axis point |

The third row is the one that decides it: 42 axis points is a *deployment* of 158 MB of generated
JSON, and moving a weight slider costs another fetch. The fourth row is the same information in
9% of the space, because a variable font *is* the compressed form of the matrix — of the Outlined
font's 10.68 MB, `gvar` (the variation deltas) is 9.28 MB and `glyf` (the default outlines) is
1.05 MB. Buying every axis, including the two this lane was not even going to offer, costs 3× the
default instance rather than 42×.

### Two tiers, because one variable font per style is not a first-load win on its own

The obvious reading of that table — drop the icons from the bundle, fetch the variable font — does
not actually pay for the common case, and the arithmetic has to be stated rather than skipped.
Almost every design names at least one icon, so the font is fetched on first load too:

| First load, gzipped | Wasm | Icons | Total |
| --- | --- | --- | --- |
| Today (#710) | 5.28 MB | in the Wasm | **5.28 MB** |
| Variable font only | 2.37 MB | 4.8 MB | **7.17 MB** — worse, and worse again per extra style |
| **Default instance, upgrading on demand** | 2.37 MB | 0.53 MB | **2.90 MB** |

So the fonts come in two tiers:

- **A static instance per style at the default axes** — `wght 400, FILL 0, GRAD 0, opsz 24`,
  1.39 MB raw and **0.53 MB gzipped**, because dropping `gvar`, `fvar` and `avar` takes 9.28 MB
  off. This is what a design that never touched a slider needs, what the grid paints with, and
  what first load actually pays: **2.90 MB against today's 5.28 MB**. Add `FILL 1` and it is three
  files per style, six for all three styles, ~3.2 MB of deployment.
- **The variable font for that style**, fetched only for an axis value **no static instance
  covers**. Not "any non-default value": `FILL 1` is non-default and has an instance of its own, and
  every migrated `filled/` key lands on it, so filled designs — most designs — stay on the static
  tier. The variable font is for a weight, grade or optical size off the deployed points. It
  replaces the instance in the glyph cache and everything re-resolves.

That is the progressive step this design turns on, not an optimisation deferred to later: without
it the lane makes first load worse. What is still avoided is the 42-instance matrix — six
default-axis instances is a tier, 42 is the data-file deployment under another name.

**Serve the `.ttf` with HTTP compression, not `.woff2`.** 4.8 MB gzipped and 4.0 MB as woff2 are
close enough that it is not worth caring, and Skia decodes sfnt directly — the browser's woff2
decoder is not reachable from `Typeface.makeFromData`.

## The runtime, verified rather than assumed

Every piece of this already exists in what the editor ships.

- **Skia in the browser can instance a variable font and hand back outlines.** The skiko `wasm-js`
  klib (0.150.1, the one behind Compose Multiplatform 1.11.1) declares
  `Typeface.makeFromData`, `Typeface.variationAxes`, `Typeface.makeClone(FontVariation)`,
  `Typeface.makeClone(Array<FontVariation>, Int)`, `Font.getPath(Short)` and
  `Font.getPaths(ShortArray)` — read out of the klib's own declaration table, not inferred from
  documentation. `skiko.wasm` is already served beside `uiBuilder.wasm`, so this costs no
  deployment.
- **Compose can build a vector from a path string on Wasm.** `UiBuilderRenderer.kt` already does it:
  `PathParser().addPathNodes(node.pathData).toPath()` in `commonMain`, for the structured-SVG icon
  lane. Nothing new is needed to draw one.
- **The coordinate systems line up exactly.** The font's `unitsPerEm` is 960, which is the viewBox
  upstream's own SVGs use (`viewBox="0 -960 960 960"`). Transforming glyph outlines by
  `(1, 0, 0, -1, 0, 960)` puts `search` in a 120–840 box inside a 960 × 960 viewport — checked
  against `symbols/web/search/materialsymbolsoutlined/search_24px.svg` — and the commands that come
  out are `M`, `L`, `Q` and `Z`, all of which Compose's parser accepts. An `ImageVector` with
  `viewportWidth = viewportHeight = 960f` and `defaultWidth = defaultHeight = 24.dp` needs no
  further fixing up.

## Where the fonts come from

`google/material-design-icons` ships `variablefont/MaterialSymbols{Outlined,Rounded,Sharp}[FILL,GRAD,opsz,wght].ttf`,
and `update/current_versions.json` is the index: 6,612 entries, 4,403 of them Material Symbols.

The build **fetches the three fonts at a pinned upstream commit, against a checked-in SHA-256**, into
the build cache, and the server serves them content-hashed. Not vendored: three variable fonts is
36 MB of binary in git, re-added in full on every upstream refresh. Not fetched at runtime from
`raw.githubusercontent.com` either — [`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md) settled that the
host fetches nothing on a design's behalf, and a build-time download with a pinned digest is the
same fetch moved to where it can be reviewed.

An offline build needs the fonts already in the cache, which is the same property every other
pinned artefact in this build has.

## The index the search box reads

The name list comes from the font itself — its `cmap` plus the `GSUB` ligature table is what maps
`chevron_right` to a glyph — so the bundle carries no second copy of 4,403 names.

What the font does **not** carry is tags and categories: `photo_camera` is findable under "camera"
only because `fonts.google.com/metadata/icons` says so, and that index is not in the GitHub
repository. (It was not reachable from the sandbox this was researched in; it is reachable from an
ordinary machine.) Tag search is therefore **deferred**, not designed away: if we want it, the
answer is a small vendored tags file on the same pinned-and-checksummed footing as the fonts, and
until then search matches names, which is what the picker matches today.

## What a design records

`m3/icon` gains the axes and keeps one name:

| Property | Values | Default |
| --- | --- | --- |
| `iconName` | a Material Symbols name — `search`, `chevron_right` | required |
| `iconStyle` | `outlined`, `rounded`, `sharp` | `outlined` |
| `iconFill` | `0`, `1` | `0` |
| `iconWeight` | 100–700 | `400` |
| `iconGrade` | -25, 0, 200 | `0` |
| `iconOpticalSize` | 20, 24, 40, 48 | `24` |
| `iconAutoMirror` | `true`, `false` | the name's default, below |

**Auto-mirroring is a property, because the font has no idea about it.** Today's inventory creates
`autoMirrored/<style>/<name>` keys and resolves them through `Icons.AutoMirrored`
(`MaterialIconCatalogTasks.kt`), and Compose honours that through `ImageVector.autoMirror` — which
`UiBuilderRenderer` already reads when it draws a structured icon. Material Symbols carries no such
flag: an arrow is one glyph, and nothing in the font says it should flip in RTL. Dropping the
distinction would silently render every directional icon the same way in both directions, so:
`iconAutoMirror` is a real property, its default per name comes from a generated list seeded by the
`autoMirrored/` keys the current inventory already enumerates, and every emitted `ImageVector`
carries `autoMirror = <that value>`. A legacy `autoMirrored/filled/arrowBack` therefore maps to
`arrow_back` at `fill 1` with `iconAutoMirror = true`, not merely to name and style.

**The legacy keys are lower-camel, and the new names are snake_case.** `MaterialIconCatalogTasks`
builds its key from the Kotlin member — `ArrowBack` becomes `arrowBack`, so the stored key is
`autoMirrored/filled/arrowBack` and never `arrow_back`. A migration that treats the suffix as an
already-valid symbol name resolves nothing. The mapping is therefore an explicit generated table
from every real stored spelling — all 11,385 style-qualified keys plus the 46 unqualified
compatibility aliases — to its symbol name, built from the same inventory that produced the keys,
not derived by re-casing a string at runtime.

`iconKey` stops being an 11,431-value enum and the catalog says "a name in the Material Symbols
set", validated against the font's own name table rather than spelled out. The
`SUMMARY_ALLOWED_VALUES` guard stays — it is a good rule regardless — but nothing reaches it.

**Old keys keep resolving, and some of them change pixels.** A saved `filled/chat` maps to
`chat` at `fill 1`, `outlined/chat` to `chat` at `fill 0`, `rounded/` and `sharp/` to their own
styles, and the 46 original compatibility keys through the same table. Two cases are not clean and
must not be hidden:

- **Material Symbols is not Material Icons redrawn at a different weight.** A number of glyphs
  differ in detail. A design opened after this lane may look slightly different, and that is a
  deliberate, stated change rather than a regression to chase.
- **`twoTone` has no Material Symbols equivalent.** It maps to `fill 0` and the editor says so on
  the node. The alternative — keeping `material-icons-extended` on the classpath purely to draw
  TwoTone — reinstates the bundle cost this lane exists to remove.

## The export, and what the icons site tells a developer to do

An exported design has to hand someone an icon the way they would have got one themselves. The
Google Fonts page offers a developer three things — the SVG, the Android vector drawable, and the
font plus `font-variation-settings` — and the three export shapes below are those three answers,
chosen by which export it is rather than by a setting.

### 1. Single-file source: inline path data

`ComposeEmitter` already emits a private `builderIcon(key)` carrying only the icons a design uses.
It keeps that shape and changes what the arms return: an `ImageVector` built with
`ImageVector.Builder`, roughly **550 bytes of Kotlin per distinct icon**. One file that compiles on
its own is what a single-file export is for, and it is the only shape that can carry an arbitrary
axis combination. The expression allowlist is unchanged — `ImageVector.Builder` and `addPathNodes`
are both under `androidx.compose`.

**The path needs a brush, and `addPath` does not default to one.** `ImageVector.Builder.addPath`
takes `fill` as a nullable `Brush` defaulting to null, so a path added without one is an outline
that draws nothing and an `Icon` tints nothing — an invisible icon in every export. The emitted
shape is therefore, following the inline vector this repository already has in
`native-catalog-m3/…/CatalogComponents.kt`:

```kotlin
ImageVector.Builder(
    name = "search",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 960f, viewportHeight = 960f,
    autoMirror = false,
  )
  .addPath(addPathNodes("M784-120 …"), fill = SolidColor(Color.Black))
  .build()
```

`SolidColor(Color.Black)` rather than a chosen colour because `Icon` tints it; `autoMirror` from
the property above. A gate renders one exported icon and asserts it has ink, so "compiles" is never
mistaken for "draws".

### 2. Bundle: `res/drawable`, which is what the site hands an Android developer

This is where the icons site's Android answer — a `<vector>` in `res/drawable/`, read through
`painterResource(R.drawable.ic_search)` — **does not survive contact with an export**, and
[`UI_BUILDER_EXPORT_BUNDLE.md`](UI_BUILDER_EXPORT_BUNDLE.md) already settled why: `R` is generated
into the *application's* namespace, not the package the exported file declares, so the export would
need the app's namespace as a second parameter it has no way to know. That doc put the bytes in
`assets/` for exactly this reason, and refused `resources.getIdentifier` as the reflective way
around it.

So the bundle follows the decision already made rather than reopening it: the outline travels as
data under `assets/uibuilder/<designId>/`, and the generated source builds the `ImageVector` from
it — the same namespace-free `context.assets.open(…)` shape the images use. Upstream's own
`symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml` drawables are what a *human*
following the site would drop into their own module, and the README beside the bundle says so; they
are not what an exporter that does not know the namespace can wire up for them.

### 3. Default axes on a provably identical icon: `Icons.*`

Worth having, because the generated code stays idiomatic and the consumer ships no path data — but
**only where it is the same picture**, and that is a measurement, not an assumption. Upstream ships
both sets, so the question is directly checkable: rasterise `src/<category>/<name>/materialiconsoutlined/24px.svg`
and `symbols/web/<name>/materialsymbolsoutlined/<name>_24px.svg` at 96 px and difference their alpha.
Over a 50-name sample of the 2,205 names that exist in both:

| Difference | Names | Reading |
| --- | --- | --- |
| under 1% | 30 | the same drawing |
| 1–3% | 8 | a nudged curve |
| over 3% | 12 | redrawn — `cast_connected` 37%, `help` 31%, `calendar_today` 24% |

So a blanket "defaults use `material-icons-*`" is wrong for about a quarter of the set, and
silently: the canvas would show a Symbol and the built app a different picture, which is the
canvas/export fidelity this repo measures everywhere else. The rule instead is **per name, decided
at build time**: the comparison above runs over all 2,205 shared names and emits an equivalence
table, the export names `Icons.Outlined.X` only for a name on it at default axes, and everything
else takes shape 1. A gate keeps the table honest, the same way
`checkMaterialIconCatalogFixture` does today.

Two conditions on top of the table, because being the same picture is not sufficient:

- **It is opt-in, and it names its price.** Almost all of those 2,205 members live in
  `material-icons-extended`, not in the small core artifact, so emitting `Icons.Outlined.X` turns a
  self-contained file into one that does not compile unless the consumer already has that
  dependency — and it does so precisely when the "optimisation" fires. The shortcut is therefore an
  **export option, off by default**, and an export that uses it states the coordinate to add.
  Shape 1 stays the default for every export that did not ask.
- **An auto-mirrored name takes `Icons.AutoMirrored.<Style>.X`, or it takes shape 1.** An alpha
  comparison cannot see `autoMirror` — the two members draw the same pixels and differ only in RTL
  — so a table built from pixels alone would quietly swap a mirroring arrow for a non-mirroring
  one. The emitter selects the `AutoMirrored` member when `iconAutoMirror` is set, and falls back to
  inline path data when no such member exists.

### Who resolves the outline when there is no browser

The browser is not the only exporter, and this is the part the design has to name rather than
assume. `ui_builder_export`, the CLI and the native-preview lane all run
`ScreenGeneratorComposeExportExecutor` on the JVM: it projects the saved document and calls
`ScreenGenerator` directly, with no Compose and no Skia on that classpath — the server's
dependencies are deliberately narrow, and its build actively filters `skiko-awt-runtime` jars out.
A document that stores only a name and five axis values gives that lane nothing to emit, so those
designs would export empty or refuse.

**The document records the resolved outline, in a registry beside the assets.** When an icon is
picked or an axis moved, the editor resolves the glyph once and records the path data under the
`(name, style, fill, weight, grade, opticalSize)` it resolved for — one entry per distinct icon in
the design, not per node, exactly the shape `assets` already has in `DesignDocumentV1`. The server
then needs no font and no glyph engine: the outline is in the document it was handed, and export,
native preview and the daemon render all read it from there. It also makes a design portable in the
sense [`UI_BUILDER_DESIGN_PORTABILITY.md`](UI_BUILDER_DESIGN_PORTABILITY.md) means it — a design
carries its own pictures rather than depending on the host's font pin.

**The registry is the source of truth once an entry exists — the canvas included.** The browser
resolves a glyph only to *create* an entry; having created one, it draws that entry like every other
lane. Otherwise a moved font pin would give the canvas the new glyph and export the recorded one,
from the same unchanged document, which is precisely the canvas/export divergence the registry
exists to prevent. A font refresh is then an explicit operation that replaces entries atomically and
is visible in the revision history, not a silent redraw on next open.

### Who fills the registry when no editor is involved

An icon node does not only arrive through the picker. `ui_builder_apply` writes one over MCP, a
`CreateDesign` can carry one from the start, and a design authored that way can be exported or
natively previewed before anyone opens it in a browser. None of those paths runs the editor, so
"the editor resolves it" would leave the registry absent exactly where the JVM lane has nothing to
fall back on. Asking the MCP caller to supply path data was the alternative, and it is a poor thing
to ask of an agent authoring a design.

**So the server resolves it too, from the static instances only.** On an icon write with no
registry entry, the service reads the glyph from the same pinned instance file it already serves to
the browser and records the entry as part of that commit — the node and its outline land together,
so nothing downstream sees a half-written state.

This is deliberately not "put Skia on the server". The instances are *already interpolated at build
time*, so resolving one needs no variable-font machinery — no `gvar`, no axis interpolation, no
native library. It is a read of `cmap`, `loca` and `glyf`, whose quadratic outlines are the `M`/`L`/
`Q`/`Z` the parser already accepts. That is a bounded piece of pure Kotlin, and it keeps the
narrow classpath the server's `checkDependencyOwnership` and its `skiko-awt-runtime` filtering exist
to protect.

The consequence, stated rather than hidden: **the server can resolve only the axis points a static
instance covers** — the three styles at `fill 0/1`, default weight, grade and optical size. A
server-side write naming a weight, grade or optical size off those points is refused, with a message
naming what is covered and pointing at the editor, which has the variable font and can record the
entry itself. An agent can author any icon; it cannot author an off-axis one without a browser
having been involved, and it is told so at the point of the write rather than at export.

### Why the canvas is not affected

Whichever shape the export takes, the daemon renders path data, so the Android/Robolectric lane
needs no font and no Skia — which matters, because `ui-builder` targets `jvm` and `wasmJs` and the
render bundle does not.

### Not a fourth shape: the font in `res/font`

Shipping the variable font with the consumer's app and drawing ligatures with
`FontVariation.Settings` is the literal translation of what the site recommends for the web, and it
costs that app 10.68 MB to draw three icons. It is the right answer for an app using hundreds, and
the wrong default for an exported design, so it is not built — noted here so the next person does
not have to re-derive why.

## The picker

One row per name, with the current axis settings applied to every thumbnail, and a customise panel
below the grid that mirrors Google Fonts: Fill as a switch, Weight, Grade and Optical size as
sliders, style as a segmented control. A search for `chat` returns **one** row.

The grid composes at most a page of rows, as it does now, and the glyph-to-`ImageVector` conversion
is cached by `(name, style, fill, weight, grade, opticalSize)` — cheap, because a page is 80 paths
and a typeface clone is a Skia handle, not a re-parse of 10 MB.

## Slices

1. The font-fetch task, the serving route and the browser-side glyph → `ImageVector` seam, behind
   the existing picker, drawing the default axis point only.
2. The property change on `m3/icon`, the legacy-key mapping, and the catalog contract.
3. The picker's customise panel.
4. The server's static-instance glyph reader, so an icon written over MCP carries its outline.
5. The export change — inline path data, the `assets/` bundle shape, and the opt-in equivalence
   table behind `Icons.*` — and the removal of the generated Kotlin, `material-icons-extended` and
   `MaterialIconCatalogTasks`.

Slice 1 alone returns the 17.8 MB.

## What is deliberately not here

- **A glyph endpoint the browser calls per axis change.** The browser has Skia and resolves
  locally; the registry carries the result to every other lane. The server's own resolver (above)
  is a different thing: it is a static-instance read on the write path, not a service the browser
  talks to.
- **Drawing icons as text.** A ligature draw is fewer moving parts on the canvas, but the export
  needs outlines regardless, and a consumer's app would then need the font shipped with it.
- **The full 42-instance matrix.** Six default-axis instances are a tier the design needs; 42 is
  the data-file deployment under another name, and the variable font already covers the rest.
- **Tag and category search**, per above.
- **The legacy Material Icons set, as something we draw from.** `material-icons-extended` leaves
  *our* build — nothing in the editor, the canvas or the daemon resolves an `Icons.*` member any
  more. It survives only as a name the export may print, for a consumer who already depends on it,
  under the equivalence table above.
