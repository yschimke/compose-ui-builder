# Opt-in durable code generation

Single-file export remains the default. This fixture opts into a separate build lane: registered,
Git-tracked production `.uid` files generate stateless Compose and model files on every build.
Generated Kotlin belongs under `build/` and can be discarded. The application owns the declared API.

```sh
./gradlew :ui-builder-production-consumer:compileKotlin
```

`Library.uid` and `Queue.uid` both import `EpisodeCard.uid`, which imports the model declarations.
The generated `LibraryData` includes an external `ProjectEpisode` and a list of that external type.
The external field `displayTitle` maps to the design field `title`. The shared component body is
emitted once in `example/ui/components/EpisodeCard.kt`; both screens call it. `Consumer.kt` is
handwritten application code, and its `DurableLibraryPreview` renders the generated screen.
The component contains a button whose required `onEpisodeClick` callback reports its mapped
`displayTitle` property. Both screens explicitly forward that event to their application caller.
Interaction tests click the generated button before and after an application data update.

The [`build.gradle.kts`](build.gradle.kts) retains the direct CLI integration as a lower-level
baseline. For application builds, use the [Gradle plugin](../ui-builder-gradle-plugin/README.md):
apply `ee.schimke.compose-ui-builder` and register explicit Kotlin source sets with their entry
paths and ordered records. The [published-plugin fixture](../scripts/ui-builder-production-plugin-consumer/build.gradle.kts)
builds these same application sources with the plugin resolved from staged Maven publications.

The application separately selects compatible Compose dependencies and the Compose compiler.
The generator and its Kotlin PSI dependencies stay on the build tool's classpath. Generation
validates Git eligibility on every invocation, including when Kotlin compilation is up to date.
Only registered files and their imports generate code.

Imports are relative to the consumer project directory. All `.uid` inputs must be tracked there
(staged additions and local edits to tracked files are accepted). Generation accepts a dedicated
output below that project's `build/`, refuses symbolic links, and replaces only a directory bearing
its ownership marker. Hand edits in that generated directory are discarded on the next build.
Unrelated existing directories are protected. A failure stops compilation before stale output is
used. Source archives without a Git index are not supported yet.

Every entry file pins `catalogDigest` to the ordered record contents. To compute a replacement pin,
run the generator main class with `digest <record paths...>` using the same classpath as the Gradle
tasks. Its other commands are:

```text
validate <project root> <entry .uid paths...>
generate <project root> <output> --entries <entry .uid paths...> --records <record paths...>
```

The versioned `compose-ui-builder-production/v1` schema is deliberately separate from the editor's v1 document; old editors
cannot silently drop the declared API. The current lane supports non-null scalar reads through
nested generated and external models, and separate reusable component bodies. Nullable scalar reads require explicit literal fallbacks. `DynamicLibrary.uid` demonstrates
nullable component branches and keyed repetition over typed item models. Assets and component
placement modifiers/slots still fail generation. The nullable/list wire declarations use published
contracts 3.19.0; no composite checkout is required.
Explicit event bindings support zero-argument UI callbacks such as `onClick`, reporting either no
payload or one declared data-path payload. Callbacks accepting UI values (such as text changes)
remain unsupported. Required application callbacks never receive no-op defaults.
Editor file sessions preserve the production wrapper around visual edits; API declarations remain
explicit source edits, and model-only files stay source-only. See the
[design plan](../docs/design/UI_BUILDER_BUILD_GENERATION.md) for the full intended contract.

The publication and regeneration gate runs a second checkout outside the producer tree:

```sh
./scripts/check-ui-builder-production-consumer.sh
```

It stages local Maven artifacts and lets the first consumer build resolve dependencies. It then
rebuilds offline after deleting generated output, verifies identical source, and proves an
untracked imported file blocks an otherwise unchanged build. CI runs this gate as well as the
regular generator/default-export tests.

## Google app item components

`src/main/kotlin/example/google/` contains reusable Compose items and their data classes, derived
from the canonical live Google app designs. Docs uses the repository example. These are ordinary
Kotlin sources: `example.google.model` holds the data classes and `example.google.items` holds
stateless components taking `data: <Model>` and an optional `Modifier`.

| App | Data class | Reusable component |
| --- | --- | --- |
| Gmail | `Email` | `EmailSummary`, `UnreadEmailSummary` |
| Calendar | `CalendarEvent` | `CalendarEventItem` |
| Photos | `Photo` | `PhotoTileLeftToRight`, `PhotoTileTopToBottom`, plus Favorite/Video styles |
| Keep | `Note`, `ChecklistEntry`, `NoteBullet` | Note content styles, checklist items, bullet item |
| Play | `StoreApp` | `StoreAppCard` |
| Docs | `ReviewerComment` | `ReviewerCommentItem` |

`GoogleItemPreviews.kt` demonstrates all six apps, including repeated components with different
data. `KeepChecklistItem` chooses the checked/unchecked text style from `ChecklistEntry.checked`.
These are display components; application navigation, selection and editing callbacks remain the
caller's responsibility. Gmail's avatar/star and keyed Keep/Play card hosts stay at the screen
level. Photo data describes the existing gradient placeholders, not downloaded images.

```kotlin
val email = Email("Alex", "10:30", "Design review", "The latest mockups are ready.")
UnreadEmailSummary(email)
EmailSummary(email.copy(sender = "Sam", subject = "Lunch"))
```

Colors are typed `androidx.compose.ui.graphics.Color` values. The consumer includes Material Icons
for photo badges and app ratings. Tests exercise independent instances, recomposition, checkbox
pixels, color pixels, text and accessible icon descriptions.

The live editor stores component declarations and scalar arguments; data-class wrappers are Kotlin
application APIs. The source of each body is the component with the matching name in the
[live snapshots](../docs/design/live-snapshots/README.md): Gmail r15, Calendar r3, Photos/Keep/Play r2.
`ReviewerCommentItem` comes from `comment-author` and `comment-body` in the repository Docs fixture.
Kotlin parameter reads replace the corresponding scalar bindings; outer layout weight stays with
the caller. These sources preserve the exported Material layout, with no dependency on the builder
at runtime and no changes to the production `.uid` protocol. The existing Library/Queue generation
example remains independent.

The live site remains canonical for design edits. Refresh the Kotlin bodies deliberately after a
live component change and run `:ui-builder-production-consumer:test`; do not import these source
examples over the saved screens. For canvas rendering, `GoogleItemSnapshotRenderingTest` compares
the saved documents against the corrected export projection.
