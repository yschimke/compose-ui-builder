# Durable UID generation for Gradle

Apply `ee.schimke.compose-ui-builder` alongside Kotlin JVM or Kotlin Multiplatform. The plugin
and its marker are published to Maven Central; add Central to plugin resolution in settings:

```kotlin
pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}
```

In the consumer's build script, explicitly register existing Kotlin source sets:

```kotlin
plugins {
  kotlin("jvm") version "<kotlin-version>"
  id("ee.schimke.compose-ui-builder") version "<builder-release>"
}

repositories { mavenCentral(); google() }

uiBuilderGeneration {
  sourceSets {
    create("main") {
      entries.addAll("src/main/ui/Library.uid", "src/main/ui/Queue.uid")
      records.from("catalog/compose-foundation-components-v1.json", "catalog/m3-catalog-components-v1.json")
    }
  }
}
```

Entries and their imports are relative to this Gradle project's directory. They must be in its Git
index; staged additions and local edits are accepted. Record order must match the entry's
`catalogDigest`. The application separately supplies its Compose dependencies and compiler plugin.
Applying the plugin without registrations generates nothing and does not resolve the generator.

For Multiplatform, register `commonMain`, `jvmMain`, or another existing source set explicitly.
Only put portable contracts in `commonMain`. Generated Kotlin is attached to the selected source
set and its compilations automatically depend on generation. A misspelled source set is an error.
The plugin supports Kotlin JVM and Multiplatform; standalone Android Kotlin integration is not
implemented. Source wiring is tested on Gradle 9.7.1 and Kotlin 2.4.20, including JVM and Wasm
compilation of common models. That does not extend the generator's supported Compose components,
assets, modifiers, or callbacks.

Each registration adds `generateUiBuilder<SourceSet>` (for example `generateUiBuilderCommonMain`)
and joins the aggregate `generateUiBuilderSources` task. Generation uses a Java 17 toolchain in a
separate JVM with the `uiBuilderGenerator` configuration. Compiler/PSI and generator dependencies
are absent from application configurations and the plugin's runtime dependencies.

The plugin pins the generator version selected when it was published, including when selective
releases retain an older generator. `uiBuilderGeneration.generatorVersion` can explicitly override
that pin. Repositories for that dependency belong in the consumer build, not inside the plugin.

Generation always runs, including with configuration caching, up-to-date compilation, or build
caching enabled: a live Git-index check cannot be replaced by restored outputs. Every run validates
the complete registered import closure before replacing its owned output. Removing an entry or
renaming a generated declaration deletes obsolete Kotlin files. Deleting a still-registered input
fails compilation. An empty registration fails; remove the registration to disable generation for
that source set.

Outputs are fixed at `<project>/build/generated/uiBuilder/<sourceSet>/kotlin`. The generator's
ownership protections deliberately require the conventional project `build/` directory, so
relocating Gradle's `buildDirectory` does not relocate these sources. Include this directory in
custom clean logic if you relocate your other outputs. Generated directories reject symbolic
links and unrelated existing content. Do not put handwritten files there. Source archives without
a Git index remain unsupported.

The in-repository [Compose consumer](../ui-builder-production-consumer) retains direct CLI tasks
as a lower-level baseline. The [published-plugin fixture](../scripts/ui-builder-production-plugin-consumer/build.gradle.kts)
uses this plugin with the same application sources. Run its independent Maven publication gate:

```sh
./scripts/check-ui-builder-production-consumer.sh
```

The gate resolves the plugin marker, plugin implementation, and default generator from staged
Maven artifacts outside this checkout, rebuilds offline with configuration caching, and checks
Git eligibility and compiler-enforced application contracts.
