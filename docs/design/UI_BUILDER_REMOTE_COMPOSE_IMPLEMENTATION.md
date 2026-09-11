# Remote Compose implementation progress

The scope and operation census are in
[the completeness review](UI_BUILDER_REMOTE_COMPOSE_COMPLETENESS_REVIEW.md).
The runnable proof is in [experiments/remote-compose-poc](../../experiments/remote-compose-poc/README.md).
This document tracks production implementation; it does not redefine completion around the proof.

## Landing scope

The current goal is to land the existing work behind a default-off compile-time flag, rather than
continue extending operation coverage in this change. `-PuiBuilderRemoteCompose=true` enables the
experimental authoring and export paths in the existing WASM app, server and MCP adapter. See the
[build option and gated surfaces](../development/UI_BUILDER_FEATURE_FLAGS.md). The remaining production
work below is future scope and is not a claim that the full authoring system is complete.

## Authoring boundary

The visual editor focuses on layouts. Its tree remains a semantic hierarchy of components, layouts,
state and actions; it is not the flat Remote Compose operation stream. Richer authoring can live in
MCP without requiring a visual control for every operation. Precise lowering belongs in the export
layer. Common concepts keep their Compose mappings, including selection as `when`.

## JSON and binary export delivery

The existing service, WASM Export menu and MCP now route Remote document exports through the shared
`RemoteDocumentJsonExporter`. The JSON source is UTF-8; `.rc` is compiled by the existing offline
`remotecompose-json` publication and travels as base64 in the protocol. Both are revision-pinned and
content-digested. HTTP downloads return diagnostics as 422 responses, rather than offering a partial
or empty document as a successful file.

