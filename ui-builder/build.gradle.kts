import java.security.MessageDigest

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

// The frontend JVM lane's floor, from the catalog. Tasks that run this module's classes must launch
// on it: `jvmToolchain` doesn't cover `JavaExec`.
val uiBuilderJava = JavaLanguageVersion.of(libs.versions.java.ui.builder.get().toInt())

val uiBuilderLauncher = javaToolchains.launcherFor { languageVersion.set(uiBuilderJava) }

/**
 * The vendored font families for the JVM, under `fonts/` on the classpath: the manifest and every
 * file it names. Roboto Flex is registered with the Wear port as `roboto-flex` (see
 * `ensureBundledWearDeviceFonts`) so JVM Wear renders don't use the fallback sans; the rest are
 * what `ProductionFontFamilies` uses for named typefaces. About 4.5 MB, in a bundle only the server
 * unpacks.
 */
val stageWearDeviceFonts =
  tasks.register<Sync>("stageWearDeviceFonts") {
    from(rootProject.layout.projectDirectory.dir("assets/rc-fonts")) {
      include("fonts.json", "*.ttf", "*.txt")
      into("fonts")
    }
    into(layout.buildDirectory.dir("generated/wearDeviceFonts"))
  }

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
  // `java-ui-builder`, above the rest of this build: these JVM classes only ship inside
  // `:ui-builder-render-bundle`'s polyglot PNG. The catalog entry for the two floors has the full
  // argument.
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
      implementation(libs.compose.material3)
      // The real Material 3 adaptive scaffolds; see `docs/design/UI_BUILDER_PREVIEW_FIDELITY.md`.
      implementation(libs.compose.material3.adaptive)
      implementation(libs.compose.material3.adaptive.layout)
      // Above the adaptive artifacts' own floor, for the Large width-class constant; the server's
      // renderer sidecar pins the same ref (see `androidx-window` in the catalog).
      implementation(libs.androidx.window.core)
      // `NavigationSuiteScaffold`, drawn for real for the same reason: whether a design's
      // navigation is a rail or a bar is the library's answer, per frame.
      implementation(libs.compose.material3.navigation.suite)
      // Wear Compose on the canvas via the CMP port (the upstream AAR has no `wasmJs` variant); it
      // keeps the upstream package names. See `docs/design/UI_BUILDER_WEAR_SCREEN.md`.
      implementation(libs.wearcmp.compose.material3)
      implementation(libs.wearcmp.compose.foundation)
      implementation(libs.material.icons.extended)
      @Suppress("DEPRECATION") implementation(compose.ui)
      // Versions come from the platforms (see the catalog). `project.dependencies.platform(...)`
      // because a KMP source set's `KotlinDependencyHandler` has no `platform` function.
      implementation(project.dependencies.platform(libs.composeai.tools.bom))
      implementation(project.dependencies.platform(libs.composeai.contracts.bom))
      implementation(project.dependencies.platform(libs.composeai.rc.players.bom))
      implementation(libs.composeai.ui.builder.protocol)
      // The real `ScreenGenerator`, compiled for wasmJs as well as the JVM, so the editor asks the
      // same question the server's export answers.
      implementation(libs.composeai.screen.model)
      // `api`: `UiBuilderDocument` appears in this module's public signatures (e.g.
      // `UiBuilderSurface`), so consumers like `:ui-builder-renderer` must see it.
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
      // The Theme panel's "Generate from a seed colour": the Material colour utilities, for JVM and
      // wasmJs alike. See `ThemeSchemes.kt`.
      implementation(libs.materialkolor)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    getByName("jvmTest").dependencies {
      // The Compose UI-test harness, for asserting on what the editor draws rather than only
      // diffing PNGs. `compose.desktop.currentOs` supplies the Skiko the test scene renders on.
      @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class) @Suppress("DEPRECATION")
      implementation(compose.uiTest)
      implementation(compose.desktop.currentOs)
      // The hosted service, for `ServiceConformanceTest` (the in-page service and the runtime must
      // answer v1 requests the same). Test-only. The render bundle is excluded: it is built from
      // this module's previews.
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
      // Version from the platform; `project.dependencies.platform(...)` as above. The daemon
      // platform is here, not in `commonMain`, because its only coordinate is JVM-only; the other
      // platforms stay in `commonMain` since a `jvmMain` platform doesn't constrain wasmJs
      // resolution.
      implementation(project.dependencies.platform(libs.composeai.daemon.bom))
      implementation(libs.composeai.data.preview.overrides.runtime)
      implementation(libs.composeai.preview.annotations)
      // The JVM previews use the same published catalog store as desktop and IDE hosts. Exclude
      // the render bundle: this module builds the previews packaged inside that bundle.
      implementation(project(":ui-builder-runtime")) {
        exclude(group = "ee.schimke.composeai", module = "ui-builder-render-bundle")
      }
    }
    getByName("jvmMain").resources.apply {
      srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
      // Runtime previews compose these catalogs from their published delivery files.
      exclude("wear-m3-capabilities-v1.json", "remote-m3-capabilities-v1.json")
    }
    getByName("jvmMain").resources.srcDir(stageWearDeviceFonts)
    getByName("jvmTest") {
      kotlin.srcDir(stageBehaviorCompileFixture)
      resources.srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
      resources.exclude("wear-m3-capabilities-v1.json", "remote-m3-capabilities-v1.json")
    }
  }
}

