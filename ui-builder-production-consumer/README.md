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

The [`build.gradle.kts`](build.gradle.kts) is the complete JVM build integration. To use it outside
this checkout, keep the verification/generation tasks and source-set wiring, and replace the
project generator dependency with a pinned published version:

```kotlin
val uiBuilderGenerator = configurations.create("uiBuilderGenerator")
dependencies {
  uiBuilderGenerator("ee.schimke.composeai:compose-preview-ui-builder-codegen-jvm:<release>")
}
```

The application separately selects compatible Compose dependencies and the Compose compiler.
The generator and its Kotlin PSI dependencies stay on the build tool's classpath. Compilation
must depend on generation; generation must depend on the always-run Git verification. The two
`JavaExec` tasks deliberately have no cached outputs, so eligibility and generation run even when
Kotlin compilation is up to date. Only registered files and their imports generate code.

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
nested generated and external models, and separate reusable component bodies. Nullable model
fields and lists are supported as declarations, but nullable binding fallbacks, dynamic list
rendering, assets and component placement modifiers/slots currently fail generation.
Explicit event bindings support zero-argument UI callbacks such as `onClick`, reporting either no
payload or one declared data-path payload. Callbacks accepting UI values (such as text changes)
remain unsupported. Required application callbacks never receive no-op defaults.
Editor file sessions preserve the production wrapper around visual edits; API declarations remain
explicit source edits, and model-only files stay source-only. A packaged Gradle plugin remains
follow-up work. See the
[design plan](../docs/design/UI_BUILDER_BUILD_GENERATION.md) for the full intended contract.

The publication and regeneration gate runs a second checkout outside the producer tree:

```sh
./scripts/check-ui-builder-production-consumer.sh
```

It stages local Maven artifacts and lets the first consumer build resolve dependencies. It then
rebuilds offline after deleting generated output, verifies identical source, and proves an
untracked imported file blocks an otherwise unchanged build. CI runs this gate as well as the
regular generator/default-export tests.
