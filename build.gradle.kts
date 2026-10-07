import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockStoreTask
import org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec

plugins {
  base
  // Task types for this build's scripts (the Material icon generators, `:ui-builder`'s fixture
  // tasks); see `BuildTasksPlugin`.
  id("composeai.build-tasks")
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.maven.publish) apply false
}

val materialIconGeneratorClasspath =
  configurations.create("materialIconGeneratorClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
  }

dependencies {
  materialIconGeneratorClasspath(libs.material.icons.extended.desktop)
  materialIconGeneratorClasspath(libs.material.icons.core.desktop)
}

val generateMaterialIconInventory =
  tasks.register<GenerateMaterialIconInventory>("generateMaterialIconInventory") {
    group = "code generation"
    description = "Indexes every vector in the shipped Compose Material Icons artifact."
    iconClasspath.from(materialIconGeneratorClasspath)
    output.set(layout.buildDirectory.file("generated/materialIcons/material-icon-inventory.tsv"))
  }

val generateMaterialIconUiSources =
  tasks.register<GenerateMaterialIconUiSources>("generateMaterialIconUiSources") {
    group = "code generation"
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    outputDirectory.set(layout.buildDirectory.dir("generated/materialIcons/ui-builder"))
    vectorOutputDirectory.set(layout.buildDirectory.dir("generated/materialIcons/ui-builder-jvm"))
  }

val generateMaterialIconExportSource =
  tasks.register<GenerateMaterialIconExportSource>("generateMaterialIconExportSource") {
    group = "code generation"
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    outputDirectory.set(layout.buildDirectory.dir("generated/materialIcons/ui-builder-export"))
  }

val m3MaterialIconCatalogFixture =
  layout.projectDirectory.file("docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json")
val generateMaterialIconCatalogFixture =
  tasks.register<GenerateMaterialIconCatalogFixture>("generateMaterialIconCatalogFixture") {
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    catalog.set(m3MaterialIconCatalogFixture)
    output.set(
      layout.buildDirectory.file("generated/materialIcons/m3-catalog-capabilities-v1.json")
    )
  }

// `wear-m3-capabilities-v1.json` is deliberately absent here. It is a **golden**, owned by
// `SynthesisedCatalogGoldenTest`: `wearM3Catalog` is synthesised in Kotlin from the packaged
// Material 3 catalog, so the golden already carries the full icon list and regenerating it is what
// records an icon-catalog change. Patching the same file here as well gave it two writers that
// produce the same icons in different bytes — the allowlist spliced onto one line here, expanded
// one entry per line by the golden's `Json { prettyPrint = true }` — and `VerifyMatchingFile`
// compares bytes, so no content of the file could satisfy both. Every push since the icon catalog
// grew was red on whichever of the two ran last (#710).
//
// `m3-catalog-capabilities-v1.json` has no golden and stays here, which is what this task is for.
tasks.register<UpdateMaterialIconCatalogFixtures>("updateMaterialIconCatalogFixture") {
  group = "code generation"
  description = "Updates the m3 icon allowlist from the shipped Material Icons artifact."
  generatedM3.set(generateMaterialIconCatalogFixture.flatMap { it.output })
  checkedInM3.set(m3MaterialIconCatalogFixture)
}

val checkMaterialIconCatalogFixture =
  tasks.register<VerifyMatchingFile>("checkMaterialIconCatalogFixture") {
    checkedIn.set(m3MaterialIconCatalogFixture)
    expected.set(generateMaterialIconCatalogFixture.flatMap { it.output })
  }

tasks.named("check") { dependsOn(checkMaterialIconCatalogFixture) }

tasks.named("check") {
  group = "verification"
  dependsOn(
    ":ui-builder:check",
    ":ui-builder-desktop:check",
    ":ui-builder-host-jvm:check",
    ":ui-builder-artwork:check",
    ":ui-builder-export:check",
    ":ui-builder-codegen-jvm:check",
    ":ui-builder-gradle-plugin:check",
    ":ui-builder-production-consumer:check",
    ":ui-builder-generated-jetcaster:check",
    ":ui-builder-reference-jetcaster:check",
    ":ui-builder-render-bundle:check",
    ":ui-builder-renderer:check",
    ":ui-builder-renderer-sdk:check",
    ":ui-builder-runtime:check",
    ":ui-builder-web:check",
  )
}

