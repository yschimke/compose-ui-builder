import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink

plugins {
  base
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ktfmt) apply false
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
    ":ui-builder-runtime:check",
    ":ui-builder-renderer:check",
    ":ui-builder-web:check",
    ":server:check",
    ":ui-builder:check",
    ":ui-builder-generated-jetcaster:check",
    ":ui-builder-reference-jetcaster:check",
    ":usage-source-psi:check",
    ":wasm-ui:check",
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

subprojects {
  tasks.withType<KotlinJsIrLink>().configureEach { usesService(wasmLinkLane) }
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
  }
}

// ---------------------------------------------------------------------------
// No Maven set: this repository does not publish to Maven Central.
// ---------------------------------------------------------------------------
//
// What it ships are the GitHub release assets — `compose-preview-server-<v>.tar.gz`,
// `compose-preview-mcp-<v>.tar.gz` and `compose-preview-ui-builder-web-<v>.zip`, built by
// `:server:distTar`, `:mcp:distTar` and `:ui-builder-web:webArchive`. `compose-preview serve`,
// `browse`, `ui-builder` and `mcp serve` launch those; nothing links this build's classes.
//
// Six modules used to publish, and five of them only because the sixth's POM named them. A project
// dependency reaches a published POM as a coordinate, so `:server` depending on
// `:ui-builder-export` meant `:ui-builder-export` had to be on Central too — which is how 3.1.0
// shipped `compose-preview-server:ui-builder-export-jvm:unspecified` and 3.3.0 through 3.8.0 shipped
// a `compose-preview-ui-builder-runtime` POM naming an artifact nobody had uploaded. Six releases
// nobody could resolve, for a transitive nobody wanted.
//
// With no POM there is no coordinate to dangle, so the machinery those breakages produced —
// `publishReleaseArtifacts`, `printPublishedProjectPaths`, `CheckPublishedPomCoordinates` and the
// external-consumer gate that staged the derived set into a local repository — is gone with it. The
// modules keep their `archivesName`, which names the distribution archives and always did.
//
// The one consumer this cost anything is compose-ai-tools, whose `:cli` compiled two wire-drift
// tests against `compose-preview-serve`. Those tests launch the distribution now
// (compose-ai-tools#5436), which is the artifact `serve` runs anyway.

// One compile-time choice for the editor/server and the independently published MCP adapter.
// No environment, URL or request parameter can turn a released build's feature set on.
val remoteComposeAuthoring = providers.gradleProperty("uiBuilderRemoteCompose").orElse("false").map {
  require(it == "true" || it == "false") { "uiBuilderRemoteCompose must be true or false" }
  it.toBooleanStrict()
}
listOf(
  Triple("generateUiBuilderBuildFeatures", "ee.schimke.composeai.uibuilder", "UiBuilderBuildFeatures"),
  Triple("generateMcpBuildFeatures", "ee.schimke.composeai.mcp", "McpBuildFeatures"),
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
