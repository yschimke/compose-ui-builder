// `:ui-builder-export` — the projection from a saved design onto the screen the generator
// consumes, shared by `:server` and the browser editor so both agree on which designs export.
//
// Kotlin Multiplatform (jvm + wasmJs), possible because `ee.schimke.composeai:screen-model`
// publishes the generator for both. No Compose dependency: this is projection and generation, not
// rendering.
plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  id("composeai.maven-publishing")
}

base { archivesName.set("compose-preview-" + project.name) }

// The Remote Material 3 component record, embedded as a constant (resource loading differs between
// JVM and wasmJs). See `RemoteMaterial3`.
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
// `scripts/remote-vocabulary/generate_remote_modifiers.py`; `remoteCall` modifiers are validated
// against it.
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

// The design-guideline rules, from the one reviewed file; `DesignGuidelineRuleSet.Bundled` reads it
// in the editor and in compose-preview-server.
val embedDesignGuidelines =
  tasks.register<EmbedComponentRecord>("embedDesignGuidelines") {
    record.set(rootProject.file("docs/guidelines/android-design-guidelines.json"))
    constantName.set("EMBEDDED_ANDROID_DESIGN_GUIDELINES_JSON")
    sourceDescription.set(
      "docs/guidelines/android-design-guidelines.json\n" +
        "// by :ui-builder-export:embedDesignGuidelines"
    )
    output.set(
      layout.buildDirectory.file(
        "generated/designGuidelines/ee/schimke/composeai/uibuilder/EmbeddedDesignGuidelines.kt"
      )
    )
  }

// The document and mutation JSON Schemas from the pinned protocol jar; see
// `ExtractProtocolSchemas`. The dependency (not the configuration) is non-transitive so the BOM's
// constraints still apply. Versionless: the contracts BOM supplies the root module's version, and
// the JVM variant is selected by attribute (a `-jvm` coordinate at the BOM version may not exist).
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

// The flexpress version from the version catalog, so an export's dependency note matches the
// generator.
val generateFlexpressVersion =
  tasks.register("generateFlexpressVersion") {
    val version = libs.versions.flexpress
    val output = layout.buildDirectory.dir("generated/flexpressVersion")
    inputs.property("flexpress", version)
    outputs.dir(output)
    doLast {
      output
        .get()
        .asFile
        .resolve("FlexpressVersion.kt")
        .apply { parentFile.mkdirs() }
        .writeText(
          "package ee.schimke.composeai.uibuilder.export\n\n" +
            "/** The flexpress release variable font text is generated for. */\n" +
            "const val FLEXPRESS_VERSION: String = \"${version.get()}\"\n"
        )
    }
  }

// The variable fonts `FlexpressVariableFontSources` generates from, staged into this module's JVM
// resources because the canvas's copies are not readable where an export runs.
// `FlexpressVariableFontSourcesTest` fails if one is missing.
val stageFlexpressFonts =
  tasks.register<Sync>("stageFlexpressFonts") {
    from(rootProject.layout.projectDirectory.dir("assets/rc-fonts")) {
      include(
        "RobotoFlex.ttf",
        "RobotoFlex-OFL.txt",
        "google-sans-flex-variable.ttf",
        "GoogleSansFlex-OFL.txt",
      )
      into("ee/schimke/composeai/uibuilder/export/fonts")
    }
    into(layout.buildDirectory.dir("generated/flexpressFonts"))
  }

ktfmt { googleStyle() }

kotlin {
  // Pinned explicitly to `java-server` so the server distribution's floor cannot drift with
  // `JAVA_HOME`.
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
        embedDesignGuidelines.map { layout.buildDirectory.dir("generated/designGuidelines") }
      )
      kotlin.srcDir(
        embedRemoteModifierVocabulary.map {
          layout.buildDirectory.dir("generated/remoteModifierVocabulary")
        }
      )
      kotlin.srcDir(generateFlexpressVersion)
    }
    commonMain.dependencies {
      // These platforms supply the versions. `project.dependencies.platform(...)` because a KMP
      // source set's `KotlinDependencyHandler` has no `platform` function.
      api(project.dependencies.platform(libs.composeai.tools.bom))
      api(project.dependencies.platform(libs.composeai.contracts.bom))
      api(libs.composeai.screen.model)
      api(libs.composeai.ui.builder.protocol)
      implementation(libs.kotlinx.serialization.json)
    }
    // flexpress's code generator, for `FlexpressVariableFontSources`. Plain Kotlin on the JVM at
    // Java 17, like this side; the wasm editor has no generator and says so.
    jvmMain { resources.srcDir(stageFlexpressFonts) }
    jvmMain.dependencies {
      implementation(libs.flexpress.core)
      implementation(libs.flexpress.codegen)
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

// Compose Multiplatform Desktop as a compile classpath for `DesktopTypefacesCompileTest` only (real
// `SystemFont` signature and opt-in). Its own configuration so other tests' stand-ins are not
// shadowed and the module keeps no Compose dependency.
val desktopComposeClasspath =
  configurations.create("desktopComposeClasspath") {
    isCanBeConsumed = false
    attributes {
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
        org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
      )
    }
  }

// The Compose compiler plugin, for `FlexpressVariableFontSourcesTest` (generated code calls
// `remember`). Non-transitive.
val composeCompilerPlugin =
  configurations.create("composeCompilerPlugin") {
    isCanBeConsumed = false
    isTransitive = false
  }

dependencies {
  desktopComposeClasspath(libs.compose.material3)
  composeCompilerPlugin(libs.kotlin.compose.compiler.plugin.embeddable)
}

tasks.named<Test>("jvmTest") {
  // `RemoteJsonImporterTest` imports a RemoteCompose-Examples checkout when given one.
  providers.gradleProperty("remoteJsonCorpus").orNull?.let {
    val corpus = rootProject.file(it)
    inputs.dir(corpus).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("remoteJsonCorpus", corpus.absolutePath)
  }
  val classpath = desktopComposeClasspath.incoming.files
  val plugin = composeCompilerPlugin.incoming.files
  inputs
    .files(classpath)
    .withPropertyName("desktopComposeClasspath")
    .withNormalizer(ClasspathNormalizer::class)
  inputs
    .files(plugin)
    .withPropertyName("composeCompilerPlugin")
    .withNormalizer(ClasspathNormalizer::class)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf(
        "-DdesktopCompose.classpath=${classpath.asPath}",
        "-DcomposeCompiler.plugin=${plugin.asPath}",
      )
    }
  )
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
