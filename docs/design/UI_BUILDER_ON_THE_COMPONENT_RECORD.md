# The UI builder on a derived component record

Status: **plan** (2026-09). The server-side half of
[`compose-ai-tools` → `docs/design/COMPONENT_RECORD.md`](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/COMPONENT_RECORD.md),
which carries the root-cause analysis, the record's shape, the override format
and the conformance ladder. This document covers only what this repository owns:
where the builder's catalog comes from, how a node renders, how an export is
proven, and what has to change in `:ui-builder`, `:ui-builder-runtime`,
`:render-host` and `:server`.

**Module placement is not incidental here.** AGENTS.md puts what *renders* in
`:render-host` and keeps it free of a web server, with `:server` holding the HTTP
layer. Resolving a catalog bundle's classpath and invoking a composable through it is
rendering, so it belongs in `:render-host` with only its adapter in `:server` —
otherwise the published graph is violated and offline render callers cannot reuse the
path.

## The problem this repository owns

The builder describes one catalog and renders another.

* **One capability catalog, hand-transcribed.**
  `docs/design/fixtures/ui-builder/jetcaster-discover-capabilities-v1.json` is 25
  components transcribed by hand from a single screen of
  `android/compose-samples`. `ui-builder-runtime/build.gradle.kts` copies it into
  a resource named `m3-catalog-v1.json`. It has no relationship to
  `yschimke/m3-catalog` — the 59-component Material 3 reference catalog served on
  the same host under the same name.
* **Restated four times, by hand — two more than the first draft of this document
  found.** `UiBuilderRenderer.kt` (1,554 lines) re-implements each component as a
  `when (componentId)` branch; `CapabilityComposeCodeExporter.kt` (1,232 lines) prints
  Kotlin from a second `when (componentId)`; and the **production** export path uses
  neither. `RevisionPinnedComposeExportExecutor` — the executor that emits
  `ALMOST_COMPILING_PROJECTION` — calls a *third* projector, the private
  `ComposeSourceProjection` in `ui-builder-runtime`
  (`ProductionUiBuilderRuntime.kt:332`, declared at `:515`); `ui-builder-runtime`
  contains no reference to `CapabilityComposeCodeExporter` at all. So a served Compose
  export and the in-browser exporter are separately hand-maintained renditions of the
  same catalog, and generating only the common one would leave the path users actually
  hit exactly as it is today.

  And there is a fourth rendition: `ComposeCodeExporter.export(document)` — the one-argument
  overload in `:ui-builder` commonMain — is a 145-line hard-coded "Confetti spike" that
  `require`s a single `layout/scaffold` root and emits a fixed `ConfettiScheduleHeader()`
  with its own chip/tab/list-item projection. Its sibling two-argument overload delegates to
  `CapabilityComposeCodeExporter`; this one delegates to nothing. Repo-wide callers are
  tests today, but it is retained main-source API and will drift the moment a component's
  call shape changes.

  Counting the catalog JSON itself, **five** surfaces describe one component set and
  nothing compares any of them.
* **The three already disagree**, and nothing detects it:

  | | ids |
  |---|---|
  | renderer implements, catalog does not declare | `m3/center-aligned-top-app-bar`, `m3/list-item`, `m3/primary-tab-row`, `m3/tab`, `shape/colour-dot` |
  | exporter prints, catalog does not declare | the same five |
  | catalog declares, exporter cannot print | `remote-compose/document` |

  …and that diff covers two of the five surfaces. The production projector and the legacy
  spike exporter are compared against nothing at all.

* **Slots are declared in one place and implemented in another.**
  `SlotCapability` (`name`, `cardinality`, `ordered`, `acceptedRoles`,
  `acceptedTraits`) is validated against JSON; what a slot *does* is whatever its
  renderer branch does. They are never compared, which is why "do slots really
  work" has no answer today. A slot is not derived from a `@Composable` lambda
  parameter of a real function, because there is no real function.
* **Export is unverified and says so.** Every `RevisionPinnedComposeExportExecutor`
  artifact carries `ALMOST_COMPILING_PROJECTION`. Nothing compiles the output —
  while `PlaygroundCompileService`, in this same server, already stages Kotlin,
  compiles it against a catalog's resolved classpath, discovers its previews,
  renders a first frame and mints a live session.

## The change

