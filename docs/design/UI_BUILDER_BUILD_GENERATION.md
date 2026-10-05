# Build generation from project owned designs

**Status: experimental opt-in build lane implemented, 2026-10-05.** Strict project-file contracts,
tracked input resolution, owned model generation, stateless Compose generation and a JVM consumer
build are implemented. Versioned production metadata and explicit editor file round trips are
implemented against the published contracts 3.18.0 release. Single-file export remains
the default. UI-value callback lowering, dynamic lists, nullable binding fallbacks and a packaged Gradle plugin remain proposed.
The experimental file schema does not extend the current design-service wire schema.

The project owns the design and its declared Kotlin API. A build turns those inputs into stateless
Compose source that can be deleted and regenerated at any time. Application code depends on the
declared API; editing a layout changes its implementation. Generated files are never an editing
surface.

## Opt-in mode

Single-file export remains the default in the editor and existing export APIs. Durable generation
is a separately selected build mode: the project explicitly registers production `.uid` files
and accepts their declared application API. Merely saving or checking in a normal design does not
enable this mode. Separate component and model files are a rule of the durable mode only.

## Requirements

1. Only `.uid` files checked into the project participate. A hosted design, browser draft or
   downloaded catalog listing cannot become a build input implicitly.
2. Generated composables take a simple data class, which may contain nested or shared data classes
   and lists. The application owns business state, view models, loading and navigation.
3. A data contract either maps existing project types or declares types the generator emits. In
   the latter case, the project accepts those generated types as an application API.
4. Reusable components are generated into separate files. A screen calls them rather than
   receiving an expanded copy of their implementation.

Explicit callbacks are part of the experimental opt-in API. Zero-argument UI callbacks can report
intent and declared data payloads; callbacks receiving UI values remain proposed.

## Current foundation and missing contracts

[`UidDesignFiles`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UidDesignFiles.kt)
reads and writes the existing `DesignDocumentV1` format. It accepts both the v1 and candidate
schema names and deliberately ignores unknown fields. Build generation will need a stricter
version check for production metadata: an older generator must refuse declarations it cannot
interpret, rather than discard them and produce a different API.

The existing
[`document model`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/UiBuilderDocument.kt)
has component definitions, placements and bindings. Component parameter lists are intentionally
inferred from body reads today. That remains useful for mockups; it cannot define a permanent
application API. Removing the last use of a parameter must not remove that parameter from a
production signature.

[`ScreenDocumentProjection`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/ScreenDocumentProjection.kt)
projects designs onto the shared screen model, including scoped bindings and supporting functions
where the pinned generator supports them.
[`ScreenExportGate`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/ScreenExportGate.kt)
then invokes `ScreenGenerator` from the pinned `compose-ai-tools` publication. The generator owns
Kotlin emission. Its current entry point returns one source file. The JVM build runner uses its typed supporting
functions and Kotlin PSI to select a generated body structurally, then emits the declared data
wrapper around it. Component bodies live in separate files and are called through records derived
from their generated signatures. Production generation never splits emitted source with regular
expressions or introduces another Compose body emitter in the editor.

The existing export lane can also emit asset placeholders. The
[`Jetcaster fixture`](../../ui-builder-generated-jetcaster/README.md) demonstrates generation and
compilation but retains located TODOs for incomplete behavior. Production generation needs an
explicit validation profile that rejects such outcomes.

## Ownership and source files

Register entry-point `.uid` files explicitly in the consumer build. Resolve the complete dependency
closure of component and model declarations beneath the registered project input roots. Every
referenced `.uid` must also be checked in. An unused committed design is not generated merely
because a directory scan finds it.

Normal local builds may use edits to already tracked inputs. A verification task checks that the
registered files and their dependencies are tracked, including newly added files; CI generates
from its clean checkout. The generator itself consumes files rather than Git state so it can run
from a source archive. Archive builds must carry the same explicit input manifest produced from
the checkout. No generation task downloads a design or asks a running editor for content.