// JVM tests exercise the exact captured delivery files without fetching the network.
tasks.withType<Test>().configureEach {
  val catalogCaptures = rootProject.file("ui-builder-runtime/src/test/resources/catalog-cutover")
  inputs.dir(catalogCaptures).withPropertyName("catalogCutoverCaptures")
  systemProperty(
    "compose.uiBuilder.catalogDir",
    catalogCaptures.absolutePath,
  )
}

/**
 * Order preview discovery after this module's wasmJs and Compose-resources tasks.
 *
 * `composePreviewDiscover` and `composePreviewBundle` read this module's processed resource and
 * class directories, but the plugin only declares the desktop producers. The wasmJs and
 * multiplatform-resource tasks write into the same directories, which Gradle rejects as an
 * undeclared dependency.
 *
 * `mustRunAfter` rather than `dependsOn`, so desktop-only invocations don't build wasm. Matched by
 * shape since the set of tasks varies with the graph, and the wasm match is case-insensitive
 * (`compileKotlinWasmJs` vs `wasmJsCopyHierarchicalMultiplatformResources`, `wasmFrontendDist`).
 * The real fix belongs in the plugin.
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

// The typed catalog bridge's compiled-canvas proof; the external bundle end-to-end test runs only
// when a catalog's packaged bundle is named.
tasks.named<Test>("jvmTest") {
  // Keep the typed canvas's native validation reproducible on hosts with a read-only home.
  systemProperty(
    "skiko.data.path",
    layout.buildDirectory.dir("typed-catalog/skiko").get().asFile.absolutePath,
  )
  val bundle =
    providers.gradleProperty("typedCatalogJvmBundle").orNull?.let { rootProject.file(it) }
  if (bundle != null) {
    inputs.dir(bundle)
    systemProperty("typedCatalogJvmBundle", bundle.absolutePath)
    outputs.dir(layout.buildDirectory.dir("typed-catalog/e2e"))
    systemProperty(
      "typedCatalogJvmEvidence",
      layout.buildDirectory.dir("typed-catalog/e2e").get().asFile.absolutePath,
    )
  } else {
    exclude("**/ExternalJvmCatalogEndToEndTest*")
  }
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

// Canvas frame times over the Jetcaster fixture on the JVM desktop backend (see #193). Reports,
// doesn't gate: `jvmTest` skips the benchmark; this runs it alone and writes the numbers CI keeps
// as an artifact.
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

// The PRODUCTION executable from `optimized/` (after Binaryen: DCE, optimised, no debug names),
// which is what browsers and hosts download; much smaller than the development build or the
// pre-Binaryen `kotlin/`. `wasmJsBrowserDevelopmentRun` still serves the development executable.
//
// `-PuiBuilder.wasmOpt=false` packages `kotlin/` instead; see `ProductionWasm.kt` in build-logic.
val wasmExecutableDir = productionWasmDir

// Generated lookup tables Binaryen must leave as separate functions. Binaryen inlines one-caller
// functions whatever their size, producing huge functions (Material 3 locale tables, icon builders)
// that V8's TurboFan then spends hundreds of MB and seconds compiling. Kept apart, only the tables
// a design reaches get compiled. A global `--one-caller-inline-max-function-size` cap doesn't work.
// `wasm-opt` matches `*`.
val wasmNoInlinePatterns =
  listOf(
    "androidx.compose.material3.l10n.*",
    "androidx.compose.foundation.text.l10n.*",
    "androidx.compose.material.icons.*",
    "ee.schimke.composeai.uibuilder.renderer.sdk.generated*",
    "ee.schimke.composeai.uibuilder.export.generatedMaterialIconMembersChunk*",
    // Export's per-kind node builders (placement, repetition, selection, variable font text, the
    // catalog's export projection) each have one caller, `Pass.node`; inlined, they made it the
    // largest TurboFan compile on opening an M3 design (166 MB once the projection joined them).
    "ee.schimke.composeai.uibuilder.export.Pass.*Node",
  )

