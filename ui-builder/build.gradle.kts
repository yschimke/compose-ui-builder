plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.preview)
}

ktfmt { googleStyle() }

abstract class VerifyGeneratedSource : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val checkedIn: org.gradle.api.file.RegularFileProperty

  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val expected: org.gradle.api.file.RegularFileProperty

  @org.gradle.api.tasks.TaskAction
  fun verify() {
    check(checkedIn.get().asFile.readBytes().contentEquals(expected.get().asFile.readBytes())) {
      "Generated Jetcaster Compose is stale. Run ./gradlew :ui-builder:generateJetcasterComposeFixture"
    }
  }
}

/**
 * Embeds the component record the Compose export reads, so the editor judges a design against the
 * same artefact the server exports against.
 *
 * Generated from `m3-catalog-components-v1.json` rather than copied, because a second copy of the
 * record is the drift this module is removing. It lands as a Kotlin constant rather than a resource
 * because resource loading differs between the JVM and wasmJs, and the panel must behave the same
 * in both — the browser is where it actually runs.
 */
abstract class EmbedComponentRecord : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val record: org.gradle.api.file.RegularFileProperty

  @get:org.gradle.api.tasks.OutputFile abstract val output: org.gradle.api.file.RegularFileProperty

  @org.gradle.api.tasks.TaskAction
  fun generate() {
    val json = record.get().asFile.readText()
    val file = output.get().asFile
    file.parentFile.mkdirs()
    file.writeText(
      buildString {
        appendLine("package ee.schimke.composeai.uibuilder")
        appendLine()
        appendLine(
          "// Generated from docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"
        )
        appendLine("// by :ui-builder:embedComponentRecord. Do not edit.")
        appendLine()
        // A raw string, with every `$` escaped: `typeFqn` values carry them (a nested classifier
        // is `Arrangement${'$'}Vertical` in a JVM name), and Kotlin would read them as template
        // interpolation. `val` rather than `const val` for the same reason — an escaped raw string
        // is not a compile-time constant.
        appendLine("internal val EMBEDDED_COMPONENT_RECORD_JSON: String =")
        val escaped = json.trimEnd().replace("$", "\${'\$'}")
        appendLine("  \"\"\"" + escaped + "\"\"\"")
      }
    )
  }
}

val embedComponentRecord =
  tasks.register<EmbedComponentRecord>("embedComponentRecord") {
    record.set(rootProject.file("docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"))
    output.set(
      layout.buildDirectory.file(
        "generated/componentRecord/ee/schimke/composeai/uibuilder/EmbeddedComponentRecord.kt"
      )
    )
  }

val ktfmtCli = configurations.create("ktfmtCli")

dependencies { ktfmtCli(variantOf(libs.ktfmt.cli) { classifier("with-dependencies") }) }

// The frontend JVM lane's floor, read from the catalog rather than written twice. Every task in
// this file that *runs* this module's classes has to launch on it, because `jvmToolchain` sets the
// compile and `Test` toolchains but leaves `JavaExec` on the Gradle JVM.
val uiBuilderJava = JavaLanguageVersion.of(libs.versions.java.ui.builder.get().toInt())

val uiBuilderLauncher = javaToolchains.launcherFor { languageVersion.set(uiBuilderJava) }

kotlin {
  // `java-ui-builder`, above the rest of this build. This module's JVM classes are published to
  // nobody: they leave the build only inside `:ui-builder-render-bundle`'s polyglot PNG, which
  // `:ui-builder-runtime` reaches as a coordinate and never as classes on anyone's classpath. The
  // catalog entry for the two floors has the full argument, including the runtime cost.
  jvmToolchain(uiBuilderJava.asInt())

  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    outputModuleName.set("uiBuilder")
    binaries.executable()
  }

  sourceSets {
    commonMain {
      kotlin.srcDir(
        embedComponentRecord.map { layout.buildDirectory.dir("generated/componentRecord") }
      )
    }
    commonMain.dependencies {
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.materialIconsExtended)
      @Suppress("DEPRECATION") implementation(compose.ui)
      implementation(libs.composeai.ui.builder.protocol)
      // The real `ScreenGenerator`, compiled for wasmJs as well as the JVM. Before this the editor
      // had no way to ask the question the server's export answers, so it kept its own emitter.
      implementation(libs.composeai.screen.model)
      // `api`, not `implementation`: `UiBuilderDocument` lives in this module and appears in
      // `:ui-builder`'s own public signatures — `UiBuilderSurface(document: UiBuilderDocument)` is
      // the whole point of the module — so a consumer that calls them has to be able to see it.
      // `:ui-builder-renderer` is that consumer, and with the dependency hidden its wasmJs compile
      // fails on "Cannot access class UiBuilderDocument. Check your module classpath".
      api(project(":ui-builder-export"))
      implementation(libs.composeai.rc.player.compose)
      implementation(libs.kotlinx.serialization.json)
      implementation(project(":ui-builder-artwork"))
      // Kotlin syntax highlighting for the Code pane. See `UiBuilderCodeHighlighting.kt` for why a
      // Compose-native tokenizer rather than the playground's CodeMirror.
      implementation(libs.snipme.highlights)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    getByName("jvmMain").dependencies {
      // Feasibility spike only: the saved-document bridge executes ComposeScene against SVGCanvas.
      // It remains NO-GO for production/Figma until a representative nested scene succeeds and the
      // adapter moves behind :render-host. currentOs supplies the matching local Skiko runtime.
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.compose.ui.tooling.preview)
      implementation(libs.composeai.data.preview.overrides.runtime)
    }
    getByName("jvmMain")
      .resources
      .srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
    getByName("jvmTest") {
      resources.srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
    }
  }
}

