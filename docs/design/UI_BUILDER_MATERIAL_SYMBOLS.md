# UI builder: Material Symbols, one row per icon

How the icon picker stops being a list of 11,385 near-duplicates and becomes what
[fonts.google.com/icons](https://fonts.google.com/icons) is — search a name, then customise it —
and how the icons leave the Wasm bundle without arriving anywhere else as a large binary. Revisits
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
  properties of the node, exactly as they are axes on the Material Symbols font — the sliders and
  dropdowns the Google Fonts page shows to the right of the grid.
- **Nobody downloads a font.** The browser asks for the outlines of the icons it is about to draw
  and gets path data — about 550 bytes each. A design's five icons are ~3 KB; a grid page of 80 is
  ~45 KB. There is no font in the bundle, none fetched by the client, and none in git.
- **The server generates from the source, on demand.** It keeps the upstream variable fonts —
  digest-pinned, fetched once into a cache, in neither git nor the distribution — and resolves a
  glyph at the requested axis values when asked. Nothing is pre-generated, so all four axes work
  continuously.
- **A design records what it drew.** The outline goes into the document beside the assets, so an
  export, a daemon render and the canvas cannot disagree, and a later upstream refresh cannot
  silently redraw an old design.

## Where 4,403 icons are allowed to live

The floor is fixed: 6,614 glyph outlines at one axis point are **3.74 MB raw, 735 KB gzipped**, and
no encoding beats that by much for one point. Every choice here is about *who pays it* and *how
many points* — not about making it smaller.

### Not the browser, because the browser needs 80 of them

This is where the first draft of this design went wrong, and the correction is the whole point. If
the browser resolves glyphs itself it needs a whole font, and then the arithmetic is bad:

| First load, gzipped | Wasm | Icons | Total |
| --- | --- | --- | --- |
| Today (#710) | 5.28 MB | compiled in | **5.28 MB** |
| Browser holds a variable font | 2.37 MB | 4.8 MB | **7.17 MB** — worse than today |
| Browser holds a default-axis instance | 2.37 MB | 0.53 MB | **2.90 MB** |
| **Browser holds nothing; outlines on demand** | 2.37 MB | ~3 KB | **~2.37 MB** |

The third row was this document's previous answer and it is still 6,614 glyphs to draw five. The
fourth row is what the product actually needs: **a design uses about five icons and a grid page
shows eighty**, so bytes proportional to what is on screen is three orders of magnitude less than
bytes proportional to the catalog.

So the browser holds no font at all, and the editor fetches:

- the design's own icons in one request when it opens — ~3 KB;
- a grid page as it scrolls or the query changes — ~45 KB raw, ~12 KB gzipped, debounced and cached
  by `(name, style, fill, weight, grade, opticalSize)`;
- nothing when an axis slider moves except the visible page again.

Name search does not wait on any of that: the editor fetches the **name list once** — 4,284 names
for the Outlined face, from the `.codepoints` file pinned beside the font — and filters it locally
from then on, so searching is typing against an in-memory list and only the outlines of the visible
rows are fetched. It is served rather than bundled for the same reason the fonts are: a copy in the
bundle is a second copy that can drift from the pin.

### The server, generating from the source on the fly

The server needs the complete set, and there are two ways to give it one: pre-generate the outlines
and ship them, or keep the *source* and generate on demand. Pre-generation is the trap, for two
reasons that have nothing to do with how well it compresses.

It does compress well, and the measurement is worth keeping because it is what makes the trap
tempting. The same icon at seven weights is nearly the same string, so ordering the data glyph-major
— every variant of `search` adjacent, rather than every glyph of weight 400 adjacent — lets ordinary
compression see it. Over one style's fill × 7-weight matrix (14 variants, 6,614 glyphs, 51.0 MB
raw):

| Layout | gzip | xz |
| --- | --- | --- |
| Instance-major, one blob per variant | 11.50 MB | — |
| Glyph-major, single stream | 7.11 MB | 3.98 MB |
| Glyph-major, 64 shards of ~104 glyphs | 7.16 MB | 4.39 MB |

Three styles is 21.5 MB with `java.util.zip`. That is a large generated binary inside the server
artifact, regenerated in full on every upstream bump — and it *still* only covers the axis points
someone chose in advance, so grade and optical size would have to be refused or the table
multiplied. Compressing it better does not fix either problem.

**So the server keeps the variable fonts and resolves glyphs on demand.** A variable font is the
source data: 10.68 MB per style that already contains every point of all four axes, which is
precisely the thing a pre-generated table is trying and failing to enumerate. Resolution is a glyph
lookup plus `gvar` delta interpolation at the requested axis values, memoised in an LRU — and since
what comes back is ~550 bytes that the document then records permanently, the same icon is resolved
once per host, not once per request.

The piece to build is a variation-aware TrueType reader: `cmap` and the `GSUB` ligature table for
name → glyph, `loca`/`glyf` for the outline, `fvar`/`avar`/`gvar` for the axis deltas with IUP for
the untouched points. It is a well-specified format and a few hundred lines of Kotlin with no
native dependency, which keeps the narrow server classpath that `checkDependencyOwnership` and the
`skiko-awt-runtime` filtering exist to protect. It earns its keep by removing the table, the
build-time instancing step, and the axis scope limit at once.

Correctness is the real cost, and it is bought with a *small* fixture rather than a large asset:
a checked-in golden set of a few dozen glyphs at a spread of axis values, generated from the pinned
fonts with an offline tool, that the reader must reproduce exactly. Kilobytes, not megabytes, and it
fails loudly when the interpolation is wrong.

### Nothing is bundled, and nothing is pre-generated

The fonts are neither in git nor in the server artifact. They are **fetched once against a pinned
digest into the host's cache on first use**, which is the pattern this codebase already runs on —
the Robolectric `android-all-instrumented` jars arrive the same way, on a first daemon test rather
than in anyone's repository. An offline host needs a warm cache, exactly as it does for those.

The URL names an upstream **commit**, not a branch, and that is load-bearing rather than tidy. The
digest alone makes wrong bytes unusable; it does nothing to keep the right bytes reachable. Pointed
at `master`, the pin works until the day upstream pushes and then every host with a cold cache
downloads bytes that cannot match, discards them, and answers 503 for good — while the warm hosts
carry on, so nobody notices until a new deployment. Updating a pin is three deliberate lines: the
commit, the digest, the size.

That leaves: nothing large in the bundle, nothing large in the distribution, nothing large in git,
and nothing regenerated when upstream moves — the pin changes, the next run fetches, and resolution
is unchanged. [`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md)'s rule holds, because that rule is
about fetching *on a design's behalf*: a digest-pinned dependency fetched once into a cache is the
same class of thing as a Maven artifact, not a runtime call to somebody's CDN.

### The axis scope comes back for free

Because resolution reads the variable font rather than a fixed set of points, **all four axes work
continuously** — weight anywhere in 100–700, and grade and optical size too, which the table
version would have had to refuse. The sliders in the customise panel are the font's real axes, and
nothing has to be decided in advance about which combinations someone is allowed to want.

![One icon per row across weight, fill, grade, optical size and the three styles, plus five off-grid weights](https://raw.githubusercontent.com/yschimke/compose-preview-server/b9b6de991d63cc1d94d0ee341b893422c1964519/docs/design/evidence/ui-builder-material-symbols/axis-options.png)

Every cell there is resolved by the reader in this change from the pinned font, at request time.
The last row is the argument against a pre-generated table in one picture: `wght 137`, `263`, `418`,
`552` and `689` are not points anybody would have chosen to enumerate, and they cost nothing.

## Why not the live Google services

fonts.google.com does this work as a service, and it is worth saying why we do not call it.
`fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@…` returns a font
already instanced *and* subsetted to the axis values asked for, and
`fonts.gstatic.com/s/i/…/<name>/<variant>/24px.svg` is a per-icon outline endpoint. Between them
they would delete the build task and the table.

Four reasons they cannot be the runtime source:

- **The repository already decided it.** External URLs are refused; "a stored, hash-checked asset is
  the answer, and the host fetches nothing on a design's behalf"
  ([`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md)).