Recommended layout, with names shown as an example rather than a fixed convention:

```text
src/commonMain/ui/
  Library.uid
  Queue.uid
  components/EpisodeCard.uid
  models/LibraryModels.uid

build/generated/uiBuilder/commonMain/kotlin/example/ui/
  LibraryScreen.kt
  QueueScreen.kt
  components/EpisodeCard.kt
  models/LibraryUiData.kt
  models/EpisodeUiData.kt
```

The source set is explicit. Designs for platform-specific APIs belong to that platform's source
set; they must not be emitted into `commonMain`. Catalog records and assets are project inputs or
versioned dependencies, with their identities and content pinned. A build must reproduce output
without the original authoring host.

## Declared entry point API

The production contract declares the package, function name, data type, visibility and events.
Neither a document title nor node order determines those names. The default screen visibility is
public; a reusable component defaults to internal unless the project explicitly publishes it.

Proposed shape of generated Kotlin:

```kotlin
@Composable
fun LibraryScreen(
    data: LibraryUiData,
    onEpisodeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
internal fun EpisodeCard(
    data: EpisodeUiData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

There is one input model for each declared entry point. Callbacks and `Modifier` are separate
parameters. Required events have no no-op default. A click reports intent to the application; a
text edit reports the proposed value. The application updates the data and passes it back in.
Routing and state mutation are never generated from editor preview actions.

Stateless here means no owned application state. Foundation components may retain ordinary
interaction, focus and scroll state needed to operate. Generated code must not create view models,
remember business values, launch application effects or fetch data. Preview samples and selected
values are distinct: samples let the editor draw the design, while production selection comes from
the data parameter.

## Data contracts

### Existing project types

Declare an external type by its fully qualified name and map its readable properties. For example,
the design field `episode.title` may map to `data.episode.displayTitle`. The contract records the
expected type and nullability so the editor and generator can check binding uses.

The first implementation can validate the declared shape and let the consumer Kotlin compilation
verify the real external properties. This avoids a source-discovery task depending on compilation
of the generated code it is about to produce. Compiler integration or property discovery can later
improve diagnostics without changing ownership. A mapped property that disappears must fail the
consumer build; no reflection, unchecked casts or runtime dictionaries substitute for validation.

External types are not emitted. The application owns adapters when domain models require
formatting, filtering or conversion beyond direct property reads. Mixed graphs are permitted:
a generated input class can refer to an explicitly declared external data class.

### Generated project types

Declare every generated class and field, including its name, type and nullability. Generate one
file per named class. Support an initial closed set: `String`, `Boolean`, `Int`, `Long`, `Float`,
`Double`, references to declared data classes, nullable forms and `List` of supported types.
Reject recursive model graphs initially. Enum and richer presentation types are follow-up work.

Generated types have immutable `val` properties. Collection inputs are read-only by contract; the
caller must not mutate a list behind Compose's observation. Do not promise stability with generated
`@Immutable` or `@Stable` annotations until it is proved for the complete type graph, including
external types.

Each shared class has one owning declaration and a stable identity. Two screens referencing
`EpisodeUiData` generate it once. Duplicate ownership, duplicate Kotlin names, or inconsistent
declarations fail validation. Changes to constructors, field types, packages or names are explicit
API edits. Unused declared fields remain in the class.

Preview samples never supply production constructor defaults. Defaults, if supported later, must
be explicit API decisions. Sample data belongs to a separate preview artifact and does not enter
production lists or become a fallback for a missing binding.

## Bindings and component reuse

Start with typed property reads, list iteration, explicit null handling and boolean conditions.
The binding model must preserve lexical scope: `data` refers to the current composable's model,
and a loop item refers to that loop's declared element type. Nested paths are validated one segment
at a time. Arbitrary Kotlin strings, method calls and computed business expressions are outside
this lane.

Nullable data requires an explicit presentation choice, such as an alternative subtree or a
declared literal fallback. A missing binding is an error, not an empty string or a default color.
Loops over a lazy container generate `items` with an explicitly declared stable item key; a loop
inside a single lazy `item` is not an equivalent implementation. Preview row indices must never
become production keys.

Each reusable component has a stable identity, owning `.uid`, explicit signature and one generated
file. References identify that declaration rather than duplicating its body into a screen. A
component's inputs and events are passed explicitly, so it cannot capture a screen's model or a
callback that its API did not declare. Generate declared components even when their owning screen
does not currently place them, if they are explicitly registered as reusable entry points.

In-document reusable components also receive separate output files. Cross-file references resolve
within the registered input closure. Reject reference cycles and identifier collisions before
emission. Moving a visual node between files does not rename a declaration. Detect duplicate
fully qualified Kotlin symbols across components and generated models as well as within a file.

The editor's existing imported component snapshots remain appropriate for authoring and drift
review. Production references resolve the checked-in authoritative declaration. A stale embedded
snapshot must not silently override that declaration; report drift and require an explicit update
of the design inputs.

## Generation and build integration

Use a pipeline with separately testable contracts:

```text
registered project inputs and pinned records
  -> resolve component and model declarations
  -> validate production API, graph, bindings and resources
  -> project onto the shared typed screen model
  -> generate a collection of named Kotlin files
  -> compile the owning source set
