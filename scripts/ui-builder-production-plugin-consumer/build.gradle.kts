plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  id("ee.schimke.compose-ui-builder")
}

ktfmt { googleStyle() }

kotlin { jvmToolchain(libs.versions.java.server.get().toInt()) }

dependencies {
  implementation(libs.compose.material3)
  implementation(libs.material.icons.extended)
  implementation(compose.foundation)
  implementation(compose.ui)
  implementation(compose.desktop.currentOs)
  implementation(libs.compose.ui.tooling.preview)
  testImplementation(kotlin("test"))
  @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class) testImplementation(compose.uiTest)
}

// The plugin marker and its default generator both resolve from the staged Maven repository.
uiBuilderGeneration {
  sourceSets {
    create("main") {
      entries.addAll("src/main/ui/Library.uid", "src/main/ui/Queue.uid", "src/main/ui/DynamicLibrary.uid")
      records.from(
        rootProject.file("docs/design/fixtures/ui-builder/compose-foundation-components-v1.json"),
        rootProject.file("docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"),
      )
    }
  }
}