- **Offline is the product.** This is a local preview server. A CLI render on a laptop with no
  network, or a daemon render on a restricted CI runner, still has to draw the icons. The research
  for this document ran in exactly such an environment: `fonts.google.com` was unreachable while
  `raw.githubusercontent.com` was.
- **Determinism is the premise.** Every change here is judged by pixel comparison. A glyph redrawn
  upstream would arrive as a visual diff on somebody else's PR with no commit behind it, which is
  the expensive kind of mystery `flake-triage` exists for.
- **Privacy.** Every editor session would tell Google what someone is designing.

They are the right source at **build time**, pinned, for one thing the fonts do not carry: see the
index below.

## The index the search box reads

Names come from the `.codepoints` file that ships beside each variable font — a `name codepoint`
line per icon, 79 KB for 4,284 names — fetched and pinned with the font. It is both how a name
reaches a glyph (`name` → code point → `cmap` → glyph id) and the list the picker filters, so there
is no second copy of the names anywhere, and the reader needs no `GSUB` ligature parsing at all.
Upstream publishes one of these per face and all three hash identically, so one file is fetched, not
three.

Two routes carry it: `GET /api/icons/{style}/names` for the list, and
`GET /api/icons/{style}?names=…&names=…&wght=…&FILL=…&GRAD=…&opsz=…` for a batch of outlines — one
`names` parameter per icon, because a comma cannot survive query decoding and a stale name
containing one would otherwise split into two. A name the
face does not carry comes back in a `missing` field rather than failing the batch — one stale name
in a grid page should not cost the other seventy-nine their pictures — and an axis that is not a
number is a 400 naming the parameter, because a silently ignored `wght=heavy` looks like a broken
slider.

