// `:ui-builder-export-fixtures` — the sample design documents tests and `@Preview`s build from.
//
// Every new design is seeded from the templates its catalog publishes (`UiBuilderNewDesignSeed`),
// so
// the Kotlin document builders that used to seed them are not `:ui-builder-export` API any more.
// They stay as fixtures: `:ui-builder-export` and `:ui-builder` tests depend on this module, and so
// do `:ui-builder`'s JVM previews, which is why it is a module and not a test source set. Never
// published; `:ui-builder`'s previews reach the render bundle through its own runtime classpath.
//
// The sources are under `src/fixtures/`, not `src/commonMain/`: they are not the builder's main
// sources, so `.github/scripts/catalog-id-ratchet.sh` does not count the catalog ids they spell.
plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
}

ktfmt { googleStyle() }

kotlin {
  // `java-server`, like `:ui-builder-export`: that module's tests consume this one, so it cannot
  // target a newer JVM than they do.
  jvmToolchain(libs.versions.java.server.get().toInt())

  jvm()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain {
      kotlin.setSrcDirs(listOf("src/fixtures/kotlin"))
      resources.setSrcDirs(emptyList<String>())
    }
    commonMain.dependencies {
      api(project(":ui-builder-export"))
      implementation(libs.kotlinx.serialization.json)
    }
  }
}