// `--no-inline` is a pass and must precede the inlining passes, so the rules go right after the
// plugin's own `--no-inline` entries; if the plugin's defaults change shape this fails loudly.
tasks.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec>().configureEach {
  val defaults = org.jetbrains.kotlin.gradle.internal.platform.wasm.BinaryenConfig.binaryenArgs
  val insertAt = defaults.indexOfLast { it.startsWith("--no-inline=") } + 1
  check(insertAt > 0) {
    "the Kotlin plugin's Binaryen defaults have no --no-inline entry to place the rules after"
  }
  binaryenArguments.set(
    defaults.subList(0, insertAt) +
      wasmNoInlinePatterns.map { "--no-inline=$it" } +
      defaults.subList(insertAt, defaults.size)
  )
}

val skikoRuntimeDir = layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")

evaluationDependsOn(":ui-builder-renderer-sdk")

tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the standalone Compose UI builder Wasm fixture."
  group = "distribution"
  dependsOn(productionWasmTaskName, "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  from(wasmExecutableDir) {
    // Source maps point at sources nobody serving the archive has; they are dead weight in it.
    exclude("*.map")
  }
  from(skikoRuntimeDir) { include("skiko.mjs", "skiko.wasm") }
  from(layout.buildDirectory.dir("kotlin-multiplatform-resources/aggregated-resources/wasmJs"))
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) {
    include("index.html", "ui-builder-sw.js")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources")) { include("ui-builder-boot.js") }
  from(rootProject.layout.projectDirectory.dir("assets/js-joda")) { include("js-joda.esm.js") }
  from(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder")) {
    include(
      "confetti-schedule-operations-v1.json",
      "m3-catalog-capabilities-v1.json",
      "jetcaster-discover-operations-v1.json",
      // Not fetched by the page: host-bridge hosts read these from the unpacked archive and pass
      // the pinned one to the editor (see HostBridgeApp.kt), so all three offline catalogs work.
      "wear-m3-capabilities-v1.json",
      "remote-m3-capabilities-v1.json",
    )
  }
  from(rootProject.layout.projectDirectory.dir("assets/rc-fonts")) {
    include("*.ttf", "fonts.json", "google-fonts.txt", "*OFL.txt")
    into("fonts")
  }
  // Material icon vectors as data, fetched per file on first draw (`GoogleMaterialIconVectors`).
  from(project(":ui-builder-renderer-sdk").tasks.named("generateMaterialIconData")) {
    into("icons")
  }
  val distDir = layout.buildDirectory.dir("wasmDist")
  into(distDir)
  // The service worker names its cache after the bundle, so its bytes must change whenever any file
  // does. Written after the copy so the digest covers exactly what ships; the file list is what the
  // worker precaches.
  doLast {
    val root = distDir.get().asFile
    val worker = root.resolve("ui-builder-sw.js")
    val digest = MessageDigest.getInstance("SHA-256")
    root
      .walkTopDown()
      .filter { it.isFile && it != worker }
      .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
      .forEach { file ->
        digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
        digest.update(file.length().toString().toByteArray())
        file.inputStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
          }
        }
      }
    val version = digest.digest().take(8).joinToString("") { "%02x".format(it) }
    // The shell is fetched (and its references found) by the worker itself; licences and notes
    // are not fetched by anything.
    val bundle =
      root
        .walkTopDown()
        .filter { it.isFile && it != worker }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .filter { it != "index.html" && !it.endsWith(".txt") && !it.endsWith(".md") }
        .sorted()
        .joinToString(",", "[", "]") { "\"$it\"" }
    worker.writeText(
      worker
        .readText()
        .replace("@UI_BUILDER_SW_VERSION@", version)
        .replace("@UI_BUILDER_SW_BUNDLE@", bundle)
    )
  }
}

// Both tasks touch the shared root package directory, so order their writes when they share a
// graph.
tasks.named("wasmJsBrowserProductionWebpack") {
  mustRunAfter("wasmJsDevelopmentExecutableCompileSync")
}

// The packaged renderer bundle ships in the server, so it carries only the production preview
// (`UiBuilderEditorChromePreview` would pull in the whole authoring UI).
//
// Inside `afterEvaluate` because the compose-ai-tools plugin defers the Desktop lane's task
// registration to `afterEvaluate` for KMP modules without the Android KMP library plugin. Not
// `withType(...).configureEach`: the plugin's deferred registration resets `previewIds` after it,
// silently selecting nothing.
afterEvaluate {
  tasks.named<ee.schimke.composeai.plugin.BundlePreviewTask>("composePreviewBundle") {
    previewIds.set(
      listOf(
        "ee.schimke.composeai.uibuilder.ProductionUiBuilderPreviewKt.ProductionUiBuilderPreview"
      )
    )
  }
}
