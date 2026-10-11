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

// `wear-m3-capabilities-v1.json` is absent: it is a golden owned by `SynthesisedCatalogGoldenTest`,
// and a second writer here produced different bytes. `m3-catalog-capabilities-v1.json` has no
// golden and stays.
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

// One Kotlin/Wasm executable links at a time.
//
// `kotlin.daemon.jvmargs=-Xmx6g` is sized for one ir2wasm pass, but the build shares one Kotlin
// daemon across many parallel link tasks, which ran out of memory together. A shared build service
// with `maxParallelUsages = 1` serialises just these tasks. Matched by task type
// (`KotlinJsIrLink`), not name, so a rename cannot silently stop the matching.
abstract class WasmLinkLane : BuildService<BuildServiceParameters.None>

val wasmLinkLane =
  gradle.sharedServices.registerIfAbsent("wasmLinkLane", WasmLinkLane::class) {
    maxParallelUsages.set(1)
  }

// The Binaryen `wasm-opt` pass (a native process peaking ~5.6 GB) shares the lane too, so it never
// overlaps a link holding the Kotlin daemon's heap. With `kotlin.daemon.jvmargs` returning idle
// heap (see `gradle.properties`), peak memory stays within CI runners.
subprojects {
  tasks.withType<KotlinJsIrLink>().configureEach { usesService(wasmLinkLane) }
  tasks.withType<BinaryenExec>().configureEach { usesService(wasmLinkLane) }
}

// Gradle 9 validates this task's non-optional input before its action can treat an absent Yarn
// lock as "no dependencies"; skip when there is no lock (e.g. a fresh CI checkout).
tasks.withType<YarnLockStoreTask>().configureEach {
  onlyIf("the generated Yarn lock exists") { inputFile.asFile.get().isFile }
}

// ktfmt tasks write and delete per-task temp directories under `build/tmp/`, so they must not run
// concurrently; the rest of the build stays parallel.
abstract class KtfmtLane : BuildService<BuildServiceParameters.None>

val ktfmtLane =
  gradle.sharedServices.registerIfAbsent("ktfmtLane", KtfmtLane::class) {
    maxParallelUsages.set(1)
  }

// The formatting aggregates — derived, never listed: a module formats exactly when it applies the
// ktfmt plugin, so a new module cannot be forgotten.
val ktfmtCheckAll = tasks.register("ktfmtCheckAll") { group = "verification" }

val ktfmtFormatAll = tasks.register("ktfmtFormat") { group = "formatting" }

subprojects {
  plugins.withId("com.ncorti.ktfmt.gradle") {
    ktfmtCheckAll.configure { dependsOn(tasks.named("ktfmtCheck")) }
    ktfmtFormatAll.configure { dependsOn(tasks.named("ktfmtFormat")) }

    // The ktfmt *scripts* tasks walk `projectDir`, including `build/`, where other ktfmt tasks
// create
    // and delete temp directories ("Could not read path"). Nothing under `build/` is authored, so
    // exclude it. Also see `ktfmtLane` above.
    tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
      exclude("build/**")
      usesService(ktfmtLane)
    }
  }
}

// ---------------------------------------------------------------------------

// The release wiring lives in `root-tasks.gradle.kts`, so editing it does not republish every
// module (this file is a shared build input to `.github/scripts/maven-publish-plan.sh`). The lanes
// and ktfmt aggregates stay here because they match plugin task types a script applied with
// `apply(from = ...)` cannot see.
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