Tags and categories are the part only Google has: `photo_camera` is findable under "camera" because
`fonts.google.com/metadata/icons` says so, and that index is **not** in the GitHub repository. It is
small — a few hundred KB — so it is fetched once against a pinned digest alongside the fonts, and
the server hands the picker names and tags together. Search then matches what a person types on the Google Fonts page, which is the point
of the exercise; without it the picker matches names only, as it does today.

## The runtime, verified rather than assumed

- **Compose can build a vector from a path string on Wasm.** `UiBuilderRenderer.kt` already does it:
  `PathParser().addPathNodes(node.pathData).toPath()` in `commonMain`, for the structured-SVG icon
  lane. Drawing a fetched outline needs nothing new.
- **The coordinate systems line up exactly.** The font's `unitsPerEm` is 960, which is the viewBox
  upstream's own SVGs use (`viewBox="0 -960 960 960"`). Transforming glyph outlines by
  `(1, 0, 0, -1, 0, 960)` puts `search` in a 120–840 box inside a 960 × 960 viewport — checked
  against `symbols/web/search/materialsymbolsoutlined/search_24px.svg` — and the commands that come
  out are `M`, `L`, `Q` and `Z`, all of which Compose's parser accepts. An `ImageVector` with
  `viewportWidth = viewportHeight = 960f` and `defaultWidth = defaultHeight = 24.dp` needs no
  further fixing up.
- **Recorded, because it was checked and then not needed:** skiko's `wasm-js` klib (0.150.1, behind
  Compose Multiplatform 1.11.1) does declare `Typeface.makeFromData`, `Typeface.variationAxes`,
  `Typeface.makeClone(FontVariation)`, `Font.getPath(Short)` and `Font.getPaths(ShortArray)`, so
  in-browser instancing of a variable font is possible. It is not used, because it requires the
  browser to hold the font the section above removes.

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
distinction would silently render every directional icon the same way in both directions, so
`iconAutoMirror` is a real property, its default per name comes from a generated list seeded by the
`autoMirrored/` keys the current inventory already enumerates, and every emitted `ImageVector`
carries `autoMirror = <that value>`.