**1. Generate the capability catalog — for the components that *are* composables.**
`ComponentCapability` becomes a projection of `components.json` **plus** authored
policy, not a projection alone. Three corrections make the difference between a
generator and a regression.

**A parameter has a role, and only one role becomes a property.** Classifying every
non-slot parameter as a property breaks the existing document model on the first
component anyone tries: `Modifier` is carried by `modifierCapabilities` and the node's
own modifier list, and `Button`'s required `onClick: () -> Unit` comes from
`eventBindings`. Neither has a meaningful `jsonType`, so the naive rule would either
publish unusable `Modifier`/function properties or fail the constructible-parameter
gate for `Button` itself. The record therefore assigns each parameter a role:

| role | recognised by | projects to |
|---|---|---|
| `slot` | `@Composable`-annotated function type | `slots` |
| `modifier` | type is `androidx.compose.ui.Modifier` | `modifierCapabilities` |
| `event` | non-composable function type taking **no** value | `eventBindings` |
| `stateCallback` | a value-taking callback the **catalog has declared** as state-updating | a state variable, updated from the callback's argument |
| `property` | everything else that is constructible | `properties` |
| `unsupported` | anything left (`Painter`, `Shape`, `InteractionSource`) | nothing; blocks Tier 3 unless defaulted |

**A callback that carries a value is not always an event, and collapsing the two loses
data.** `m3/search-input-field` declares `(String) -> Unit`: the renderer updates state
straight from the callback's argument, and the Jetcaster fixture's `valueChange` action
carries no literal precisely because the value comes from the call. The generic event
dispatcher accepts no payload, so generating that parameter as an ordinary event would
clear `searchQuery` or fail to update it.

**But the signature cannot decide which it is, and an earlier draft of this table was wrong
to imply it can.** `onValueChange: (String) -> Unit` updates state; `onItemClick: (String)
-> Unit` has exactly the same type and dispatches an event carrying a payload. Splitting on
"does the function take a value" would make the generated adapter store the argument for
both, silently swallowing the click. So `stateCallback` is an **authored** classification —
the catalog declares which callbacks are state-updating and what the payload maps to — and
a value-taking callback with no such declaration stays an `event` whose payload is carried,
not consumed. Arity is the trigger for asking the question, never the answer.

| capability field | derived from |
|---|---|
| `componentId` | the catalog identity |
| `properties` | `property`-role parameters; `jsonType` from the Kotlin type, `allowedValues` from an enum's constants |
| `PropertyEditorControl` | the parameter's type — **for the control kind only**, see below |
| `slots` | `@Composable` lambda parameters, plus authored policy for any **value parameters the lambda receives** (see below). **Only whether the lambda argument is required** follows from the signature. Child occupancy does not (a required `RowScope` lambda may emit 0..N children), and `acceptedRoles`/`acceptedTraits` are **not** recoverable from a receiver scope. Both stay authored policy; the scope is recorded because it decides what a child's modifier may call |
| `code.symbol` / `code.imports` | the record's source-level **callable** FQN, never the JVM file-facade owner — deriving imports from the facade prints `androidx.compose.material3.ButtonKt`, which does not resolve |
| `wasm.adapterStatus` / `svg.status` | the conformance tier the component proved |

**A slot lambda can receive values, and building it from children alone drops them.**
`layout/scaffold`'s content lambda is handed `PaddingValues`, and both current surfaces
apply it — the renderer as
`slot("content").forEach { child(it, Modifier.padding(padding)) }`, the exporter
correspondingly. Receiver-scope metadata cannot derive that mapping: it says what the
child's modifier may call, not that this particular lambda parameter must be applied to
the child. So the record carries authored policy for a slot's value parameters, or the
reflective renderer must ignore the padding and Scaffold — a core component — cannot meet
the golden or pass Tier 3.

**Editor metadata is carried, not inferred.** A Kotlin type selects a coarse control
and nothing more. `CapabilityCatalogParser.EDITOR_OVERRIDES` additionally supplies the
finite `minimum`/`maximum`/`step` that `UiBuilderEditorState.numberBounds` requires —
`EditorPropertyControl.Number` is only chosen when `numberBounds != null` — and the
Material colour-token suggestions. Replacing it with type inference would turn every
numeric property `Unsupported` in the inspector and delete the colour choices. So the
record carries this metadata as authored per-property editor hints; generation fills
in the control kind, never the bounds.

