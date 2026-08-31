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
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.materialIconsExtended)
      @Suppress("DEPRECATION") implementation(compose.ui)
    }
  }
}

tasks.register<Sync>("wasmFrontendDist") {
  description = "Assemble the independent Jetcaster Discover Compose/Wasm reference."
  group = "distribution"
  dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
  dependsOn("wasmJsProcessResources")
  from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
  from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
    include("skiko.mjs", "skiko.wasm")
  }
  from(layout.projectDirectory.dir("../wasm-ui/src/wasmJsMain/resources")) {
    include("js-joda.esm.js")
  }
  from(layout.projectDirectory.dir("src/wasmJsMain/resources"))
  into(layout.buildDirectory.dir("wasmDist"))
}