**The legacy keys are lower-camel, and the new names are snake_case.** `MaterialIconCatalogTasks`
builds its key from the Kotlin member — `ArrowBack` becomes `arrowBack`, so the stored key is
`autoMirrored/filled/arrowBack` and never `arrow_back`. A migration that treats the suffix as an
already-valid symbol name resolves nothing, so every stored key has to be re-spelled.
`autoMirrored/filled/arrowBack` maps to `arrow_back` at `fill 1` **with** `iconAutoMirror = true`.

**It is a rule plus twenty-seven exceptions, not a generated table of 11,431 rows.** This document
asked for the table first; the measurement says a table is mostly arithmetic written out. There are
2,132 distinct member names behind the 11,385 style-qualified keys, and re-casing the member
resolves **2,105** of them against the pinned name list — the whole of the difference being the digit boundary, where
neither convention wins (`co2` stays `co2`, `filter1` is `filter_1`), so two spellings are tried and
no name has both. The remaining 27 are hand-written and named: 11 upstream renames (`crop169` →
`crop_16_9`, the `outbond` typo upstream later fixed, `playCircleFilled` and `playCircleOutline`
both folding into `play_circle` now that fill is an axis) and 16 icons Material Symbols dropped
outright (`facebook`, `fitbit`, `pix`, `whatsapp`, the `panorama*Select` variants). A generated
table would be a large asset regenerated on every pin bump; these 27 lines change only when upstream
renames something.

The rule is never trusted on its own: a candidate spelling is accepted only when the **pinned face
carries it**, so a bad guess is an explicit "no equivalent" somebody sees rather than a silently
wrong picture, and a name upstream drops turns into that same visible answer. All 2,133 are held
against the real name list in `MaterialSymbolsLegacyKeysTest`, with the expected answer for each
committed, so a rule that stops covering one fails loudly.

**The 46 unqualified keys are not "the filled member of the same name".** Five of them stood for
something else, and reading them the tempting way is silent rather than loud: `genres` was always
`Icons.Filled.Category`, and `genres` is *also* a real Material Symbols icon, so the assumption
swaps the picture rather than failing. `arrowBack`, `arrowForward` and `playlistAdd` were
auto-mirrored members that would stop flipping in RTL, and `bookmarkBorder` was the outlined one.
Each is written as the qualified key it stands for and migrated through the ordinary path, and all
46 are pinned against the inventory column that issued them.

One rename outranks its style rather than following it. `playCircleFilled` and `playCircleOutline`
were two icons, each drawn in all five styles; Material Symbols has one `play_circle` and says the
difference with `FILL`. Taking fill from the style there would collapse the pair — a stored
`filled/playCircleOutline` would come back filled — so the name's baked fill wins and only the face
follows the style. The style decomposition is checked there
too, because getting it backwards would redraw every icon in every existing design: `Filled`,
`Rounded` and `Sharp` are one drawing at three corner treatments, all of them *filled*, so they are
the three faces at `FILL 1`, and only `Outlined` is `FILL 0`.

`iconKey` stops being an 11,431-value enum and the catalog says "a name in the Material Symbols
set", validated against the shipped name list rather than spelled out. The `SUMMARY_ALLOWED_VALUES`
guard stays — it is a good rule regardless — but nothing reaches it.

**Old keys keep resolving, and some of them change pixels.** Two cases are not clean and must not be
hidden:

- **Material Symbols is not Material Icons redrawn at a different weight.** A number of glyphs
  differ in detail. A design opened after this lane may look slightly different, and that is a
  deliberate, stated change rather than a regression to chase.
- **`twoTone` has no Material Symbols equivalent.** It maps to `fill 0` and the editor says so on
  the node. The alternative — keeping `material-icons-extended` on the classpath purely to draw
  TwoTone — reinstates the cost this lane exists to remove.

