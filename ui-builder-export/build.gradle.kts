// `:ui-builder-export` — the projection from a saved design onto the screen the generator consumes.
//
// It exists because two very different callers need the same answer to one question — *what does
// this document mean?* — and until now only one of them could ask it. `:server` projects a saved
// `DesignDocumentV1` onto `ScreenDocument` and runs the real `ScreenGenerator`; the browser editor,
// which is wasm, could not reach that code at all and kept a hand-written emitter of its own. The
// two then disagreed about which designs export, which is the drift this module ends.
//
// **Kotlin Multiplatform** (jvm + wasmJs) for exactly that reason, and it is only possible because
// `ee.schimke.composeai:screen-model` publishes the generator for both targets. `preview-discovery`
// carries the same code for the JVM alone, which is why this could not be done before 1.77.0.
//
// **No Compose dependency.** This is projection and generation, not rendering: `:ui-builder` may
// depend on it without inverting anything, and `:server` may without pulling Compose UI onto a
// server classpath.
//
// **A named seam**, because both `:server` and `:ui-builder` depend on it. It used to publish for
// that reason; an incomplete project-dependency POM is what broke `compose-preview-serve` 3.1.0.
// Since #794 this repository ships distributions rather than Maven coordinates, while the module
// boundary remains useful for keeping the browser and server on one implementation.
plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  id("composeai.maven-publishing")
}



// Same derivation as `:server` and `:ui-builder-runtime` — `PLUGIN_VERSION` in CI, a patch-bumped
// SNAPSHOT off `.release-please-manifest.json` locally. It keeps every archive on the shared
// release
// line; `unspecified` was also the string that broke 3.1.0's former POM.

base { archivesName.set("compose-preview-" + project.name) }

ktfmt { googleStyle() }

kotlin {
  // Pinned to `java-server`, and pinned *explicitly*. This module had no toolchain at all, so its
  // JVM jar once took whatever JVM happened to run Gradle — 17 on CI today, and silently whatever a
  // contributor's `JAVA_HOME` says. It is stated here so the server distribution cannot drift, and
  // so a reader comparing this file with `:ui-builder`'s sees the line between the two floors.
  jvmToolchain(libs.versions.java.server.get().toInt())

  jvm()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain {
      kotlin.srcDir(rootProject.tasks.named("generateMaterialIconExportSource"))
      kotlin.srcDir(rootProject.tasks.named("generateUiBuilderBuildFeatures"))
    }
    commonMain.dependencies {
      // Both coordinates carry no version of their own; these platforms supply them (see the
      // catalog). `project.dependencies.platform(...)`, not a bare `platform(...)`: inside a
      // Kotlin Multiplatform source set the receiver is `KotlinDependencyHandler`, which has no
      // `platform` function at all.
      api(project.dependencies.platform(libs.composeai.tools.bom))
      api(project.dependencies.platform(libs.composeai.contracts.bom))
      api(libs.composeai.screen.model)
      api(libs.composeai.ui.builder.protocol)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

composeAiMavenPublishing {
  coordinates(
    displayName = "Compose UI Builder — Export",
    description =
      "Projection from a saved UI-builder design onto the screen model the Compose generator consumes, shared by the service and the browser editor so the two agree about what a design exports.",
  )
}
