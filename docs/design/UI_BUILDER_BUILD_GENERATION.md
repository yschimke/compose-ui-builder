# Build generation from project owned designs

**Status: proposed, 2026-10-04.** This document plans a production generation lane for checked-in
`.uid` files. It does not describe an implemented Gradle plugin or extend the current wire schema.

The project owns the design and its declared Kotlin API. A build turns those inputs into stateless
Compose source that can be deleted and regenerated at any time. Application code depends on the
declared API; editing a layout changes its implementation. Generated files are never an editing
surface.

## Requirements

1. Only `.uid` files checked into the project participate. A hosted design, browser draft or
   downloaded catalog listing cannot become a build input implicitly.
2. Generated composables take a simple data class, which may contain nested or shared data classes
   and lists. The application owns business state, view models, loading and navigation.
3. A data contract either maps existing project types or declares types the generator emits. In
   the latter case, the project accepts those generated types as an application API.
4. Reusable components are generated into separate files. A screen calls them rather than
   receiving an expanded copy of their implementation.

The additional choices below are proposed defaults. In particular, explicit event callbacks are
an extension of the data-input requirement, and need agreement before implementing interactive
controls.

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
Kotlin emission. Its current entry point returns one source file; root screen parameters and
separate public component files require shared-model and generator work upstream. Production
generation must not split emitted source with regular expressions or introduce another Compose
emitter in the editor.

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
Compose rendering and editor classes. Canonical production metadata belongs in the versioned
`compose-preview-contracts` protocol; the editor must round-trip it before a project relies on it.
Add typed entry points and multiple-file emission to the generator in `compose-ai-tools`. Preserve
the current single-file export API for existing consumers.

Add a thin JVM generator runner and Gradle plugin in this repository. Their published coordinates
are new consumer surfaces and need a boundary-document update and external-consumer verification.
They do not add a dependency on `compose-preview-server` or require changing its four existing
seams. Keep Gradle APIs out of the projection module.

The generation task declares every document, referenced resource, catalog record, source-set
setting and generation option as an input. The pinned generator classpath is also an input. Task
outputs are a dedicated generated directory wired into compilation through task providers.
Generation is part of every build graph, while unchanged inputs can remain up-to-date or come
from the build cache. Cache keys do not include absolute checkout paths or incidental Git state.

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

- Approve explicit callbacks as part of the stateless composable API. The proposed initial event
  types are zero-argument callbacks and callbacks with one supported data value.
- Choose the versioned production metadata shape with `compose-preview-contracts`. Model and
  component-only `.uid` files need an explicit schema; they must not pretend to be ordinary screens
  with fabricated root nodes.
- Choose the published Gradle plugin identity and generator coordinates, and document those new
  consumer surfaces alongside the existing seams.
- Specify preview samples for external model mappings without requiring the editor to load or
  execute application Kotlin.

These decisions change the format and build API. They should be reviewed before declaring the
first generation contract stable.