```

Implement the production projection and validation in the builder's export layer, independent of
Compose rendering and editor classes. Canonical production metadata lives in the versioned
`compose-preview-contracts` protocol. Editor file sessions retain its wrapper separately from the
ordinary service document and preserve it when saving visual edits.
A future typed entry-point and multi-file API in `compose-ai-tools` can replace the JVM PSI
adapter. The current implementation uses the pinned released generator and preserves the existing
single-file export API and JVM signature for consumers.

Add a thin JVM generator runner and Gradle plugin in this repository. Their published coordinates
are new consumer surfaces and need a boundary-document update and external-consumer verification.
They do not add a dependency on `compose-preview-server` or require changing its four existing
seams. Keep Gradle APIs out of the projection module.

The initial consumer runs verification and generation on every compilation build graph. Neither
task reuses a cached Git eligibility result or generated output. Outputs occupy a dedicated build
directory. A future cacheable plugin must declare every document, catalog record, source-set option
and the generator classpath as inputs, and retain an always-run Git eligibility check. Cache keys
must exclude absolute checkout paths.

Generate and validate the full file set before replacing output. If validation fails, compilation
must not use leftover successful output. On success, remove files for deleted or renamed inputs,
and write only within the task's owned directory. A separate contract report records generated
signatures for review; CI can compare it with a checked-in API baseline without requiring Kotlin
output to be committed.

## Production validation and reproducibility

Production validation is an opt-in lane; a legitimate mockup can remain editable even when it
cannot meet this contract. Its generation task refuses unsupported nodes, unknown production
schema versions, unresolved assets, unmapped events, business-state actions and unexpressible
bindings. Diagnostics name the `.uid` file, node or declaration, field and rule.

Catalog pinning must identify the actual input artifact and digest, not a placeholder revision.
Assets resolve to project resources, pinned embedded content or explicit caller inputs. No
placeholder painter, silent no-op event, TODO or network lookup is allowed in a successful
production result. Resource lowering must respect the selected platform and source set.

Identical semantic inputs and pinned generator versions produce identical file names and bytes.
Output excludes timestamps, absolute paths and editor revision counters from executable source.
Sort unordered declarations deterministically while preserving authored child order. Stable
names come from explicit declarations, never encounter order. Determinism within a pinned version
does not promise byte-identical output after upgrading the generator; upgrades must preserve the
declared API unless an explicit migration changes it.

## Delivery sequence and acceptance

| Step | Deliverable | Acceptance |
| --- | --- | --- |
| 1 | Versioned production contract and editor round-trip support | Names, model ownership, typed paths, events and references survive save and reload; unsupported versions refuse generation |
| 2 | Input resolution and strict validation | Duplicate symbols, cycles, missing fields, wrong types, null handling and unresolved assets fail with located diagnostics |
| 3 | Shared generator extensions | Public typed entry points and component files are emitted structurally; existing single-file exports still pass |
| 4 | Builder projection and generator runner | Both model ownership modes lower through the same generator; production output has no placeholders or TODOs |
| 5 | Gradle integration and external consumer fixture | A clean JVM Compose consumer compiles generated sources automatically from registered project files |
| 6 | Reproducibility and compatibility gates | Delete and regenerate output, reorder input declarations, change layout, remove a design and build offline; output and API assertions hold |

The first complete consumer fixture contains two screens sharing one generated component and one
nested generated data class, a second screen input mapped to project-owned data classes, a dynamic
list with stable keys, and explicit click callbacks. Handwritten caller code constructs the models
and calls the screens, so the test proves application integration rather than only matching source
strings. Compile generated Kotlin against real Compose dependencies.

Test regeneration from two different checkout paths and an unchanged second build. Change a
literal layout value and verify that the public contract is unchanged. Rename an external model
property and verify compilation fails. Remove a registered design and verify its old output is
gone. A malformed or unsupported design must fail the build even when a previous successful
generation exists. Add broader target fixtures when the declared support expands; the initial JVM
fixture does not claim Android or Wasm compatibility.

## Decisions to settle before implementation

- Extend callback lowering to UI-provided values, such as text edits, with explicit typed payload
  scopes. The current lane reports payloads read from the input model.
- Model-only files remain source documents without fabricated visual roots; a declaration editor
  and imported component previews remain future authoring work.
- Choose the published Gradle plugin identity and generator coordinates, and document those new
  consumer surfaces alongside the existing seams.
- Specify preview samples for external model mappings without requiring the editor to load or
  execute application Kotlin.

These decisions change the format and build API. They should be reviewed before declaring the
first generation contract stable.

## Implemented contract foundation

The versioned schema is `compose-ui-builder-production/v1`, owned by
`compose-preview-contracts`' `production.ProductionUidFileV1`. It contains explicit model,
entry-point, binding, component and event declarations plus an optional embedded `DesignDocumentV1`.
The shared module publishes its generated JSON Schema and uses builders for binary-compatible
optional-field additions. No filesystem, generation or editor behavior moves into that repository.

[`ProductionUidFile`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/production/ProductionUidFile.kt)
remains a build-tool compatibility adapter so existing generator callers keep their Kotlin types.
`ProductionUidFiles` checks the shared wire contract before adapting it. The old
`compose-ui-builder-production/v1-candidate` remains readable without implicit migration; v1 also
strictly checks the embedded protocol document. Unknown versions, fields and type variants refuse.
A model-only file declares `models` without an entry point or fabricated design. Both wrappers
remain distinct from ordinary editor documents, so a design-only reader cannot drop their API.

Each file declares project-root-relative `imports`. Model types use stable model ids; generated
classes and entry points have explicit qualified Kotlin names. Field declaration order fixes the
generated constructor order. `ProductionType` distinguishes scalar, model and list types, with
nullability explicit on each. External fields optionally map a design-facing name to one actual
Kotlin property; otherwise the same name is used. Property paths are lists of field names, not
Kotlin expression strings. Cross-file component uses declare their data path and forwarded events.

[`ProductionContractValidator`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/production/ProductionContractValidation.kt)
checks symbol and model ownership, imports, declaration cycles, typed data paths, nullability,
component inputs and event signatures. It also rejects owned application state and preview event
actions in embedded designs. Validation reports the input file, declaration, node and field where
applicable. A valid contract is **not** an assertion of catalog exportability: checking catalog
parameter types, resources, full layout graphs and executable event bindings belongs to the later
production projection and generator gate. Nullable leaf values can be declared, but this first
contract version cannot traverse nullable objects; explicit branch and fallback shapes are still
to be added. Loop item scopes and stable-key declarations are also follow-up work.

The JVM
[`ProductionProjectFiles`](../../ui-builder-export/src/jvmMain/kotlin/ee/schimke/composeai/uibuilder/export/production/ProductionProjectFiles.kt)
loader follows only registered files and their imports. It requires each input to be in the Git
index, allows local edits and staged additions, and refuses URLs, traversal and symbolic-link
inputs. It does not scan unrelated files or resolve remote designs. Source-archive manifests are
not supported yet.

[`ProductionModelGenerator`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/production/ProductionModelGenerator.kt)
returns one named Kotlin file per generated data class. It emits no copies of external models,
constructor defaults, Compose stability annotations, timestamps or absolute paths. Its pure
generation API expects a validated contract; build callers should obtain that contract through the
tracked project loader. The caller currently owns writing these files and wiring them into a
source set. This API is model generation only; it does not emit composable bodies.

Example of the current JVM API, with exhaustive failure handling:

```kotlin
when (val result = ProductionProjectFiles.load(projectRoot, listOf("Library.uid"))) {
  is ProductionContractResult.Invalid -> error(result.issues.joinToString("\n"))
  is ProductionContractResult.Valid -> {
    val files = ProductionModelGenerator.generate(result.contract)
    // Each file has a project-independent relative path and Kotlin source.
  }
}
```

The checked-in
[`model fixture`](../../ui-builder-export/src/jvmTest/resources/production/library-models.uid)
declares nested and shared generated models plus a mapped project-owned external model. Tests
resolve a tracked three-file project, generate its models, compile them with a handwritten Kotlin
consumer and execute that consumer. Other tests cover strict decoding, located contract failures,
regeneration independent of input ordering, and preservation of model APIs after removing visual
bindings. These checks establish the contract foundation. The JVM lane below also compiles real generated
Compose source from those kinds of declarations.


## Implemented opt-in JVM lane

[`ProductionComposeGenerator`](../../ui-builder-codegen-jvm/src/main/kotlin/ee/schimke/composeai/uibuilder/codegen/ProductionComposeGenerator.kt)
lives in the new JVM-only build tool, separate from the Compose-free JVM/Wasm projection seam.
It accepts a validated contract and pinned component records. Supported bindings read non-null
scalars from nested generated or mapped external data classes. Models can contain nullable fields
and lists, including unused fields: their complete declared constructor remains stable when a
layout removes a binding. Rendering nullable reads or iterating lists is not supported yet.

Each entry emits one named Kotlin file containing an internal generated body and the explicitly
declared public or internal composable with `data` and `modifier` parameters. Components emit their
body once in their own file; screen bodies call that implementation. The generated body takes typed
scalar parameters, while the wrapper reads the declared data paths, including external property
names. The shared generator proves those scalar types against the actual catalog parameters.
Kotlin PSI verifies and selects the supporting declaration; no regular-expression source splitting
is used. Implicit supporting functions, application state, generated
`remember` calls, assets, incomplete bindings and unreachable design nodes refuse output. Component
placements currently accept only their declared data mapping; placement modifiers and slots refuse.

Each screen/component file must declare `catalogDigest`, the SHA-256 of the ordered build record
contents. Hashing prefixes each byte array with its 8-byte big-endian length and excludes paths.
The CLI's `digest` command computes the value. A changed record must be explicitly accepted in the
checked-in design; placeholder catalog revisions cannot bypass the digest check.

[`ProductionGenerationCli`](../../ui-builder-codegen-jvm/src/main/kotlin/ee/schimke/composeai/uibuilder/codegen/ProductionGenerationCli.kt)
has `validate`, `digest` and `generate` modes. Generation loads only tracked registered/imported
files, validates and generates the full set before writing, then replaces its owned output under
project `build/`. A marker prevents replacing an unrelated existing directory. Successful rebuilds
remove stale files. A failed generation task blocks compilation even if earlier output exists.
The CLI does not touch editor export settings or scan normal `.uid` files to opt them in.

The executable integration is
[`ui-builder-production-consumer`](../../ui-builder-production-consumer/README.md). Its Gradle
build explicitly registers two screens sharing a separately generated component and a generated
model with a nested external mapping and list. Handwritten callers compile against real Compose.
The build runs without an editor/server and uses offline pinned records. Tests verify deterministic
output, stable model APIs, catalog drift and unsupported input refusals, stale output removal and
protection of handwritten directories. The CI gate also stages the published artifacts into a
throwaway Maven repository, compiles from a different checkout path, deletes and regenerates output
offline, and proves untracking an unchanged import blocks compilation. A packaged Gradle plugin and source-archive manifests are
follow-up distribution work; the fixture documents the reusable `JavaExec` build wiring today.

## Explicit production events

An entry's ordered `events` declares required callback parameters independently of layout usage.
`eventBindings` explicitly connects a node's catalog Kotlin callback parameter to one declared
application event:

```json
{
  "events": [{"name": "onEpisodeClick", "payload": {"kind": "scalar", "scalar": "string"}}],
  "eventBindings": [{
    "nodeId": "playButton",
    "property": "onClick",
    "event": "onEpisodeClick",
    "payloadPath": ["episode", "title"]
  }]
}
```

The UI callback must take zero arguments and return `Unit`; value callbacks and composable slots
are refused against the pinned catalog. Omit `payloadPath` only for an event without a payload.
An empty path reports the entire input model. Paths obey the model contract and external property
mapping; nullable intermediate objects still refuse traversal. A nullable leaf, model or list may
be reported as data without generating a branch or list rendering.

Each component use forwards every child event through its explicit `events` map, with identical
payload types. Bindings on component placements, unknown events, conflicting bindings and payload
type mismatches fail with located diagnostics. Preview state actions remain forbidden.

The JVM adapter lowers callbacks to typed helper parameters using the shared generator. The data
wrapper captures each declared payload read and invokes the required application callback when
clicked. It generates no application state, default callback or preview action. Unused event
declarations remain in the API. The production consumer's Compose interaction tests exercise both
screen forwarding and the component API, including updated input data after recomposition.

## Editor file round trips

`UidDesignFiles.open` returns the editable layout together with its original production wrapper.
MCP App and browser-host sessions, native desktop saves (including Save As), and the IntelliJ
project writer preserve that wrapper when replacing its design. Entry names, model ownership and
field order, imports, catalog digests, data bindings, component forwarding and events stay authored
source metadata; a visual edit does not infer or rewrite them. IntelliJ selects the production
JSON Schema for production source files.

Edits removing a declared root or binding target, or adding preview-owned state/actions, refuse
the save and report the error. External source edits retain existing conflict/reload protection,
including metadata-only changes on hosts without etags. Model-only contracts remain source-only.
There is no implicit conversion of a mockup into a production API and no metadata inspector yet;
API declarations are edited explicitly in source. Imported component resolution and model sample
rendering are separate authoring work; opening a file does not fetch imports or execute Kotlin.

Development of this change uses the existing contracts composite override:

```sh
./gradlew -PlocalBuilds=contracts -PlocalBuild.contracts=/path/to/compose-preview-contracts \
  :ui-builder-export:jvmTest :ui-builder-codegen-jvm:test
```

Use a JDK 17 Gradle runtime for the composite's unpinned modules; the editor selects its own JDK 21
toolchain. Normal builds use published contracts 3.18.0 and require no composite override.
The publication/consumer gate verifies generation from staged builder artifacts and published
contracts dependencies in an independent checkout.
