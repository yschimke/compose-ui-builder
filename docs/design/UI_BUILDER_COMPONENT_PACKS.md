# Component packs: other catalogs' components in the UI builder

**Status: built (2026-09).** The runtime merge, the server projection, the editor settings and the
native-lane targeting described here are in the tree; the follow-ups at the end are not.

## The two requirements, and why they pulled against each other

The builder started with one catalog compiled into it. `m3-catalog`'s capabilities are a checked-in
JSON file, its canvas adapters are `when (componentId)` branches in the Wasm renderer, and the two
other catalogs — `remote-m3`, `wear-m3` — are derived from it in Kotlin. That shape answered "can the
builder author a Material 3 screen" and nothing else. Two things were then asked of it at once:

1. **Be generic.** The presence of `m3-catalog` should be a configuration fact, and the builder should
   give *passable* results for a catalog it has never seen — an application's own design system,
   say — without a hand-written adapter per component.
2. **Let a design use other catalogs' components as an option.** Split by platform (mobile, Wear,
   Remote Compose), and switched on from settings: enable "Confetti Mobile" and see Confetti's
   components on the palette of a Material 3 design.

These pull against each other because the honest answer to the first is *the canvas cannot draw an
arbitrary catalog* — the browser is Compose Multiplatform for Wasm and cannot link an application's
classes any more than it can link Wear Material 3
([`UI_BUILDER_WEAR_SCREEN.md`](UI_BUILDER_WEAR_SCREEN.md#the-hard-constraint-the-canvas-has-no-wear-compose))
— while the second asks for exactly those components to be authorable. The resolution is the one
`wear-m3` already reached for seventeen of its own components: **a component the canvas cannot
draw is declared, validated, exported and rendered natively, and drawn on the canvas as a named
placeholder.** Applied to a whole catalog at once, that is a *component pack*.

## What a pack is

A pack is a served catalog's own composables, offered inside the builder's authoring catalogs of
the same platform, under ids of the form `<served catalog>/<component>`:

```text
confetti-mobile/session-card
confetti-mobile/speaker-row
```

Three properties define it, and each is a decision:

- **A pack is not a pin.** A design stays pinned to exactly one catalog, and a pack's components are
  part of that catalog the way `wear-m3`'s borrowed foundation components are. The pin is what makes
  a reopened design render what it was saved with; a second pin would be a second thing to migrate.
  A design does not record which packs it drew from, and a design that uses none is unaffected by a
  host that admits some.
- **A pack is platform-scoped, never name-scoped.** Each authoring catalog now declares its platform
  in `statusSemantics.platform` — `m3-catalog` is `mobile`, `wear-m3` is `wear`, `remote-m3` is
  `remote-compose` — and a pack names the platform whose catalogs receive it. A mobile pack lands in
  `m3-catalog` and in nothing else: a Wear widget body is `@RemoteComposable` and cannot call a phone
  application's composables, and Wear Material 3 and Material 3 are not used together. The rule that
  keeps `wear-m3` honest keeps packs honest.
- **A pack is derived, not authored.** Its components come from the served catalog's discovered
  component record — the `components.json` every preview bundle's discovery emits — projected by
  [`ComponentRecordPacks`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ComponentRecordPacks.kt).
  Nobody transcribes a capability table for Confetti; the record already says what its composables
  take, and the producer already said which of them it can prove a call site for.

## The projection, and the line it draws

[`UI_BUILDER_ON_THE_COMPONENT_RECORD.md`](UI_BUILDER_ON_THE_COMPONENT_RECORD.md) plans to generate
the builder's capability catalog from the record plus authored policy, and lists the corrections
that separate a generator from a regression. The pack projection is that generator for the one case
needing no authored policy — extra components beside a hand-authored catalog, never in place of one
— so it takes the safe subset and says so per component:

| The record says | The pack does |
| --- | --- |
| `symbol.origin == PROJECT` | offered; a `LIBRARY` symbol is left out, because `confetti-mobile/text` would be Material 3 twice under an id that lies about where it came from |
| `code.call` present | offered; a refused call site (a required parameter with no literal, collided overloads, a receiver scope) is left out rather than offered and refused at export |
| a `String`, `Boolean`, `Int`/`Long`, `Float`/`Double` parameter | a property, required when it has no default and is not nullable |
| a `Modifier`, a callback, a domain type, an enum | not a property; the generator's placeholder table fills it, which is what `code.call` proved works |
| a `@Composable` lambda | a slot that accepts anything — which roles a slot admits is authored policy the record does not carry, and the compiler on the native lane is the check |
| everything | `wasm.adapterStatus = unsupported`, so the canvas draws a placeholder and the catalog says so |

Every exclusion is logged at startup with its reason, so a shelf shorter than the record is
explained rather than discovered.

The id is spelled by one function, [`UiBuilderComponentPack.componentId`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderComponentPacks.kt),
used on both sides: the runtime writes it onto the capability and the export writes it back onto
the record as a catalog alias ([`ComponentRecordPacks.aliasedRecord`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ComponentRecordPacks.kt)),
so the palette and the generator cannot disagree about what `confetti-mobile/session-card` is.

## Where the declaration lives

`CatalogCapabilityV1` is published from compose-preview-contracts and cannot grow a field from this
repository, so — as with `previewSurfaces` and `componentMenu` — everything rides in
`statusSemantics`:

```json
{
  "platform": "mobile",
  "componentMenu": { "groupOrder": ["Scaffolds", "…", "Confetti Mobile"], "components": { "confetti-mobile/session-card": { "group": "Confetti Mobile" } } },
  "componentPacks": [
    {
      "id": "confetti-mobile",
      "label": "Confetti Mobile",
      "platform": "mobile",
      "nativeCatalog": "confetti-mobile",
      "components": ["confetti-mobile/session-card", "confetti-mobile/speaker-row"],
      "notes": "2 of 41 components in confetti-mobile's record, …"
    }
  ]
}
```

`UiBuilderCatalogPlatform` and `UiBuilderComponentPacks` in `:ui-builder-export` read it back for
the editor and the server; `CurrentM3UiBuilderCatalogExecutor` in `:ui-builder-runtime` writes it
when it merges a pack. The runtime cannot depend on the export module (`checkUiBuilderRuntimeBoundary`),
so the keys are literals on that side, exactly as `previewSurfaces` is.

## The four surfaces that read it

| Surface | What changes |
| --- | --- |
| **Runtime** (`CurrentM3UiBuilderCatalogExecutor`) | Takes `packs: List<UiBuilderComponentPackSource>`. Each enabled catalog receives the packs of its platform: components appended, one insert-panel shelf per pack named for it, `componentPacks` written. An id a pack redeclares, a pack id that is also an enabled catalog, or a duplicate pack id is a startup failure. Validation is unchanged — a pack node is a node of the catalog. The pin is untouched. |
| **Server** (`--ui-builder-packs <catalog>=<platform>`) | Reads the pack's record at startup — from `--ui-builder-components <pack>=<components.json>` where the operator named one, otherwise from the **served catalog's own delivery branch** (see below) — projects it, and passes it to the runtime. A record the operator named that will not load is a startup failure; a served catalog that supplies none is a warning and an absent shelf, because the catalog may simply not have republished yet. The export executor merges each *used* pack's aliased record into the design catalog's; a **Wear screen**, which has no record and is written by `WearScreenCodeExporter`, gets each used pack's components handed to that emitter by id, and it writes the call from the record (see below). The native lane compiles a design that uses a pack against the **pack's** bundle — the one classpath carrying both the pack's classes and the Material 3 the catalog names — and refuses a design mixing two packs (`MIXED_PACKS`), since no served bundle carries both. |
| **Editor** | The palette hides a pack's shelf until the pack is switched on; search does not find what the switch hides. **Component packs…** in the toolbar overflow (and a summary row at the top of the insert panel) opens a dialog with one switch per pack. The choice is remembered per catalog in the browser (`localStorage`), because which shelves a palette shows is a preference of the person at the keyboard, not a fact about the document. The canvas draws a pack node as a dashed, captioned outline holding its children; the code pane and the problems panel generate from the embedded record plus the pack's components projected back into record shape, so a pack node is never reported as "no component in this catalog". |
| **New design chooser** | Catalogs are grouped by platform — Mobile, Wear, Remote Compose — once more than one platform is enabled. A catalog the chooser has no templates for gets a card named after itself with a blank starting point, rather than silently missing. |

| The settings dialog | A pack node on the canvas |
| --- | --- |
| ![Component packs dialog: Confetti Mobile on, Jetnews off](../../renders/ui-builder-component-packs/packs-panel.after.png) | ![A Material 3 column holding two Confetti components drawn as captioned placeholders](../../renders/ui-builder-component-packs/pack-placeholder.after.png) |

Both are `@Preview`s in `:ui-builder` (`ComponentPackPreviews.kt`), so the next change to either
is diffed without anyone remembering to render it.

## Enabling one

```text
--ui-builder-catalogs m3-catalog,remote-m3,wear-m3
--ui-builder-packs confetti-mobile=mobile
```

The packaged image reads the same from `SERVE_UI_BUILDER_PACKS`. Admitting a pack only makes it
*available*; an author switches it on from the editor.

### Where the record comes from

A pack is projected from the served catalog's own component record, and the server finds that
record on the catalog's delivery branch by the same route it fetches everything else:

1. the file `catalog.json` declares as `componentsFile` (the design-artifacts pipeline publishes
   `components.json` beside `catalog.json`); failing that,
2. the `components.json` entry inside the catalog's **live bundle**, which the Gradle plugin has
   packed into every bundle since component records existed.

`ServeCatalogStore` stages whichever it finds at the generation root
(`<catalog dir>/components.json`, read back by `componentRecord`) on every load, so the export
path always generates against the served catalog's current record. A pack, being a startup fact,
cannot wait for that load — catalogs load in the background, for minutes — so the builder lane
asks the store to fetch the record ahead of the load (`fetchComponentRecord`) and keeps that file
as the export's fallback until the catalog has published a generation of its own.

`--ui-builder-components <catalog>=<components.json>` still exists and still wins, for a catalog
that publishes no record (one rendered before records existed, or a local module) and for the
packaged `m3-catalog` record. It is no longer needed to admit a pack.

A catalog whose bundle predates records — `confetti-mobile` at the time of writing was rendered by
compose-preview 1.14.1 — yields no record, so the pack is logged as not offered rather than failing
startup, and appears on its next publish.

### A Wear pack

```text
--ui-builder-catalogs m3-catalog,remote-m3,wear-m3
--ui-builder-packs confetti-mobile=mobile,confetti-wear=wear
```

A `wear` pack lands in `wear-m3` by the platform rule, and its bundle is an Android one, so a design
using it compiles on the Robolectric daemon — both by construction. What was *not* by construction
is the export: `wear-m3` deliberately has no component record and its screens are written by
`WearScreenCodeExporter`, an authored emitter that refused every id it had not met, a pack's
included. It now takes the used packs' components by id and writes each from its record
(`WearContentEmitter.emitPack`): the design's literals for the parameters it set, the record's
proven placeholders for the required ones it did not, its children in the record's slots, and the
row treatment on a `Modifier` or a `SurfaceTransformation` parameter where the component declares
one. A design pinned to `wear-m3` that holds `confetti-wear/section-header` generates

```kotlin
SectionHeader(
    text = "Thursday",
    modifier = Modifier.transformedHeight(this, spec),
    transformation = SurfaceTransformation(spec),
)
```

inside the `TransformingLazyColumn` item, imports `dev.johnoreilly.confetti.wear.components
.SectionHeader`, and compiles against the served `confetti-wear` bundle on the Android daemon.

What Confetti's Wear module is worth as a pack is real data rather than a limitation of the
projection. Discovery run over the published bundle's classes records fifteen of its own
composables; seven have a proven call site and would be offered. Four are components —
`SectionHeader` and `ScreenHeader` (a `String`), `PlaceholderButton` (fully defaulted), and
`SessionCard`, whose `SessionDetails` is nullable so its call site passes `null` and draws the
card's own loading skeleton — and three are the theme and preview scaffolds
(`ConfettiThemeFixed`, `ConfettiPreviewScaffold`, `TestScaffold`), single-slot containers that
are technically callable and not much use on a shelf. The rest are left out by name:
`SessionSpeakerChip` and every screen take a non-null domain object or a `UiState`, and
`SocialIcon` an `ImageVector`, for which no placeholder can be written. That is the honest shelf,
and an authored override (follow-up 2) — a `SessionDetails` sample, say — is what would widen it.

Two things stand between that shelf and a deployed box today, neither in this repository. The
published `design-artifacts/confetti-wear` bundle was rendered by compose-preview 1.14.1, before
records existed, so it carries no `components.json` and the pack is logged as not offered until
Confetti republishes. And the discovery that ran to produce the numbers above is the fixed one:
Confetti's component previews are `ConfettiThemeFixed { SessionCard(…) }`, and a released plugin
records the *wrapper* as each preview's subject, because Kotlin 2.3+ compiles a non-capturing
composable lambda into a static method of the `ComposableSingletons$…` class rather than into a
class of its own, and the walker that descends into `Theme { Component() }` looked only for the
class. Fixed in compose-ai-tools (follow-up 5); the exporter here is ready for the record a plugin
carrying that fix produces.

## What this deliberately does not do

- **It does not make `m3-catalog` a pack of itself.** The hand-authored M3 catalog carries editor
  hints, variant selectors and slot policy a record cannot say, and its canvas draws the real
  components. A pack is the passable answer for a catalog nobody has authored an adapter for; it is
  not a replacement for authoring one.
- **It does not fake a pack component on the canvas.** The rule in `UI_BUILDER_WEAR_SCREEN.md` —
  never assemble a lookalike for a library the canvas cannot link — holds. A placeholder claims
  nothing about size, colour or shape, and the native lane is where the picture comes from.
- **It does not publish a "world canonical set".** The question was whether a canonical component
  set should be published for other instances to import. The pack projection is that import, in the
  form every catalog already produces: its component record. A catalog that wants to be usable
  inside other designs publishes the record it already generates; nothing new has to be authored
  or agreed. What a canonical *authored* set would add — editor hints, slot policy, canvas adapters
  — is exactly the part that has to be written per catalog, and `m3-catalog`'s JSON is the
  existing example of one.

## Follow-ups

1. ~~**Carry `components.json` in the delivery branch.**~~ Done: the server reads the served
   catalog's record (declared file, else the live bundle), and the design-artifacts pipeline
   publishes `components.json` beside `catalog.json`. A pack's record is no longer a flag.
2. **Authored overrides for a pack** — the `stateCallback` role, enum constants, slot policy — as
   the sidecar `UI_BUILDER_ON_THE_COMPONENT_RECORD.md` describes, so a pack can grow from passable to
   good without becoming a hand-written catalog.
3. **Per-design pack pins.** A pack is derived from whatever record is on disk at startup; a design
   that uses one is not pinned to that record's revision. The same gap `ComponentRecordSource`
   records for the export, with the same retention question behind it.
4. ~~**A Wear pack.**~~ Exercised: `confetti-wear=wear` lands in `wear-m3`, the Wear screen emitter
   writes a pack node from its record, and the native lane compiles the screen against the pack's
   Android bundle. See [A Wear pack](#a-wear-pack) for what Confetti's record offers and why.
5. ~~**Discovery under Kotlin 2.3+ lambdas.**~~ Fixed in compose-ai-tools: `PreviewTargetInference`
   used to descend into a preview's `Theme { … }` content lambda only when the compiler lifted it
   into a `ComposableSingletons$…$lambda$…` class; Kotlin 2.3+ emits a static method on the
   singletons class instead, so a `ConfettiThemeFixed { SessionCard() }` preview recorded the
   wrapper as its subject and the component never reached the record. The walker now reads both
   shapes, and a `SessionCardPopulatedPreview` counts for `SessionCard`. A pack projected from a
   bundle rendered by a plugin without that fix offers its theme wrappers and little else.
