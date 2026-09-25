plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.preview)
}

ktfmt { googleStyle() }

val embedComponentRecord =
  tasks.register<EmbedComponentRecord>("embedComponentRecord") {
    record.set(rootProject.file("docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"))
    foundation.set(
      rootProject.file("docs/design/fixtures/ui-builder/compose-foundation-components-v1.json")
    )
    output.set(
      layout.buildDirectory.file(
        "generated/componentRecord/ee/schimke/composeai/uibuilder/EmbeddedComponentRecord.kt"
      )
    )
  }

val embedReleaseNotes =
  tasks.register<EmbedReleaseNotes>("embedReleaseNotes") {
    changelog.set(rootProject.file("CHANGELOG.md"))
    output.set(
      layout.buildDirectory.file(
        "generated/releaseNotes/ee/schimke/composeai/uibuilder/editor/EmbeddedReleaseNotes.kt"
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
      kotlin.srcDir(
        embedComponentRecord.map { layout.buildDirectory.dir("generated/componentRecord") }
      )
      kotlin.srcDir(embedReleaseNotes.map { layout.buildDirectory.dir("generated/releaseNotes") })
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
      // `NavigationSuiteScaffold`, drawn for real for the same reason: whether a design's
      // navigation is a rail or a bar is the library's answer, per frame.
      implementation(libs.compose.material3.navigation.suite)
      // Wear Compose, for real, on the canvas. See `docs/design/UI_BUILDER_WEAR_SCREEN.md`: this
      // is the CMP port rather than `androidx.wear.compose`, because that one is an Android AAR
      // with no `wasmJs` variant to resolve. The port keeps the upstream package names, so a
      // `TransformingLazyColumn` here is `androidx.wear.compose.material3`'s by import.
      implementation(libs.wearcmp.compose.material3)
      implementation(libs.wearcmp.compose.foundation)
      implementation(libs.material.icons.extended)
      @Suppress("DEPRECATION") implementation(compose.ui)
      // These carry no version of their own; the platforms supply them (see the catalog).
      // `project.dependencies.platform(...)`, not a bare `platform(...)`: inside a Kotlin
      // Multiplatform source set the receiver is `KotlinDependencyHandler`, which has no
      // `platform` function at all.
      implementation(project.dependencies.platform(libs.composeai.tools.bom))
      implementation(project.dependencies.platform(libs.composeai.contracts.bom))
      implementation(project.dependencies.platform(libs.composeai.rc.players.bom))
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
      // Public protocol and inspection types moved to the catalog renderer SDK. Keep this `api` so
      // existing consumers of :ui-builder see the same signatures transitively.
      api(project(":ui-builder-renderer-sdk"))
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
      // The hosted service, for `ServiceConformanceTest`: the editor's in-page service and the
      // runtime's answer the same v1 requests, and only a test that can see both can hold them to
      // the same answers. Test-only, so the editor's own classpath stays service-free. The render
      // bundle is excluded because it is built FROM this module's previews, and nothing here
      // renders through it.
      implementation(project(":ui-builder-runtime")) {
        exclude(group = "ee.schimke.composeai", module = "ui-builder-render-bundle")
      }
    }
    getByName("jvmMain").dependencies {
      // Feasibility spike only: the saved-document bridge executes ComposeScene against SVGCanvas.
      // It remains NO-GO for production/Figma until a representative nested scene succeeds and the
      // adapter moves behind :render-host. currentOs supplies the matching local Skiko runtime.
      implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.compose.ui.tooling.preview)
      // Carries no version of its own; the platform supplies it (see the catalog).
      // `project.dependencies.platform(...)`, not a bare `platform(...)`: inside a Kotlin
      // Multiplatform source set the receiver is `KotlinDependencyHandler`, which has no
      // `platform` function at all.
      //
      // The daemon platform belongs HERE rather than in `commonMain` because the only daemon
      // coordinate this module names is JVM-only. The other three platforms are in `commonMain`
      // with the coordinates they version: a platform declared on `jvmMain` constrains the JVM
      // compilation alone, so a `commonMain` dependency left to it resolves with no version at
      // all on wasmJs -- which is `Could not find ee.schimke.composeai:ui-builder-protocol:`,
      // with the empty version, out of `:ui-builder:wasmJsNpmAggregated`.
      implementation(project.dependencies.platform(libs.composeai.daemon.bom))
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

tasks.named<Test>("jvmTest") {
  // `DesignFixturesTest` reads the committed designs from disk, not from a hand-kept list, so a
  // file cannot join the directory without being replayed, validated and exported.
  systemProperty(
    "uiBuilderDesignFixturesDir",
    rootProject.file("docs/design/fixtures/ui-builder/designs").absolutePath,
  )
  systemProperty("uiBuilderProjectDir", projectDir.absolutePath)
}

// Canvas frame times over the Jetcaster fixture on the JVM desktop backend, not the Wasm canvas
// (#193). Reports, does not gate: `jvmTest` skips the benchmark, and this runs it alone and writes
// the numbers CI keeps as an artifact.
tasks.register<Test>("canvasFrameBenchmark") {
  description = "Measures editor edit-to-canvas time over the Jetcaster fixture."
  group = "verification"
  val jvmTest = tasks.named<Test>("jvmTest").get()
  testClassesDirs = jvmTest.testClassesDirs
  classpath = jvmTest.classpath
  javaLauncher.set(jvmTest.javaLauncher)
  filter.includeTestsMatching("*.CanvasFrameTimeBenchmark")
  val report = layout.buildDirectory.file("reports/ui-builder/canvas-frame-times.json")
  outputs.file(report)
  outputs.upToDateWhen { false }
  systemProperty("uiBuilder.canvasBenchmark", "true")
  systemProperty("uiBuilder.canvasBenchmark.report", report.get().asFile.absolutePath)
  testLogging.showStandardStreams = true
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
  mainClass.set("ee.schimke.composeai.uibuilder.codegen.GenerateJetcasterComposeFixture")
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
  mainClass.set("ee.schimke.composeai.uibuilder.svg.GenerateJetcasterSvgFixture")
  javaLauncher.set(uiBuilderLauncher)
  args(layout.buildDirectory.file("figma-gate/jetcaster-discover.svg").get().asFile.absolutePath)
  outputs.file(layout.buildDirectory.file("figma-gate/jetcaster-discover.svg"))
}

tasks.register<JavaExec>("exportFigmaScene") {
  description =
    "Export a design's operation log as a compose-ui-builder-figma-scene/v1, sized from its " +
      "measured layout (-PuiBuilderDesign=<operations.json>)."
  group = "code generation"
  dependsOn("jvmMainClasses")
  classpath(
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("processedResources/jvm/main"),
    configurations.getByName("jvmRuntimeClasspath"),
  )
  mainClass.set("ee.schimke.composeai.uibuilder.figma.ExportFigmaScene")
  javaLauncher.set(uiBuilderLauncher)
  val output = layout.buildDirectory.file("figma-scene/scene.json")
  args(
    providers
      .gradleProperty("uiBuilderDesign")
      .map { rootProject.file(it).absolutePath }
      .getOrElse(""),
    output.get().asFile.absolutePath,
  )
  outputs.file(output)
}

tasks.register<JavaExec>("figmaTool") {
  description =
    "Import a Figma snapshot, export a Figma scene, or reconcile Figma edits into a command " +
      "(-PfigmaArgs=\"import|export|reconcile …\")."
  group = "code generation"
  dependsOn("jvmMainClasses")
  classpath(
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("processedResources/jvm/main"),
    configurations.getByName("jvmRuntimeClasspath"),
  )
  mainClass.set("ee.schimke.composeai.uibuilder.figma.FigmaTool")
  javaLauncher.set(uiBuilderLauncher)
  workingDir = rootProject.projectDir
  args(providers.gradleProperty("figmaArgs").getOrElse("").split(" ").filter { it.isNotBlank() })
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
    mainClass.set("ee.schimke.composeai.uibuilder.codegen.GenerateJetcasterComposeFixture")
    javaLauncher.set(uiBuilderLauncher)
    args(generatedJetcasterCheckFile.get().asFile.absolutePath)
    outputs.file(generatedJetcasterCheckFile)
  }

val formatJetcasterComposeFixtureForCheck =
  tasks.register<FormatLikeKtfmtPlugin>("formatJetcasterComposeFixtureForCheck") {
    description = "Format the isolated generated fixture exactly like checked-in Kotlin."
    group = "verification"
    dependsOn(generateJetcasterComposeFixtureForCheck)
    ktfmtClasspath.from(ktfmtCli)
    source.set(generatedJetcasterCheckFile)
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

tasks.register<CheckWindowSidecarVersion>("checkWindowSidecarVersion") {
  description = "Fail when the server's androidx.window sidecar pin drifts from this module's."
  group = "verification"
  runtimeClasspath.from(configurations.named("jvmRuntimeClasspath"))
  pinned.set(libs.versions.androidx.window)
}

tasks.named("check") { dependsOn("checkWindowSidecarVersion") }

// The PRODUCTION executable, after Binaryen: dead code eliminated, optimised, no debug names. This
// is what a browser downloads and compiles before it can draw anything. The development executable
// it replaced was a 74 MB `uiBuilder.wasm` (13.3 MB gzipped); this one is 28.8 MB (5.9 MB), and
// every host of this archive — compose-preview-server, the VS Code webview — pays that difference
// on each cold open. `optimized/`, not the sibling `kotlin/`: that one is the production IR before
// Binaryen has run, and still 61 MB. The development executable is still what
// `wasmJsBrowserDevelopmentRun` serves for local work.
val wasmExecutableDir =
  layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/optimized")
val skikoRuntimeDir = layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")

tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the standalone Compose UI builder Wasm fixture."
  group = "distribution"
  dependsOn("compileProductionExecutableKotlinWasmJsOptimize", "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  from(wasmExecutableDir) {
    // Source maps point at sources nobody serving the archive has; they are dead weight in it.
    exclude("*.map")
  }
  from(skikoRuntimeDir) { include("skiko.mjs", "skiko.wasm") }
  from(layout.buildDirectory.dir("kotlin-multiplatform-resources/aggregated-resources/wasmJs"))
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) { include("index.html") }
  // The boot screen's progress bar counts decoded Wasm bytes, which only the build knows ahead of
  // time: behind a compressing proxy `Content-Length` is the compressed size, or absent.
  val wasmFiles =
    listOf(
      wasmExecutableDir.get().file("uiBuilder.wasm").asFile,
      skikoRuntimeDir.get().file("skiko.wasm").asFile,
    )
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) {
    include("ui-builder-boot.js")
    filter { line ->
      if ("@UI_BUILDER_WASM_BYTES@" !in line) line
      else line.replace("@UI_BUILDER_WASM_BYTES@", wasmFiles.sumOf { it.length() }.toString())
    }
  }
  from(rootProject.layout.projectDirectory.dir("assets/js-joda")) { include("js-joda.esm.js") }
  from(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder")) {
    include(
      "confetti-schedule-operations-v1.json",
      "m3-catalog-capabilities-v1.json",
      "jetcaster-discover-operations-v1.json",
      // Not fetched by the page: host-bridge hosts read these from the unpacked archive and
      // hand the one a design pins to the editor with the document (see HostBridgeApp.kt), so a
      // host can open a design in any of the three offline catalogs, not only Material 3.
      "wear-m3-capabilities-v1.json",
      "remote-m3-capabilities-v1.json",
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