**Not every builder node is a composable, and three of the current 25 are not.**
`shape/linear-gradient` and `shape/radial-gradient` identify
`Modifier.background(Brush…)` *expressions*; `asset/image` exposes an `assetKey`
resolved through the builder's asset registry rather than `Image`'s `Painter`. The
Jetcaster operations fixture uses all three, so generating solely from composable
records would drop or mis-shape them and make existing documents fail validation. These
keep an explicit **built-in/adapter record lane**: hand-authored capability entries,
marked as such, that the generator merges with rather than overwrites. The lane is the
declared exception, so it is visible instead of being an accident.

`CapabilityValidator` keeps its job unchanged — it just validates against a table
nobody typed by hand for the composable-backed majority.

**2. Two render tiers, one input.**

* **`:render-host` tier (authoritative).** A node renders by resolving its
  `ComposableMethod` from the catalog bundle's classpath and invoking it with
  arguments built from the node's properties and `@Composable` lambdas built from
  its slot children. `compose-ai-tools`' `RenderEngine` already invokes
  composables reflectively *and* already passes a `@Composable () -> Unit` as a
  reflective argument (`InvokeWithOptionalWrapper` hands the preview body to a
  wrapper exactly that way), so the mechanism is proven; what is new is the
  argument mapping and the slot-lambda construction.

  **This lives in `:render-host`, not `:server`.** It is bundle-classpath
  resolution and composable invocation — the rendering implementation the module
  graph assigns to `:render-host` — and putting it in `:server` would both violate
  that boundary (`checkRenderHostIsServerFree`) and deny the path to offline render
  callers. `:server` holds only the adapter that hands a document revision to it.
* **Wasm tier.** The in-browser renderer cannot reflectively invoke an arbitrary
  jar, so it needs a compiled-in set. The fix is not to keep hand-writing it:
  **generate** `UiBuilderRenderer`'s and `CapabilityComposeCodeExporter`'s
  dispatch from the record, check the generated sources in, and fail CI on a
  diff. The three-way drift above then cannot recur.

**3. Export through the oracle — a trusted one, not the playground lane.** A Compose
export is not returned until it has been compiled, rendered, and pixel-compared against
the builder's own render of that revision. Either the round trip passes or the export
returns the failure, and `ALMOST_COMPILING_PROJECTION` is deleted rather than reworded.

The oracle must be **separately initialized**. `PlaygroundCompileService` exists only
when `--playground` / `--playground-bundle` is enabled, and the packaged deployment
deliberately keeps that visitor-Kotlin lane off; routing exports through it would make
every export fail on a default UI-builder deployment. So the compile+render oracle is
its own trusted service — same staging and compile mechanics, initialized with the
builder rather than with the playground, and requiring a non-null rendered frame before
it reports success. The playground may share it; it does not own it.

**4. Conformance decides which *components* are authorable; the allowlist still
decides which *catalogs* an operator serves.** These are two different gates and
collapsing them loses one.

* **Conformance (per component).** A component enters the builder's catalog only at
  Tier 3 — every parameter constructible-or-defaulted from the wire (by the roles
  above), every slot a `@Composable` lambda, argument-deterministic, parent-agnostic,
  and the document → Kotlin → compile → render → pixel-compare round trip green. A
  typical app's `SpeakerItemView(speaker: SpeakerDetails, …)` fails at the first check,
  which is the intended answer.
* **`--ui-builder-catalogs` stays (per catalog).** It is operator policy, not a
  conformance signal: `ServeCommandOptions` is explicit that serving a catalog does not
  enable its builder, and `ServeHttpServer` gates `/ui-builder/<system>/` on this set.
  An operator must keep the ability to serve a catalog read-only. Conformance narrows
  what is authorable *within* an admitted catalog; it never admits one by itself.

The two gates also settle `remote-m3`, which this document says reaches only Tier 2:
it stays admitted by the allowlist with an explicit **typed-embed admission rule** —
its widget-host scaffolds and the `remote-compose/document` node are authorable through
the Remote Compose lane, not through Compose-export conformance. Without that rule the
mechanical gate would silently switch off the lane §5 keeps.