## The outline registry

When an icon is chosen, or written over MCP, the resolved outline is recorded in the document under
its `(name, style, fill, weight, grade, opticalSize)` — one entry per distinct icon in the design,
not per node, exactly the shape `assets` already has in `DesignDocumentV1`. Two things follow.

**Every lane reads the registry, the canvas included.** An entry is created by a lookup; once it
exists it is what gets drawn, everywhere. Otherwise a refreshed table would give the canvas a new
glyph and an export the recorded one from the same unchanged document, which is the divergence the
registry exists to prevent. A refresh is an explicit operation that replaces entries atomically and
appears in the revision history, not a silent redraw on next open. It is also what makes a design
portable in the sense [`UI_BUILDER_DESIGN_PORTABILITY.md`](UI_BUILDER_DESIGN_PORTABILITY.md) means:
it carries its own pictures.

**The server fills it on every write path, including the ones with no browser.**
`ui_builder_apply` writes an icon over MCP, a `CreateDesign` can carry one from the start, and such
a design can be exported or natively previewed before anyone opens it — so the editor cannot be the
only thing that resolves. It does not need to be: the server resolves glyphs itself, so filling the
registry happens as part of the same commit and the node and its outline land together, whatever
wrote it.

## The export, and what the icons site tells a developer to do

`ScreenGeneratorComposeExportExecutor` projects a saved document on the JVM with no Compose and no
Skia on its classpath, so whatever it emits has to come from the document. It does: the registry.

### 1. Single-file source: inline path data

`ComposeEmitter` already emits a private `builderIcon(key)` carrying only the icons a design uses,
about **550 bytes of Kotlin per distinct icon**. A file that compiles on its own is what a
single-file export is for, and it is the only shape that carries an arbitrary axis combination.

**The path needs a brush, and `addPath` does not default to one.** `ImageVector.Builder.addPath`
takes `fill` as a nullable `Brush` defaulting to null, so a path added without one is an outline
that draws nothing and an `Icon` tints nothing — an invisible icon in every export. Following the
inline vector this repository already has in `native-catalog-m3/…/CatalogComponents.kt`:

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

`SolidColor(Color.Black)` rather than a chosen colour because `Icon` tints it; `autoMirror` from the
property above. A gate renders one exported icon and asserts it has ink, so "compiles" is never
mistaken for "draws". The expression allowlist is unchanged — `ImageVector.Builder` and
`addPathNodes` are both under `androidx.compose`.

### 2. Bundle: `assets/`, not `res/drawable`

The icons site's Android answer — a `<vector>` in `res/drawable/` read through
`painterResource(R.drawable.ic_search)` — does not survive contact with an export, and
[`UI_BUILDER_EXPORT_BUNDLE.md`](UI_BUILDER_EXPORT_BUNDLE.md) already settled why: `R` is generated
into the *application's* namespace, not the package the exported file declares, so the export would
need the app's namespace as a parameter it has no way to know. That doc put the bytes in `assets/`
for this reason and refused `resources.getIdentifier` as the reflective way around it.

So the outline travels as data under `assets/uibuilder/<designId>/` and the generated source builds
the `ImageVector` from it — the same namespace-free `context.assets.open(…)` shape the images use.
Upstream's `symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml` drawables are what a
*human* following the site drops into their own module, and the README beside the bundle says so.

### 3. Default axes on a provably identical icon: `Icons.*`

Worth having, because the generated code stays idiomatic — but **only where it is the same
picture**, which is a measurement, not an assumption. Upstream ships both sets, so it is directly
checkable: rasterise `src/<category>/<name>/materialiconsoutlined/24px.svg` against
`symbols/web/<name>/materialsymbolsoutlined/<name>_24px.svg` at 96 px and difference their alpha.
Over a 50-name sample of the 2,205 names in both:

| Difference | Names | Reading |
| --- | --- | --- |
| under 1% | 30 | the same drawing |
| 1–3% | 8 | a nudged curve |
| over 3% | 12 | redrawn — `cast_connected` 37%, `help` 31%, `calendar_today` 24% |