// ---------------------------------------------------------------------------
// One Kotlin/Wasm executable links at a time.
// ---------------------------------------------------------------------------
//
// `kotlin.daemon.jvmargs=-Xmx6g` above is sized for ONE ir2wasm pass. There is one Kotlin daemon
// for the whole build, `org.gradle.parallel=true`, and twenty-six of these link tasks across seven
// modules — so `check` runs several of them inside that single 6 GB heap and it dies with "Not
// enough memory to run compilation".
//
// That is what took `main` red from #710 (the complete Material icon inventory) through #711's
// 4g -> 6g bump, which raised the ceiling without changing how many passes share it. The failure
// moved between `:ui-builder:compileTestDevelopmentExecutableKotlinWasmJs` and
// `:ui-builder-renderer:compileDevelopmentExecutableKotlinWasmJs` run to run — whichever pair
// happened to overlap, which is the signature of contention rather than of one task being too big.
//
// The evidence that a single pass does fit: the `visual-harness` job compiles four of these
// executables at the same 6 GB and passes, because it runs `--no-daemon --no-parallel
// --max-workers=1`. And `:ui-builder:compileTestDevelopmentExecutableKotlinWasmJs` — the one CI
// dies on — links in 5m13s at 6 GB on a 15 GB machine when nothing else shares the daemon.
//
// So the constraint is stated where it is true, rather than by serialising a whole CI job or by
// raising a number that has to be raised again the next time the icon set grows. A shared build
// service with `maxParallelUsages = 1` is Gradle's way to say "these tasks must not run
// concurrently with each other"; everything else in the build stays parallel.
//
// Matched by TASK TYPE, not by name. A name pattern is the same hand-kept list `ktfmtCheckAll`
// was, one rename away from silently matching nothing and letting the OOM back in with no test to
// notice — and there is no cheap test for "CI has enough memory". `KotlinJsIrLink` is the type
// Kotlin gives every executable link; if it is renamed the build stops compiling here instead.
abstract class WasmLinkLane : BuildService<BuildServiceParameters.None>

val wasmLinkLane =
  gradle.sharedServices.registerIfAbsent("wasmLinkLane", WasmLinkLane::class) {
    maxParallelUsages.set(1)
  }

// The Binaryen pass shares the lane. Each production executable is linked by the Kotlin daemon and
// then optimised by `wasm-opt`, a native process outside both daemons, and the lane above only
// covered the first half. `wasm-opt` peaks at 5.6 GB on `:ui-builder`'s executable, measured on a
// 16 GB, 4-core machine shaped like `ubuntu-latest`, and it ran while the renderer's link held the
// Kotlin daemon at 6.6 GB: 14.2 GB in use before CI's test JVMs are counted. On CI the runner
// was killed there, "The runner has received a shutdown signal", during
// `:ui-builder-renderer:compileProductionExecutableKotlinWasmJsOptimize`, on `main` and on PRs.
// Same type-based matching, for the same reason. The lane alone took the peak to 13.6 GB: the idle
// daemon still held its committed heap, which `kotlin.daemon.jvmargs` now returns (see
// `gradle.properties`). Both together measured 8.4 GB.
subprojects {
  tasks.withType<KotlinJsIrLink>().configureEach { usesService(wasmLinkLane) }
  tasks.withType<BinaryenExec>().configureEach { usesService(wasmLinkLane) }
}

// Kotlin registers this root task after the Wasm projects are configured. Its action already treats
// an absent lock as "no dependencies", but Gradle 9 validates the task's non-optional input first
// and fails before that action can make the decision. On a fresh CI checkout Yarn can legitimately
// produce no lock (for example while its cache is restored), so skip the store operation in that
// case. When Yarn does write the lock, this remains the normal task and copies it to kotlin-js-store.
tasks.withType<YarnLockStoreTask>().configureEach {
  onlyIf("the generated Yarn lock exists") { inputFile.asFile.get().isFile }
}

// The ktfmt tasks write per-file results under `build/tmp/<task>/<uuid>/` and delete the directory
// when they finish, so they are shared state a task cleans up after itself. The lane keeps two of
// them from running at once for the same reason `wasmLinkLane` exists above: the constraint is
// real, and the service is Gradle's way to state it without serialising the rest of the build.
abstract class KtfmtLane : BuildService<BuildServiceParameters.None>

