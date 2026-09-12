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

// Compile the exact shared-export golden. Keep the source in build output so formatting does not
// rewrite the generator's spelling; the server test checks every byte against ScreenExportGate.
val stageBehaviorCompileFixture =
  tasks.register<Sync>("stageBehaviorCompileFixture") {
    from(rootProject.file("docs/design/fixtures/ui-builder/state-actions.kt.txt")) {
      rename { "StateActions.kt" }
    }
    from(rootProject.file("docs/design/fixtures/ui-builder/clickable-state-layout.kt.txt")) {
      rename { "ClickableStateLayout.kt" }
    }
    into(layout.buildDirectory.dir("generated/behavior-fixture"))
  }

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
      kotlin.srcDir(rootProject.tasks.named("generateMaterialIconUiSources"))
      kotlin.srcDir(
        embedComponentRecord.map { layout.buildDirectory.dir("generated/componentRecord") }
      )
    }
    commonMain.dependencies {
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      // The real Material 3 adaptive scaffolds, so the canvas and the preview pane draw
      // `SupportingPaneScaffold` itself rather than a `BoxWithConstraints` imitating one. See
      // `docs/design/UI_BUILDER_PREVIEW_FIDELITY.md` for why the preview pane owes real components.
      implementation(libs.compose.material3.adaptive)
      implementation(libs.compose.material3.adaptive.layout)
      implementation(libs.material.icons.extended)
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
    getByName("jvmTest").dependencies {
      // The Compose UI-test harness. Until this, everything the editor *draws* was verified by
      // rendering a `@Preview` to PNG and comparing bytes, which catches a changed picture but
      // cannot ask a question about it — "is this row's Add disabled", "does this sentence appear
      // once or forty-one times". Both regressions #619 fixes were of the second kind, and both
      // reached `main`.
      //
      // `compose.desktop.currentOs` supplies the Skiko the test scene renders on; the same software
      // rendering the preview lane already uses headlessly here.
      @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class) @Suppress("DEPRECATION")
      implementation(compose.uiTest)
      implementation(compose.desktop.currentOs)
    }
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
      kotlin.srcDir(stageBehaviorCompileFixture)
      resources.srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
    }
  }
}

/**
 * Order preview discovery after this module's wasmJs and Compose-resources tasks.
 *
 * `composePreviewDiscover` reads this module's processed resource and class directories. The plugin
 * declares dependencies for the *desktop* tasks that write them (`DESKTOP_RESOURCE_TASK_CANDIDATES`
 * / `DESKTOP_COMPILE_TASK_CANDIDATES`) — which is the whole story in a JVM-only module. This one
 * also has a wasmJs target and Compose multiplatform resources, and those tasks write into the same
 * directories without being in either list, so Gradle sees an undeclared producer to consumer edge
 * and fails the build during configuration:
 *
 *     Task ':ui-builder:composePreviewDiscover' uses this output of task
 *     ':ui-builder:compileKotlinWasmJs' without declaring an explicit or implicit dependency
 *
 * `mustRunAfter` rather than `dependsOn` on purpose: the ordering is all Gradle needs to validate,
 * and `dependsOn` would make every `compose-preview list` on the desktop lane build the wasm target
 * first — a cost this module pays for nothing. When the task is asked for alone, none of these are
 * in the graph and this adds no ordering at all.
 *
 * Matched by shape rather than by a fixed list of names, because the set that trips the validation
 * depends on which tasks share the graph: `check` surfaced five, a narrower invocation five
 * different ones. The real fix belongs in the plugin, which should declare what it reads for every
 * target rather than for the desktop one — this keeps the build green until it does.
 *
 * The wasm match is CASE-INSENSITIVE, and that is the whole point of it. The first version of this
 * looked for `WasmJs` — the shape a task name takes when the target is a *suffix*
 * (`compileKotlinWasmJs`). Tasks whose name *starts* with the target spell it `wasmJs…` with a
 * lower-case w, and three of those write into the same directories:
 * `wasmJsCopyHierarchicalMultiplatformResources`, `wasmJsZipMultiplatformResourcesForPublication`
 * and this module's own `wasmFrontendDist`. None of them matched, so the validation the block
 * exists to prevent failed the `visual-harness` job on `main` anyway. A case-sensitive match on a
 * name that appears in both cases is a check that does not check.
 *
 * `composePreviewBundle` is ordered too, not only `composePreviewDiscover`. It reads the same
 * directories, and CI's `check` graph reported it as a second consumer of `wasmJsPublicPackageJson`
 * / `wasmJsTestPublicPackageJson` in the same build the discover pair failed. Ordering one of two
 * consumers of the same outputs fixes the half of the build whose graph you happened to reproduce.
 */
tasks
  .matching { it.name == "composePreviewDiscover" || it.name == "composePreviewBundle" }
  .configureEach {
    mustRunAfter(
      tasks.matching { producer ->
        producer.name.contains("wasm", ignoreCase = true) ||
          producer.name.startsWith("prepareComposeResourcesTaskFor") ||
          producer.name.startsWith("copyNonXmlValueResourcesFor") ||
          producer.name.startsWith("convertXmlValueResourcesFor")
      }
    )
  }

val collaborationSoakMinutes = providers.gradleProperty("uiBuilderCollaborationSoakMinutes")

tasks.named<Test>("jvmTest") {
  collaborationSoakMinutes.orNull?.let { systemProperty("uiBuilderCollaborationSoakMinutes", it) }
  // `DesignFixturesTest` reads the committed designs from disk, not from a hand-kept list, so a
  // file cannot join the directory without being replayed, validated and exported.
  systemProperty(
    "uiBuilderDesignFixturesDir",
    rootProject.file("docs/design/fixtures/ui-builder/designs").absolutePath,
  )
  systemProperty("uiBuilderProjectDir", projectDir.absolutePath)
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
// Inside `afterEvaluate`, because compose-ai-tools 2.8.0 (its #5380) defers the Desktop lane's task
// registration to `afterEvaluate` on any module applying the Kotlin Multiplatform plugin without
// `com.android.kotlin.multiplatform.library` — this one — so that a KMP-Android module applying
// `org.jetbrains.compose` first can still claim the Robolectric lane. `composePreviewBundle` no
// longer exists while this script body runs, and naming it here failed configuration outright.
//
// The plugin registers its `afterEvaluate` during the `plugins { }` block, so it runs before this
// one and the task is there by the time we name it. `withType(...).configureEach` looks like the
// order-independent answer and is a trap: the plugin's registration action does
// `previewIds.set(previewIdsProperty.orElse(emptyList()))`, and under deferred registration that
// action runs *after* a `configureEach` added earlier — silently resetting the selection to empty.
// The bundle still built, at 70 previews and 4.9 MB instead of 1 and 2.5 MB.
afterEvaluate {
  tasks.named<ee.schimke.composeai.plugin.BundlePreviewTask>("composePreviewBundle") {
    previewIds.set(
      listOf(
        "ee.schimke.composeai.uibuilder.ProductionUiBuilderPreviewKt.ProductionUiBuilderPreview"
      )
    )
  }
}
