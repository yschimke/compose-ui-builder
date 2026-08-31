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
    outputModuleName.set("generatedJetcaster")
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.materialIconsExtended)
      @Suppress("DEPRECATION") implementation(compose.ui)
      implementation(project(":ui-builder-artwork"))
    }
  }
}

tasks.named("ktfmtFormatKmpWasmJsMain") {
  mustRunAfter(":ui-builder:generateJetcasterComposeFixture")
}

project(":ui-builder").tasks.named("generateJetcasterComposeFixture") {
  finalizedBy(tasks.named("ktfmtFormatKmpWasmJsMain"))
}

tasks.named("check") { dependsOn(":ui-builder:checkJetcasterComposeFixture") }

tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the capability-generated Jetcaster Compose/Wasm fixture."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.buildDirectory.dir("kotlin-multiplatform-resources/aggregated-resources/wasmJs"))
  from(layout.projectDirectory.dir("src/wasmJsMain/resources"))
  from(rootProject.layout.projectDirectory.dir("wasm-ui/src/wasmJsMain/resources")) {
    include("js-joda.esm.js")
  }
  into(layout.buildDirectory.dir("wasmDist"))
}