[Contracts PR #63](https://github.com/yschimke/compose-preview-contracts/pull/63) declares `json` / `rc`
and independently advertised `remoteJson` / `remoteDocument` flags. The host advertises them only for
catalogs declaring the Remote Compose platform. The binary flag also requires a successful compiler
profile probe. The temporary strict-serialization bridge keeps builds on released contracts usable;
without local contracts the new formats are unadvertised. Remove that bridge when the release is
pinned. Local contracts and the compiler can be staged into one manifest:

```shell
python3 scripts/stage-local-dependency.py --checkout /path/to/contracts --module :ui-builder-protocol --output build/local-dependencies/remote-export
python3 scripts/stage-local-dependency.py --checkout /path/to/compose-ai-tools --module :remotecompose-json --output build/local-dependencies/remote-export
VERIFY_REMOTE_DOCUMENT_EXPORTS=true ./gradlew -PlocalDependencies=build/local-dependencies/remote-export/local-dependencies.properties :server:test --tests '*RemoteDocumentExportExecutorTest' --tests '*ServeUiBuilderRoutesTest' :mcp:test --tests '*UiBuilderMcpAdapterTest' :ui-builder:jvmTest --tests '*EditorExportMenuTest' :ui-builder:wasmFrontendDist :server:installDist
```

The hosted `ui_builder_export` accepts these formats. Standalone MCP adds `export_design`, with an
explicit revision and a format enum derived from its installed contracts; its existing source and
image tools remain available. Catalog capabilities determine which declared formats a host supports.

The staged checks pass 32 targeted tests, including a real persistent-service HTTP proof. The existing
WASM app's actual menu downloads a 757-byte document; the revision-pinned HTTP endpoint and hosted MCP
return identical bytes and SHA-256. The browser and hosted MCP also agree on the 1,659-byte JSON
source. [Browser evidence and measurements](evidence/ui-builder-document-exports/README.md)
record the sample and the distinction between delivery and preview fidelity. The released-floor
runtime suite, ABI, 46 shared-export tests, targeted HTTP/MCP/menu checks and Wasm compilation also
pass; new-format-only tests skip explicitly without staged contracts. Golden regeneration changes
no committed fixtures.

This completes the delivery path for the current lowering subset, not Remote Compose completeness.
Unsaved/local document compilation, broader component recipes and modifier mappings, independent
String selection, nullable/computed values, callbacks, loop/reusable fidelity, and the operation coverage
remaining in the review are still required.

## Live document preview

For designs whose host advertises RC export, the existing Preview button loads the compiled
document into the existing CMP/WASM player. A saved design uses its exact revision; pending edits
and local-storage designs submit their current content to the temporary document-export route.
Design mode keeps the semantic authoring canvas. Document/generation changes cancel and refresh the
preview. Compilation and player-support errors are shown in place.

[Actual browser playback and MCP evidence](evidence/ui-builder-live-document-preview/README.md)
shows clicks advancing through both cases and the fallback, then a live MCP edit updating the open
Preview to revision 1. Browser network bytes and hosted MCP exports agree at both revisions. The
full staged editor suite passes 943 tests and the WASM build passes. The six new tests cover
playback/actions at densities 1 and 2, pending saves, wrong revisions, late responses, and the
additional interactive pane. The released dependency floor compiles; its known empty-Box player
limitation skips the two density variants of that fixture until the local player fix is selected,
and the four lifecycle/routing tests pass. Staged validation can require playback with
`VERIFY_REMOTE_DOCUMENT_PREVIEW=true`.

## Unsaved and local Remote documents

`POST /api/ui-builder/v1/documents/export.json` and `.rc` accept the existing `DesignDocumentV1`
payload with export authorization. `UiBuilderServiceRequest.ExportDocument` resolves the exact
catalog pin, validates topology/environment/quotas, and uses the same bounded export runner and
artifact checks as saved export. It reads no saved design and writes no design, revision or audit
record. The source is self-contained Remote JSON/RC; native image/Kotlin compilation and resolution
of saved assets are outside this operation.

Hosted MCP exposes the same operation as `ui_builder_export_document`, with `document` and `format`.
It returns the normal export artifact and located diagnostics. Standalone MCP exposes
`export_document` with the same arguments and the same `ExportArtifactV1`, using the temporary HTTP
route with `?artifact=true`. That representation preserves diagnostics, including refusals; the
normal download route still answers 422 when compilation produces errors. The adapter marks a
refused artifact as an MCP error and checks format, SHA-256/ETag and the supplied revision before
returning content. It uses the existing contracts and adds no dependency on the server or runtime.

The existing WASM Preview button compiles the current document before a live save completes and
also works in local-storage mode. Temporary results are labelled “unsaved changes.” Local Remote
designs get JSON/RC copy/download actions; shareable export links remain specific to saved designs.
[Browser proof and artifacts](evidence/ui-builder-unsaved-remote-preview/README.md) cover local
state editing, real playback, matching browser/MCP bytes and absence from the saved-design list.
A second browser run holds a live save: the temporary preview and both downloads use the edited
document while revision 0 remains stored, then saved revision 1 yields identical RC bytes and the
same displayed branch. Both runs finish with no browser errors.

The combined run passes 952 editor JVM tests, 51 shared-export tests, 189 runtime tests and 34
targeted server/MCP tests, plus runtime ABI verification and the WASM/server distribution builds.
HTTP tests verify authentication, invalid pins/topology, real compilation and byte-for-byte unchanged
saved state/audit files when a supplied draft reuses an existing design ID.

## Authoring state and actions

The editing canvas now measures natural content height and then lays out that same composition
within a finite extent. This fixes fill-only selected branches collapsing under unbounded measurement,
while preserving long content and remeasuring on document or preview-state changes. The saved export
sample now draws its 312 × 312 dp selected child inside 24 dp padding in the actual WASM app.
[Before/after browser evidence](evidence/ui-builder-canvas-fill/README.md) includes measured bounds;
four new rendering/interaction tests and the full 937-test UI-builder JVM suite pass.

The browser's Screen panel can add, edit and remove state declarations. The Layer inspector can
add, edit, reorder and remove actions on existing controls. Both send the released
`setStateVariable`, `removeStateVariable` and `setEventBinding` mutations that MCP uses. Local
sessions translate those same mutations, including undo and redo. Removal or narrowing of state
still in use is rejected. Editing a declaration refreshes that variable in the preview while
preserving unrelated interaction state.

Actions execute in order, with each action observing prior writes. Remote Kotlin export uses
AndroidX `combinedAction` to preserve lists rather than refusing all multi-action handlers.
The existing restrictions on nullable Remote values remain explicit.

Evidence from actual Compose controls, before and after wiring a flag to a button:

- [Before wiring](evidence/ui-builder-behavior-authoring/before-wiring.png)
- [After wiring](evidence/ui-builder-behavior-authoring/after-wiring.png)

These are interaction-test captures of the production inspectors, not screenshots of a released
site. `BehaviorInspectorTest` drives the controls through the editor reducer. The full builder JVM
suite and `ServeUiBuilderMcpIntegrationTest` cover browser/local collaboration and actual MCP
requests respectively; the browser Wasm target also compiles.

## Layout click export

The shared Compose projection maps clicks on Box, Row and Column to `Modifier.clickable`, after
these layouts' authored modifier chains. Controls keep their declared event parameters. The
nested callback uses the shared generator's `ScreenValue.ActionLambda`, with the same validation
and ordered state writes as ordinary handlers. Local generator publications make this path
available before a release; the released floor reports a located refusal for the missing shape.

The exact emitted Kotlin is compiled by `GeneratedLayoutClicksTest`, which clicks both selected
branches and the fallback at densities 1 and 2 and checks the authored padding. The production
gate and hosted MCP tests compare their output with that same source. See
[compiled interaction evidence](evidence/ui-builder-layout-clicks/README.md). The combined run passes 945 editor tests,
46 shared-export tests, 26 targeted server/MCP tests, runtime ABI checks and the WASM build.
The actual existing browser Code pane and live MCP export are also verified; the released floor
passes compilation and its explicit-refusal/compiled-fixture tests. Golden regeneration changes no
existing fixtures.

The combined local profile includes contracts, both generator publications, the JSON compiler and
the player. Ordinary roots now also use the Remote Kotlin emitter when the catalog declares the
Remote Compose platform. The existing Code pane and hosted MCP export share that route; no widget
wrapper is added to the semantic tree. Layout clicks become Remote modifier actions, and catalog
controls with an Action callback keep their authored callback even when the parameter has a default.

[Ordinary-root source evidence](evidence/ui-builder-remote-root-source/README.md) records the real
WASM Code pane, all three MCP artifacts, and Android capture/playback of the exact exported source.
The Android proof clicks through both states and the fallback at densities 1 and 2, checking padding
and active layout width. After rebasing onto main `7012a87a`, the combined build passes 951 editor
JVM tests, 51 shared-export tests, 36 targeted server/MCP tests, runtime ABI checks and WASM/server
distribution builds; the real browser export proof also passes on that build. This proves source export and real document capture. The server's separate
native PNG compilation route still needs ordinary Remote-root support; saved-document WASM playback
uses the JSON/binary export route described above.

## Shared Compose export

The browser code pane and the server's record-driven export now project scalar declarations,
state reads and ordered click handlers into the shared generator. Progress values bound to state
are read inside the generated callback. Declared decimal state accepts integer JSON spellings
such as `0` and `1` without changing the Kotlin type.

Catalog scalar properties now accept state reads of a matching declared type, using one rule shared
by the browser and service. A text property can bind String state, and a Boolean property can bind
a flag or a comparison. Nullable state still needs a property that admits null; colours, assets and
constrained enums retain their own value requirements. The existing canvas resolves these property
reads while preserving the variable references used by two-way inputs.

`BehaviorScreenExportTest` pins the generated source byte for byte against
[`state-actions.kt.txt`](fixtures/ui-builder/state-actions.kt.txt). The builder compiles that exact
file as test source, and `GeneratedStateActionsTest` clicks both the existing builder canvas and the
resulting Material 3 button using the same saved document. It checks the changed label, enabled
state and progress. This is generated code running against real Compose. The MCP integration test
also authors a declaration, binding and ordered handler, then
exports them through the production service.

Null initial values, state comparisons and callbacks that consume an event value still require
extensions to the shared model. Their refusals remain explicit; they are required follow-up work.

## State selection generator

The shared generator extension is implemented in
[compose-ai-tools#5383](https://github.com/yschimke/compose-ai-tools/pull/5383) and is available through
the local dependency workflow. `ScreenNode.selection` selects ordinary child slots with a typed
`when`, preserving authored values such as `10` and `20` and an optional fallback. Every branch is
validated. Selection introduces no layout or receiver scope, so a containing layout's modifiers
remain usable. It follows ordinary Compose composition behavior; transitions and retention of
inactive branches remain later polish.

The proof discovers real Material 3 components, generates Kotlin, compiles it, and clicks from
the initial branch to a second branch and then the fallback. The
[generated source](https://github.com/yschimke/compose-ai-tools/blob/2b16c83bf2a8596d22a37f22f795afb5c49085d9/docs/evidence/screen-selection/SelectedScreen.kt.txt)
and [render evidence](https://github.com/yschimke/compose-ai-tools/tree/2b16c83bf2a8596d22a37f22f795afb5c49085d9/docs/evidence/screen-selection)
are committed with the extension. Its 42 screen-model tests, 574 discovery tests, four real Compose
functional tests and Wasm compilation pass. The existing builder also passes 915 JVM tests, 31
shared-export tests, 16 targeted behavior/MCP tests and Wasm compilation against the locally staged
generator. Those consumer tests verify compatibility; they do not yet prove selection authoring.

The existing Box inspector now has a **Show by state** section. It binds a declared scalar value,
assigns a matching value to each child, and optionally designates one child as the fallback.
The configuration is one typed `showByState` object property, so browser edits and MCP use the
same atomic `setProperty` mutation. Ordinary Box modifiers and child layout scopes remain intact.
Shared validation checks every case, undeclared selectors, unassigned children, duplicate values
(including Float narrowing), and malformed wrappers. The existing canvas draws only the selected
child; an unmatched value without a fallback draws no child. Undo/redo preserves the configuration,
and subtree copies remap case references to the copied children.

The design projection emits the shared generator's structural selection inside the normal Box.
Until the upstream generator is released, opt in to locally staged `screen-model` and
`preview-discovery` artifacts. The released generator floor explicitly refuses the unsupported
selection shape; it cannot silently turn selection into simultaneous children. Remote StateLayout
lowering now supports non-null integer, decimal and Boolean selectors, including an empty branch
when no fallback is authored. String and nullable selectors still require target support.
This is an implementation chunk, not completion of the Remote Compose scope.

`StateSelectionInspectorTest` operates the actual inspector, changes the bound declaration, and
asserts the canvas's selected content; it also verifies undo/redo and remapped subtree copies.
[Before configuration](evidence/ui-builder-state-selection/before.png) and
[configured selector](evidence/ui-builder-state-selection/configured.png) are captures of those
production composables in the interaction harness. They are not a separate editor or a released
website screenshot. `ServeUiBuilderMcpIntegrationTest` discovers the property through the catalog,
authors it through MCP and proves that removing its selector declaration is rejected.
`StateSelectionExportTest` validates the same shape and checks typed cases, fallback and padding
through the actual shared export gate. Run that test with `VERIFY_LOCAL_STATE_SELECTION=true`
and a manifest overriding only the generator pair to require generated code; without the override
it verifies the released floor's explicit refusal.

Verification for this chunk: 931 builder tests, 35 shared-export tests, 188 runtime tests and
15 targeted server/MCP tests pass with released dependencies. The same 15 server/MCP tests pass
with the local generator override. The existing Wasm target compiles, runtime ABI verification
passes, and golden regeneration adds only the Box property to the two synthesized catalogs.


## Remote StateLayout export

The production Remote emitter retains the authored Box and its modifiers, maps case values to
physical StateLayout child indexes, and records a fallback branch even when it must be empty.
Selectors, scalar property reads and action writes share one mutable declaration. Inline Remote
content now emits those declarations as well as the component body.

The [standalone Android proof](../../experiments/remote-state-selection/README.md) compiles exact
production exports against AndroidX alpha18, captures real `.rc` bytes, and exercises state changes
in the native Android player. Its seven scenarios cover integers, booleans, decimals (including adjacent Float values), an empty
fallback, 13 cases, and signed integer limits. This caught the player's integer equality overflow;
the emitter compares two 16-bit halves so the generated condition retains exact Int semantics.
Expressions are materialized per case to bound operand-mask size. Keeping expressions inside the
authored Box also ensures they update during the player's ordinary paint path.

The proof uses host overrides, not synthetic clicks. Transition appearance and inactive-branch
retention are not asserted here. This adds Remote Kotlin lowering to the existing editor/MCP
selection shape; direct JSON, binary export and saved-document playback are covered by the later integration sections above.

Verification: seven real-player scenarios, 39 shared-export tests, 931 existing builder tests,
189 runtime tests, and 15 targeted server/MCP tests pass. The local generator manifest also
passes the export/runtime/server checks after the rebase. Wasm compilation passes. Golden
regeneration changes only the selection capability notes in the three catalog fixtures.
The preview plugin now registers its desktop tasks after evaluation; a separate build fix preserves
the production-only preview filter, with a test inspecting the actual packaged `previews.json`.

## Direct JSON integration findings

The extended JSON proof now preserves authored integer selectors, including values above Float's
exact range and both Int limits. Five checks in `IntegerSelectionJsonTest` verify real player
updates and make the stock parser's limitation explicit. AndroidX alpha19 cannot declare derived
integer expressions through authoring JSON. A small experimental component-registry adapter proves
that bounded integer expressions work, but it is not a production or stock-parser dialect.
The shared compiler profile below establishes that capability for this compiler. These extended
sources still require the named profile and are not portable to the stock AndroidX parser.

The proof also exposed valid AndroidX empty Boxes without `LayoutComponentContent`, which the CMP
player rejected. [rc-players#92](https://github.com/yschimke/rc-players/pull/92) supplies the owning player's fix: it models Box content as nullable, preserves geometry and
modifiers, and rejects stray child layouts. The local player passes 118 runtime tests, 216 Compose
tests, ABI checks and Wasm compilation. The JSON probe passes with genuinely empty branches against
that local build. The existing builder also passes all 931 JVM tests and Wasm compilation against
the staged player stack. Both the experiment's local checkout option and the production staging
workflow avoid waiting for a release. This dependency work supports the existing builder; it adds no editor.

## Shared authoring JSON compiler profile

[compose-ai-tools#5396](https://github.com/yschimke/compose-ai-tools/pull/5396) adds the explicit
`compose-preview-integer-expressions-v1` profile to the existing plain-JVM `remotecompose-json`
module. The document's top-level `compilerProfile` selects it; ordinary authoring JSON retains the
stock AndroidX parser path. The profile emits standard integer-expression operations and validates
integer references, duplicate names, infix syntax, function arity and the 32-slot wire limit.
Unknown profiles fail compilation. This source profile is distinct from the binary header's API
level and feature mask.

The local dependency workflow stages its normal Maven publication. With
`-PlocalJsonCompilerManifest=…`, the real-player proof calls the shared compiler's public API and
bypasses its experimental Java adapter. All ten proof tests pass, including both Int limits,
adjacent integers above Float's exact range, 13 cases, callbacks and density 2. The owning module
passes 28 JVM tests, ABI and boundary checks. The existing server compiles against the local
publication and passes all 103 HTTP routing tests. Golden regeneration makes no changes.

This is the verified compiler seam for design-to-JSON and binary export. The delivery and saved-preview sections above describe its builder/MCP integration. This compiler
profile does not expand the claimed catalog coverage.

## Shared design-to-JSON lowering

`RemoteDocumentJsonExporter` now lives in the existing multiplatform `ui-builder-export` module.
It lowers authored Box/Row/Column trees, their spacing and cross-axis alignment, basic ordered
modifiers, integer/Boolean/decimal selection and numeric/Boolean action writes into authoring JSON. The
original Box carries its modifiers and click handler; bounded expressions precede the StateLayout
inside it. Case order follows the authored child order. Boolean state is encoded as named integer
0/1, with its authored kind retained in the export result for the eventual host bridge.

The shared gate returns located refusals for unmapped catalog components and fields instead of
omitting them. The protocol-document entry point checks predicates, accessibility, asset bindings
and token bindings before converting to the editor's document shape, so that conversion cannot
silently drop unsupported semantics. Material components still need catalog-specific recipes or
their real compilation lane.

`RemoteDocumentJsonPlayerFixturesTest` writes exact output from this exporter.
`ProductionJsonExportTest` compiles and plays five scenarios at two densities: ordinary integers,
adjacent integers above Float's exact range, both Int limits, Boolean selection and 13 cases.
It checks real click dispatch with ordered state writes, host-driven fallback and retained padding.
The player supplies its normal click indication; branch assertions identify the resulting color
channel without treating that indication as a change to the authored fill.

A separate compiler probe proves that equal initial String values share a text ID in the stock
parser. The explicit state compiler profile described below now preserves independent state
and literal IDs. String selection, nullable state, dynamic dimensions, scoped child alignment,
catalog typography/assets and reusable instances also remain to be mapped.

Verification: 46 shared-export tests and 189 runtime tests pass, the shared module and existing
editor compile for Wasm, and all 12 proof tests pass, including the ten generated-document scenarios.
The delivery and saved-preview sections above describe its editor, service and MCP integration.

## PNG export from compiled Remote documents

The existing PNG export lane now lowers ordinary Remote layout trees through the shared JSON
exporter and compiler, then plays those exact `.rc` bytes through the packaged Compose Remote
player. The render projection contains a single embedded document; the authored hierarchy and
saved source remain intact. Dimensions, density and font scale come from the authored environment.
Compiler refusals retain their located diagnostics and return HTTP 422 for downloads.

`POST /api/ui-builder/v1/documents/export.png` accepts current drafts through the existing bounded,
authorized export service. The existing WASM Export menu enables PNG for local designs and submits
pending edits without waiting for persistence. Hosted `ui_builder_export_document` and standalone
`export_document` accept `png` and retain the same artifact digest and diagnostics.

The real renderer proof covers densities 1 and 2, saved and supplied HTTP export, hosted MCP,
unsupported lowering and a local browser edit. [Evidence and reproduction instructions](
evidence/ui-builder-remote-png-export/README.md) describe the exact subset exercised.
Imported documents, custom Remote content and Wear record-free roots keep their existing rendering
lane; combined JSON lowering of those constructs remains separate work.

## Independent text state

The shared exporter uses `compose-preview-state-v1` when a document declares non-null text state.
Its `mutableString` declarations use AndroidX's named-string writer API to allocate independent IDs.
The owning compiler extension emits ordinary NamedVariable/TextData operations. Its previous
unextended and integer-expression profiles retain their behavior.

Ordered `set`/`select` assignments emit ValueStringChange actions. An immutable literal resource
holds each assigned value, preserving empty text, Unicode and strings beginning with `@` or `$`
without interpreting them as references. Generated resource names avoid authored state names.
The existing Screen text editor and action controls use these mappings through the same JSON/RC
HTTP and MCP export paths; there is no additional Remote-only editor mode.

The real-player proof shows two state variables and a literal initially containing `Ready`.
A click changes only the first variable; a host update changes only the second. Exact production
exports additionally exercise repeated ordered actions with six literal cases. The existing WASM
editor proof edits a local text variable and compares its JSON/RC downloads with hosted MCP.
[Source, screenshots and reproduction](evidence/ui-builder-remote-string-state/README.md).

This enables independent declarations and literal assignments. String equality/branch selection,
nullable text, text expressions and catalog-specific typography recipes remain separate work.
The visual text fixture is a direct authoring-JSON player proof, not a claim that arbitrary M3 Text
components now have a precise JSON recipe.

## Layout controls in the existing inspector

The Properties inspector now exposes authored modifiers in a compact Layout section. Numeric and
enum fields reuse the existing modifier editor, and Add modifier uses the catalog and parent-scope
rules already used by the context menu. State selection remains a semantic property on the layout.
Modifiers retain their authored order; repeated types are edited at their exact chain position.
Modifiers without dedicated controls remain visible as source rather than being discarded.

These controls dispatch the existing `SetModifiers` operation used by local persistence, undo/redo
and the MCP mutation path. No additional wire shape or Remote-only editor is introduced.
The [actual WASM browser proof](evidence/ui-builder-modifier-inspector/README.md) changes top padding
from 24 to 40 dp while preserving the state link and other edges. The downloaded PNG moves its
content boundary by precisely 16 pixels at density 1 and matches hosted MCP byte for byte.

## Decimal selection player prerequisite

The isolated JSON proof now reproduces creation-compose's Float equality lowering and checks
ordinary decimals, adjacent Floats, subnormals and opposite extremes in the real CMP player.
It exposed a shared numeric-ID defect: Float writes did not publish the truncated integer view
that AndroidX makes available to integer expressions. [The player correction](https://github.com/yschimke/rc-players/pull/94) passes those
four switching scenarios and a direct comparison against AndroidX alpha19's numeric state.
The owning runtime/Compose suites, ABI and WASM checks also pass.

[Before/after captures and reproduction](evidence/ui-builder-float-selection-player/README.md)
record the proof. Its original parser adapter stays confined to the experiment. The production integration below
now uses the shared compiler publication and exporter instead.

## Decimal selection through JSON, live preview and MCP

The shared JSON exporter now lowers finite decimal selectors with `floatEquals` declarations in
`compose-preview-state-v1`. These emit the exact creation-compose comparison sequence and expose
an integer 0/1 for the existing bounded ordinal expressions. Case order, fallback, the authored
Box and its modifiers remain intact. Literal and state selectors retain Float precision; the
existing regular Compose `when` and Remote Kotlin mappings remain available.

The compiler validates finite operands, scalar Float references, unique names and supported fields.
Its unextended and integer-only profiles retain their behavior. The CMP player must include the
numeric-ID correction in rc-players#94. Both committed publications are staged locally, without
an upstream release or another editor application.

[Actual browser and export evidence](evidence/ui-builder-decimal-selection/README.md) shows the
existing WASM Preview switching 2.5 → fallback → 1.25 → 2.5 through real clicks. The Screen editor
then changes the local initial value from 2.5 to 3.75. JSON, RC and PNG downloads before and after
that edit match independently requested hosted MCP artifacts. No browser errors or saved server
designs are produced. The exact production JSON additionally passes nine real-player scenarios at
two densities, covering adjacent Floats, subnormals, opposite finite extremes, ordered actions,
integer/Boolean regression cases and padding. All 53 shared-export tests and 953 editor tests pass;
the final main-based compiler passes 35 tests and its ABI check.

## Repetition and reusable instances in JSON export

[The static expansion proof](evidence/ui-builder-repetition-proof/README.md) now establishes that
authored rows can place a parameterized reusable body and compile into ordinary Remote layouts.
The existing editor canvas, temporary expanded tree and real compiled document match every pixel
at densities 1 and 2. All six expanded cells remain independently clickable and update the shared
selection. Placement padding and row-specific spacing are retained.

The [production integration](evidence/ui-builder-repetition-export/README.md) now accepts authored
rows and reusable instances in the shared JSON exporter. It preserves placement modifiers, ordered
actions and lexical argument scopes, with bounded expansion and reference validation. Browser and
service validation check the supplied argument values; both reducers recognize detached component
bodies, so editing the design does not fail on a body with no screen parent. Remote catalogs offer
the existing For each layout, and its inspector reports the row count.

The actual WASM app passes live clicks on all six repeated cells and saves an initial-state edit.
All six JSON/RC/PNG downloads, before and after that edit, match hosted MCP artifacts byte for byte.
MCP also creates a design, edits its row data and exports the saved revision while retaining its
component definition. Tests pass for 65 shared-export cases, 191 runtime cases and 954 editor cases;
one separate opt-in canvas proof is skipped in the ordinary editor suite. Runtime ABI and WASM
distribution checks pass.

Remote Kotlin loop generation, record-driven Compose loops/calls and per-instance MCP addressing
remain integration work. Static expansion does not substitute for runtime lists, lazy keys, scoped
state or callback/slot parameters.

The [Kotlin feasibility proof](evidence/ui-builder-repetition-source/README.md) now generates actual
typed row lists, `forEach` loops and reusable composables from the same semantic fixture for both
ordinary Compose and creation-compose. Numeric arguments, placement modifiers and explicit callbacks
preserve layout and shared-state behavior. Four compile/interaction tests pass at densities 1 and 2;
initial images match the independent JSON/player reference pixel for pixel, and all 24 clicks update
the expected state. Ordinary Compose's click indication is the only measured post-click difference,
confined to the clicked cell. This is an isolated generator prototype, not yet a production export
route. Proceed with the proven source form in the shared generator and Remote emitter; retain the
catalog-derived type, nested scope and callback/slot requirements rather than shipping the prototype.

The shared generator now implements `ScreenRepetition` and `ScreenValue.RowRead` in
[generator commit 39d3f93](https://github.com/yschimke/compose-ai-tools/commit/39d3f930d2841f92796ed879683869ee96a8dfcd)
on [PR #5383](https://github.com/yschimke/compose-ai-tools/pull/5383). It emits typed row classes and
actual `forEach` loops, checks row schemas and field types, preserves nested initializer/template
scopes, validates empty templates and supports row values captured by checked assignment callbacks.
Its functional test discovers Material 3, compiles the production generator's exact output and
clicks all repeated buttons at densities 1 and 2. All 52 model and 574 discovery tests pass, as does
WASM compilation. Both generator publications are staged locally; the existing builder/server
compile and all 65 shared-export tests pass against them. No release is needed.

This provides the shared primitive. The builder's semantic-loop projection, reusable-function
lowering, Remote Kotlin integration and browser/MCP source parity remain required next steps.

Shared reusable-function generation now exists in
[generator commit 7d674b3](https://github.com/yschimke/compose-ai-tools/commit/7d674b3555de7a4a439f13e417270758a1e6f428).
`ScreenFunction`, explicit value/callback parameters, `ScreenNode.function` and checked parameter
reads emit one composable definition and calls from typed loops. Definitions cannot implicitly
capture screen state, caller rows or layout receivers; recursive calls and invalid argument or
callback bindings refuse the artifact. Functions can forward values and callbacks to other definitions.
The real Material 3 proof physically clicks repeated function instances at densities 1 and 2 and
checks their authored 0, 8 and 16 dp padding. All 60 model and 574 discovery tests pass, as do both
direct-loop and function compile/interaction proofs and WASM compilation.

Both committed generator publications are staged locally. The existing builder passes 954 editor
tests (one separate opt-in proof skipped), all 65 shared-export tests and WASM/server compilation
against them.

The semantic loop/component projection now consumes these shared forms. The existing Compose export
gate emits real typed loops and reusable composables, preserves placement modifiers and passes state
reads and callbacks explicitly. Nested component calls forward those parameters; nested loops use
their own row scope while row initializers can read an enclosing row. Missing or conflicting bindings,
cycles and invalid row data refuse instead of expanding or dropping authored content.

[Production source and compiled interaction evidence](evidence/ui-builder-scoped-compose-export/README.md)
replaces the ordinary Compose half of the earlier Python prototype. The committed editor fixture's
exact generated source compiles and matches the independent Remote JSON reference pixel for pixel
at densities 1 and 2. All twelve physical clicks select the expected state. Browser-gate and service
executor tests compare the same source, with the service's revision/provenance header checked
separately. The staged checks pass 74 shared-export tests, 954 editor tests (one separate opt-in
proof skipped), 60 service tests and WASM compilation. The equivalent Remote Kotlin projection is now implemented in `RemoteContentEmitter`: authored
rows emit typed loops, definitions emit once as `@RemoteComposable` functions, and call sites pass
modifiers, values, state reads, images and actions explicitly. Nested lexical scopes are checked;
missing or incompatible bindings and recursive definitions refuse the artifact. The exact production
source compiles against AndroidX creation-compose and passes pixel equality plus twelve physical
clicks at two densities. [Remote source and interaction evidence](evidence/ui-builder-scoped-remote-export/README.md)
records the proof. The fresh live run also passes all six browser/MCP JSON/RC/PNG comparisons,
six browser playback clicks, and saved MCP row authoring. The existing WASM Code pane matches the
MCP Kotlin body after accounting for the service header/package and terminal whitespace. The run
has no browser errors; a viewport relayout accommodates an observed stale accessibility overlay
when switching docks. All 82 shared-export tests, 954 editor tests (one separate opt-in proof
skipped), and WASM/server builds pass. These primitives do not complete all consumer paths.

The next callback source form now has an [opt-in compiled proof](evidence/ui-builder-bound-action-proof/README.md).
Three integer row IDs pass through two reusable component layers whose argument names differ at
each boundary. Ordinary Compose receives `(Int) -> Unit`; creation-compose receives `(Int) -> Action`
factories that retain the caller's mutable state target. Both targets pass initial pixel equality
and eight out-of-order select/reset clicks at each of two densities (32 clicks total).
`-PboundActionProof=true` includes this isolated source/test configuration; omitted or false retains
the previous proof build. The original generator and all production routes remain unchanged.
This proves the next implementation form. Shared typed callback parameters/invocations, production
Remote action factories, authoritative action-binding validation, safe preview resolution and the
existing browser/MCP authoring surfaces still need integration before enabling this feature for users.

The Remote Kotlin part of those callbacks is now in the production emitter. Bound action values
contribute typed function/row parameters, and reusable components receive explicit Action factories
that forward through nested callers. Int, Float, Boolean and String variants compile against
AndroidX; the unchanged row-ID fixture passes every initial pixel and sixteen select/reset clicks
at two densities. Direct row reads, mixed action ordering and invalid scopes/types are covered by
exporter tests. The existing Code pane and revision-pinned server export produce the same source.
[Production callback evidence](evidence/ui-builder-bound-action-production/README.md) records the
exact source, RC documents and frames. `-PboundActionProductionProof=true` selects that source for
the Android proof without replacing the prototype. This is an export capability, not yet an enabled
end-to-end authoring feature; the other integration work listed above remains.

## Remaining production work

- Extend the shared record-driven generator to nullable state, comparisons and parameter-aware
  callbacks, preserving the same meaning in the code pane, server preview and export.
- Extend Remote state selection to String and nullable selectors without changing authored
  semantics. Transitions and inactive-branch retention can follow.
- Enable fully disconnected Remote document compilation; local-storage designs currently use the
  connected host compiler through the same temporary route as hosted and standalone MCP.
- Complete loops, reusable component parameters/callbacks and per-instance addressing through all
  preview and export lanes.
- Extend typed values, expressions, action sequences, host events and modifier bindings using
  shared capability metadata; surface detailed controls only when needed.
- Map catalog-specific components through declared lowering recipes or their real compilation
  lane. Do not substitute lookalikes for unsupported library components.
- Expose the same discoverable operations, located diagnostics, validation and exports over MCP.
- Verify coverage against the pinned operation census, including operands and target limitations;
  rendering one fixture or accepting an opcode is not an 80% implementation claim.

## Local dependency development

[`stage-local-dependency.py`](../../scripts/stage-local-dependency.py) builds selected modules in
their owning checkouts and stages their normal JVM and Wasm publications. An explicit
`-PlocalDependencies=…` manifest selects them for the existing builder and server. Shared generator
publications are staged together, because both `screen-model` and `preview-discovery` carry its
classes. Released builds continue using pinned coordinates by default. Usage is in
[the local build guide](../development/LOCAL_DEPENDENCIES.md).

Verified by compiling local `ui-builder-protocol` JVM/Wasm publications and both shared generator
publications, then running 915 builder JVM tests, 31 shared-export tests and 16 targeted server
behavior/MCP tests against them. Wasm compilation and both resolved-classpath boundary checks
also pass. Gradle dependency reports confirm the selected snapshot versions; omitting the manifest
restores `ui-builder-protocol:2.15.0` and `preview-discovery:2.7.0`.

The first contracts probe used the then-current 2.15.0 floor. Rebasing onto main at `9f1277d5`
brought in contracts 2.16.0 and the declaration/removal mutation integration. Selection verification
now stages only the shared generator pair, retaining the released contracts 2.16.0 dependency;
local overrides must not downgrade contracts to the older probe snapshot.