A blanket "defaults use `material-icons-*`" is therefore wrong for about a quarter of the set, and
silently so. What the numbers look like:

![Legacy Material Icons against Material Symbols, twelve names side by side with a difference overlay](https://raw.githubusercontent.com/yschimke/compose-preview-server/b9b6de991d63cc1d94d0ee341b893422c1964519/docs/design/evidence/ui-builder-material-symbols/legacy-vs-symbols.png)

`menu` is the same drawing to the pixel and `search` is within 0.7%, while `cast_connected` is
redrawn outright at 36.9%. The middle of that range is the interesting part: the legacy *outlined*
face is not consistently outlined — its `favorite`, `star` and `help` are solid where the Symbols
outlined face draws a stroke — which is a change of meaning, not of weight, and exactly the kind of
substitution an equivalence table has to refuse. The rule is **per name, decided at build time**: the comparison runs over all 2,205
shared names and emits an equivalence table, gated the way `checkMaterialIconCatalogFixture` is.
Two conditions on top of it:

- **It is opt-in, and it names its price.** Almost all those members live in
  `material-icons-extended` rather than the core artifact, so the shortcut turns a self-contained
  file into one that does not compile unless the consumer has that dependency — precisely when the
  optimisation fires. It is an **export option, off by default**, and an export that uses it states
  the coordinate to add.
- **An auto-mirrored name takes `Icons.AutoMirrored.<Style>.X`, or it takes shape 1.** An alpha
  comparison cannot see `autoMirror`: the two members draw identical pixels and differ only in RTL.

### Not a fourth shape: the font in `res/font`

Shipping the variable font with the consumer's app and drawing ligatures with
`FontVariation.Settings` is the literal translation of what the site recommends for the web, and it
costs that app 10.68 MB to draw three icons. Right for an app using hundreds, wrong for an exported
design — recorded so the next person need not re-derive it.

## The picker

One row per name, with the current axis settings applied to every thumbnail, and a customise panel
that mirrors Google Fonts: Fill as a switch, Weight, Grade and Optical size as sliders, style as a
segmented control. A search for `chat` returns **one** row.

The grid composes at most a page of rows, as it does now. Filtering is local against the shipped
name and tag index; outlines for the visible page are fetched in one batched request, debounced
while typing or dragging a slider, and cached by the full axis tuple.

## Slices

1. The pinned-digest font fetch, the variation-aware glyph reader with its golden fixture, the
   serving route and the browser's fetch-and-draw seam, behind the existing picker.
2. The property change on `m3/icon`, the legacy-key mapping, the outline registry and the catalog
   contract, with the server filling the registry on every write path.
3. The picker's customise panel and the tag index.
4. The export change — inline path data, the `assets/` bundle shape, the opt-in equivalence table
   behind `Icons.*` — and the removal of the generated Kotlin, `material-icons-extended` and
   `MaterialIconCatalogTasks`.

Slice 1 alone returns the 17.8 MB, and adds nothing in its place that a client downloads.

## What is deliberately not here

- **Anything large in a binary, or regenerated when upstream moves.** Not in the bundle, not
  fetched by a client, not in the server's distribution, not in git. The fonts are digest-pinned
  source data in a cache; everything else is derived on demand.
- **A pre-generated outline table**, at 21.5 MB compressed for three styles — considered, measured,
  and rejected: it is a large generated binary that still cannot answer an axis value nobody chose
  in advance. The 42-instance font matrix and the 158 MB instance-major dump went the same way.
- **Drawing icons as text.** Fewer moving parts on the canvas, but the export needs outlines
  regardless, and a consumer's app would then need the font shipped with it.
- **The live Google endpoints at runtime**, per the section above.
- **`material-icons-extended` as something we draw from.** It leaves this build entirely; it
  survives only as a name the export may print, opt-in, for a consumer who already depends on it.