**5. Remote Compose keeps its own lane.** `remote-compose/document` embeds a
nested *player*, not a composable, and its document-authored slot names
(registered as custom-component configs, reached through the inferred
`DynamicSlots` trait — see [UI_BUILDER_REMOTE_COMPOSE.md](UI_BUILDER_REMOTE_COMPOSE.md))
are a second component model wearing the first one's clothes. Do not force it
into the record: keep it an explicitly typed embed node with its `namedValues`
boundary, keep it out of the Compose exporter as it already is, and let
`remote-m3` reach Tier 2 through a Remote Compose *creation-DSL* generator rather
than the Compose one.

## Sequence

Phases 0–3 of the upstream plan (the PSI measurement, `components.json`, the
parameter override format, generation by printing) land in `compose-ai-tools`
first; nothing here can be built on a record that does not exist. Then, here:

1. **Now, independent of everything:** add a `catalog ↔ renderer ↔ exporter`
   component-id diff as a test, and fix the seven ids above.

   The diff cannot be strict equality across all three, because §5 deliberately keeps
   `remote-compose/document` out of the Compose exporter: a plain equality check would
   report the intentional Remote Compose lane as drift forever. So the test compares
   **catalog ↔ renderer** on every id, and **catalog ↔ exporter** over
   Compose-exportable records only, with the typed-embed exemption named explicitly in
   one list rather than implied by an absence.

   Give the catalog resource its accurate name — it is a Jetcaster transcription, and
   two unrelated things sharing `m3-catalog` on one host is most of why the builder is
   hard to reason about — but **keep the old path as a compatibility alias**. Both
   `CurrentM3UiBuilderCatalogExecutor.RESOURCE` and the external-consumer gate
   (`scripts/ui-builder-external-consumer`, which asserts the released JAR contains
   `ee/schimke/composeai/uibuilder/catalogs/m3-catalog-v1.json`) pin that exact
   classpath name in a published artifact, so an unqualified rename is a breaking
   change to consumers, not a cleanup.
2. Generate the capability catalog from a published `components.json`; keep the
   hand-written file as a golden until the generated one matches it.
3. Generate the Wasm renderer/exporter dispatch; CI-diff the checked-in output.
   **Including the production projector, and settling the legacy spike.**
   `ComposeSourceProjection` is the one a served export actually runs, so generating only
   `CapabilityComposeCodeExporter` would satisfy the letter of this phase and leave the real
   export path hand-maintained — the exact drift this plan exists to end. Either the
   production executor is switched onto the generated path, or its projection is generated
   and CI-diffed too. `ComposeCodeExporter.export(document)`, the hard-coded Confetti spike,
   is retained main-source API with only test callers: delegate it, generate it, or retire
   it before claiming the exporter surfaces agree. Leaving it is the cheapest way for this
   whole effort to be quietly wrong again.
4. Reflective invocation on the daemon tier, behind a flag, proven against the
   Wasm tier's pixels.
5. Export through the oracle; drop `ALMOST_COMPILING_PROJECTION`.
6. Tier-3 component filtering is layered **beneath** the retained
   `--ui-builder-catalogs` allowlist — it never replaces it. §4 is the normative
   statement: conformance decides which *components* are authorable, the allowlist
   decides which *catalogs* an operator serves editable at all. Dropping the allowlist
   here would remove the operator's ability to serve a conformant catalog read-only and
   would switch off the Tier-2 `remote-m3` lane §5 keeps.

## Success criteria

* The renderer, **every** exporter and the catalog cannot disagree, because every
  surface but the catalog is generated and CI diffs them. There are five, not two:
  counting only the common exporter leaves `ComposeSourceProjection` — the one a served
  export runs — and the legacy `ComposeCodeExporter.export(document)` spike outside the
  guarantee.
* Every Compose export the server hands a user has been compiled and rendered.
* A component's slots are its `@Composable` lambda parameters, so "does this
  component have a slot, and must a lambda be passed for it" is answered by the
  type system rather than by two tables agreeing. Everything about the slot's
  *contents* — how many children it may hold (`SlotCardinality.min`/`max`) and which
  are accepted — stays authored policy: a required `RowScope.() -> Unit` obliges a
  caller to pass a lambda, and that lambda may still emit zero children, so deriving
  occupancy from requiredness would reintroduce exactly the wrong validation.
* Confetti — a typical app — reaches the preview browser and the API panel and
  **no** UI-builder component. If it reaches Tier 3, the gate is too loose.
