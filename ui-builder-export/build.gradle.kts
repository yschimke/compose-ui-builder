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

// The Remote modifier vocabulary, generated from the released `remote-creation-compose` sources by
// `scripts/remote-vocabulary/generate_remote_modifiers.py`. The released API is authoritative for
// which `RemoteModifier` calls exist; a `remoteCall` modifier is validated and written against
// this.
val embedRemoteModifierVocabulary =
  tasks.register<EmbedComponentRecord>("embedRemoteModifierVocabulary") {
    record.set(rootProject.file("docs/design/fixtures/ui-builder/remote-modifiers-v1.json"))
    constantName.set("EMBEDDED_REMOTE_MODIFIERS_JSON")
    sourceDescription.set(
      "docs/design/fixtures/ui-builder/remote-modifiers-v1.json\n" +
        "// by :ui-builder-export:embedRemoteModifierVocabulary"
    )
    output.set(
      layout.buildDirectory.file(
        "generated/remoteModifierVocabulary/ee/schimke/composeai/uibuilder/EmbeddedRemoteModifiers.kt"
      )
    )
  }

// The document and mutation JSON Schemas come from the pinned protocol jar, generated there from
// the serializers; see `ExtractProtocolSchemas`. The JVM jar alone: the attributes pick the JVM
// variant of the multiplatform root module, and the dependency (not the configuration) is
// non-transitive so its own dependencies stay out. Not the configuration: a non-transitive
// configuration does not follow the platform's edges either, so the BOM's constraints would never
// apply and the versionless coordinate would fail to resolve.
//
// The coordinate carries no version. The contracts BOM supplies it, as it does for every other
// contracts module this build names: the contracts repository publishes only the modules a release
// changes, so `ui-builder-protocol-jvm` at the BOM's own version is a 404 whenever a release skips
// it (3.15.0 is one). The BOM constrains the root module, not `-jvm`, which is why this names the
// root and selects the JVM variant by attribute rather than naming the `-jvm` artifact directly.
val protocolSchemaJar =
  configurations.create("protocolSchemaJar") {
    isCanBeConsumed = false
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
      )
    }
  }

dependencies {
  protocolSchemaJar(platform(libs.composeai.contracts.bom))
  protocolSchemaJar(libs.composeai.ui.builder.protocol) { isTransitive = false }
}

val extractProtocolSchemas =
  tasks.register<ExtractProtocolSchemas>("extractProtocolSchemas") {
    protocolJar.from(protocolSchemaJar)
    schemas.putAll(
      mapOf(
        "design-document-v1.schema.json" to "compose-ui-builder-document-v1.schema.json",
        "design-mutation-v1.schema.json" to "compose-ui-builder-mutation-v1.schema.json",
        "production-uid-v1.schema.json" to "compose-ui-builder-production-v1.schema.json",
      )
    )
    ids.putAll(
      mapOf(
        "compose-ui-builder-document-v1.schema.json" to
          "https://schemas.compose-preview.dev/ui-builder/document/v1",
        "compose-ui-builder-mutation-v1.schema.json" to
          "https://schemas.compose-preview.dev/ui-builder/mutation/v1",
        "compose-ui-builder-production-v1.schema.json" to
          "https://schemas.compose-preview.dev/ui-builder/production/v1",
      )
    )
    output.set(layout.buildDirectory.dir("generated/protocolSchemas"))
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
      resources.srcDir(extractProtocolSchemas.map { it.output })
      kotlin.srcDir(rootProject.tasks.named("generateMaterialIconExportSource"))
      kotlin.srcDir(rootProject.tasks.named("generateUiBuilderBuildFeatures"))
      kotlin.srcDir(
        embedRemoteMaterial3Record.map {
          layout.buildDirectory.dir("generated/remoteMaterial3Record")
        }
      )
      kotlin.srcDir(
        embedRemoteModifierVocabulary.map {
          layout.buildDirectory.dir("generated/remoteModifierVocabulary")
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
    jvmTest.dependencies {
      implementation(libs.json.schema.validator)
      // Compile generated model files with a real handwritten consumer, without requiring a
      // globally installed kotlinc or leaking compiler libraries into the export publication.
      implementation(libs.kotlin.compiler.embeddable)
    }
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
