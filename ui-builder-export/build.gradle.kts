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

base { archivesName.set("compose-preview-" + project.name) }

// The Remote Material 3 component record, embedded as a constant for the reason
// `:ui-builder:embedComponentRecord` gives: resource loading differs between the JVM and wasmJs. It
// is what writes a `remote-m3/remote-button` in a widget body as a `RemoteButton` call, and what
// the built-in `remote-m3` catalog reads each component's parameters off. See `RemoteMaterial3`.
val embedRemoteMaterial3Record =
  tasks.register<EmbedComponentRecord>("embedRemoteMaterial3Record") {
    record.set(rootProject.file("docs/design/fixtures/ui-builder/remote-m3-record-v1.json"))
    constantName.set("EMBEDDED_REMOTE_M3_RECORD_JSON")
    sourceDescription.set(
      "docs/design/fixtures/ui-builder/remote-m3-record-v1.json\n" +
        "// by :ui-builder-export:embedRemoteMaterial3Record"
    )
    output.set(
      layout.buildDirectory.file(
        "generated/remoteMaterial3Record/ee/schimke/composeai/uibuilder/EmbeddedRemoteMaterial3Record.kt"
      )
    )
  }

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
      kotlin.srcDir(
        embedRemoteMaterial3Record.map {
          layout.buildDirectory.dir("generated/remoteMaterial3Record")
        }
      )
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
      "Projection from a saved UI-builder design onto the screen model the Compose generator " +
        "consumes, shared by the service and the browser editor so the two agree about what a " +
        "design exports.",
  )
}