val ktfmtLane =
  gradle.sharedServices.registerIfAbsent("ktfmtLane", KtfmtLane::class) {
    maxParallelUsages.set(1)
  }

// The formatting aggregates — DERIVED, never listed, for the reason the Maven set below is.
//
// They were hand-kept lists and had drifted by four modules: `mcp`, `native-catalog-m3`,
// `ui-builder-artwork` and `ui-builder-export`. `AGENTS.md` tells every contributor and every
// agent to run `ktfmtCheckAll` before committing, CI's `check` reaches those four anyway, so the
// gate that was supposed to save a round trip was the thing costing one — twice in one session on
// the same file. A list of modules that has to be edited when a module is added is not a gate,
// it is a reminder.
//
// Keyed on the ktfmt plugin rather than on the module list: a module formats if and only if it
// applies the plugin, which is the same fact from the only place that states it.
val ktfmtCheckAll = tasks.register("ktfmtCheckAll") { group = "verification" }

val ktfmtFormatAll = tasks.register("ktfmtFormat") { group = "formatting" }

subprojects {
  plugins.withId("com.ncorti.ktfmt.gradle") {
    ktfmtCheckAll.configure { dependsOn(tasks.named("ktfmtCheck")) }
    ktfmtFormatAll.configure { dependsOn(tasks.named("ktfmtFormat")) }

    // Two things the ktfmt plugin does not do for itself, both about `build/`.
    //
    // Its *scripts* tasks walk the project directory — `project.fileTree(projectDir)` filtered to
    // `**/*.kt` and `**/*.kts` — so the walk descends into `build/`, where every other ktfmt task
    // writes per-file results under `build/tmp/<task>/<uuid>/` and deletes the directory when it
    // finishes. A walk that meets a deletion fails the task with "Could not read path", which is
    // how CI lost `ktfmtCheckScripts` on a file under `ktfmtCheckKmpCommonMain`'s temp directory.
    // Nothing under `build/` is authored, so nothing under it is worth walking.
    //
    // And because those temp directories are state a task cleans up after itself, no two ktfmt
    // tasks may run at once — see `ktfmtLane` above. Everything else in the build stays parallel.
    tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
      exclude("build/**")
      usesService(ktfmtLane)
    }
  }
}

// ---------------------------------------------------------------------------

// The release wiring (the derived Maven publish set, `printPublishTasks`, the published-POM
// coordinate check) lives in `root-tasks.gradle.kts`, not here. The lanes and ktfmt aggregates
// above stay: they match tasks by Kotlin and ktfmt plugin types, which a script applied with
// `apply(from = ...)` cannot see. This file is a shared build input to
// `.github/scripts/maven-publish-plan.sh` -- the plugins and source generators above reach the
// published modules, so a change to it publishes all of them -- while those tasks decide which
// tasks run, and build nothing.
apply(from = "root-tasks.gradle.kts")

// One compile-time choice for the editor and the MCP adapter that hosts it.
// No environment, URL or request parameter can turn a released build's feature set on.
val remoteComposeAuthoring = providers.gradleProperty("uiBuilderRemoteCompose").orElse("false").map {
  require(it == "true" || it == "false") { "uiBuilderRemoteCompose must be true or false" }
  it.toBooleanStrict()
}

// `generateMcpBuildFeatures` stayed behind with `:mcp`, which is the server's module. The flag is
// still one compile-time choice; it is now made in two builds, and a release pairs them.
listOf(
  Triple(
    "generateUiBuilderBuildFeatures",
    "ee.schimke.composeai.uibuilder.export",
    "UiBuilderBuildFeatures",
  ),
).forEach { (taskName, packageName, objectName) ->
  tasks.register(taskName) {
    val enabled = remoteComposeAuthoring
    val output = layout.buildDirectory.dir("generated/$taskName")
    inputs.property("uiBuilderRemoteCompose", enabled)
    outputs.dir(output)
    doLast {
      val directory = output.get().asFile.apply { mkdirs() }
      directory.resolve("$objectName.kt").writeText(
        "package $packageName\n\n" +
          "/** Build-time feature selection. Enable with -PuiBuilderRemoteCompose=true. */\n" +
          "object $objectName {\n  const val remoteCompose: Boolean = ${enabled.get()}\n}\n"
      )
    }
  }
}
