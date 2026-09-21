plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// A catalog's pinned composite build substitutes this source coordinate. The SDK is not published:
// only the catalog's verified renderer ZIP crosses into delivery.
group = "ee.schimke.composeai"

ktfmt { googleStyle() }

kotlin {
  jvmToolchain(libs.versions.java.server.get().toInt())
  jvm()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      api(project(":ui-builder-export"))
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.materialIconsExtended)
      @Suppress("DEPRECATION") api(compose.runtime)
      @Suppress("DEPRECATION") api(compose.ui)
      implementation(libs.kotlinx.serialization.json)
    }
    commonMain { kotlin.srcDir(rootProject.tasks.named("generateMaterialIconUiSources")) }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}
