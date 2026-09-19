plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

val uiBuilderJava = JavaLanguageVersion.of(libs.versions.java.ui.builder.get().toInt())

kotlin {
  jvmToolchain(uiBuilderJava.asInt())
  jvm()

  sourceSets {
    getByName("jvmMain") {
      resources.srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
    }
    jvmMain.dependencies {
      implementation(project(":ui-builder"))
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.core)
    }
  }
}

compose.desktop { application { mainClass = "ee.schimke.composeai.uibuilder.desktop.MainKt" } }