val collaborationSoakMinutes = providers.gradleProperty("uiBuilderCollaborationSoakMinutes")

tasks.named<Test>("jvmTest") {
  collaborationSoakMinutes.orNull?.let { systemProperty("uiBuilderCollaborationSoakMinutes", it) }
}

tasks.register<JavaExec>("generateJetcasterComposeFixture") {
  description = "Generate the standalone Jetcaster Compose source from the frozen public document."
  group = "code generation"
  dependsOn("jvmMainClasses")
  classpath(
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("processedResources/jvm/main"),
    configurations.getByName("jvmRuntimeClasspath"),
  )
  mainClass.set("ee.schimke.composeai.uibuilder.GenerateJetcasterComposeFixture")
  javaLauncher.set(uiBuilderLauncher)
  args(
    rootProject.layout.projectDirectory
      .file(
        "ui-builder-generated-jetcaster/src/wasmJsMain/kotlin/generated/uibuilder/JetcasterDiscoverExpanded.kt"
      )
      .asFile
      .absolutePath
  )
}

tasks.register<JavaExec>("generateJetcasterSvgFixture") {
  description = "Generate the full structured Jetcaster SVG used by the real Figma import gate."
  group = "code generation"
  dependsOn("jvmMainClasses")
  classpath(
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("processedResources/jvm/main"),
    configurations.getByName("jvmRuntimeClasspath"),
  )
  mainClass.set("ee.schimke.composeai.uibuilder.GenerateJetcasterSvgFixture")
  javaLauncher.set(uiBuilderLauncher)
  args(layout.buildDirectory.file("figma-gate/jetcaster-discover.svg").get().asFile.absolutePath)
  outputs.file(layout.buildDirectory.file("figma-gate/jetcaster-discover.svg"))
}

val generatedJetcasterCheckFile =
  layout.buildDirectory.file("generated/ui-builder-check/JetcasterDiscoverExpanded.kt")

val generateJetcasterComposeFixtureForCheck =
  tasks.register<JavaExec>("generateJetcasterComposeFixtureForCheck") {
    description = "Generate Jetcaster Compose into build output for non-mutating verification."
    group = "verification"
    dependsOn("jvmMainClasses")
    classpath(
      layout.buildDirectory.dir("classes/kotlin/jvm/main"),
      layout.buildDirectory.dir("processedResources/jvm/main"),
      configurations.getByName("jvmRuntimeClasspath"),
    )
    mainClass.set("ee.schimke.composeai.uibuilder.GenerateJetcasterComposeFixture")
    javaLauncher.set(uiBuilderLauncher)
    args(generatedJetcasterCheckFile.get().asFile.absolutePath)
    outputs.file(generatedJetcasterCheckFile)
  }

val formatJetcasterComposeFixtureForCheck =
  tasks.register<JavaExec>("formatJetcasterComposeFixtureForCheck") {
    description = "Format the isolated generated fixture exactly like checked-in Kotlin."
    group = "verification"
    dependsOn(generateJetcasterComposeFixtureForCheck)
    classpath(ktfmtCli)
    mainClass.set("com.facebook.ktfmt.cli.Main")
    args("--google-style", generatedJetcasterCheckFile.get().asFile.absolutePath)
    inputs.file(generatedJetcasterCheckFile)
    outputs.file(generatedJetcasterCheckFile)
  }

tasks.register<VerifyGeneratedSource>("checkJetcasterComposeFixture") {
  description = "Fail when the checked-in Jetcaster Compose fixture is stale."
  group = "verification"
  dependsOn(formatJetcasterComposeFixtureForCheck)
  checkedIn.set(
    rootProject.layout.projectDirectory.file(
      "ui-builder-generated-jetcaster/src/wasmJsMain/kotlin/generated/uibuilder/JetcasterDiscoverExpanded.kt"
    )
  )
  expected.set(generatedJetcasterCheckFile)
}

tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the standalone Compose UI builder Wasm fixture."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.buildDirectory.dir("kotlin-multiplatform-resources/aggregated-resources/wasmJs"))
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) { include("index.html") }
  from(rootProject.layout.projectDirectory.dir("wasm-ui/src/wasmJsMain/resources")) {
    include("js-joda.esm.js")
  }
  from(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder")) {
    include(
      "confetti-schedule-operations-v1.json",
      "m3-catalog-capabilities-v1.json",
      "jetcaster-discover-operations-v1.json",
    )
  }
  from(rootProject.layout.projectDirectory.dir("assets/rc-fonts")) {
    include("*.ttf", "fonts.json", "*OFL.txt", "LICENSE.txt")
    into("fonts")
  }
  into(layout.buildDirectory.dir("wasmDist"))
}

// `:server:installDist` stages the development Wasm app while callers may also request the Kotlin
// browser production distribution. Both Kotlin tasks touch the shared root package directory, so
// keep their writes ordered when they appear in one task graph.
tasks.named("wasmJsBrowserProductionWebpack") {
  mustRunAfter("wasmJsDevelopmentExecutableCompileSync")
}

// The packaged renderer bundle is embedded in :ui-builder-runtime's jar and ships in the server, so
// it carries the production preview and nothing else. `UiBuilderEditorChromePreview` exists to be
// diffed, not shipped: leaving it in the bundle took it from 478 KB to 1.6 MB, because the editor
// chrome drags in the whole authoring UI that the document renderer never touches.
tasks.named<ee.schimke.composeai.plugin.BundlePreviewTask>("composePreviewBundle") {
  previewIds.set(
    listOf("ee.schimke.composeai.uibuilder.ProductionUiBuilderPreviewKt.ProductionUiBuilderPreview")
  )
}
