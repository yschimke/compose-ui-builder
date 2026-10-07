plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    outputModuleName.set("jetcasterReference")
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      implementation(libs.compose.material3)
      // The real Material 3 adaptive scaffolds. The oracle has to draw `SupportingPaneScaffold`
      // itself for the same reason the builder does -- a `BoxWithConstraints` imitating one is what
      // let this app and the builder disagree by ~7.7% at expanded width after #788.
      implementation(libs.compose.material3.adaptive)
      implementation(libs.compose.material3.adaptive.layout)
      @Suppress("DEPRECATION") implementation(compose.materialIconsExtended)
      @Suppress("DEPRECATION") implementation(compose.ui)
      implementation(project(":ui-builder-artwork"))
    }
  }
}

// `-PuiBuilder.wasmOpt=false` skips Binaryen, as `ProductionWasm.kt` in build-logic does for the
// rest of the build. Spelled out here because this fixture also builds standalone, from its own
// `settings.gradle.kts`, which does not include build-logic.
val jetcasterWasmOpt = providers.gradleProperty("uiBuilder.wasmOpt").orNull?.toBoolean() ?: true
val jetcasterWasmTask =
  "compileProductionExecutableKotlinWasmJs" + if (jetcasterWasmOpt) "Optimize" else ""
val jetcasterWasmVariant = if (jetcasterWasmOpt) "optimized" else "kotlin"
val jetcasterWasmDir =
  layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/$jetcasterWasmVariant")

tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the independent Jetcaster Discover Compose/Wasm reference."
  group = "distribution"
  dependsOn(jetcasterWasmTask, "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  // The production executable after Binaryen, as `:ui-builder`'s `wasmFrontendDist` ships: the
  // development one is several times larger and slower to compile in the browser. `optimized/`, not
  // `kotlin/`, which is the production IR before Binaryen has run. Source maps stay behind.
  // `-PuiBuilder.wasmOpt=false` packages `kotlin/` instead; see `jetcasterWasmOpt` above.
  from(jetcasterWasmDir) { exclude("*.map") }
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.buildDirectory.dir("kotlin-multiplatform-resources/aggregated-resources/wasmJs"))
  from(layout.projectDirectory.dir("../assets/js-joda")) { include("js-joda.esm.js") }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources"))
  from(
    rootProject.layout.projectDirectory.dir(
      "ui-builder-artwork/src/commonMain/composeResources/files/artwork"
    )
  ) {
    include("manifest-v1.json")
    into("ui-builder-artwork/files/artwork")
  }
  into(layout.buildDirectory.dir("wasmDist"))
}
