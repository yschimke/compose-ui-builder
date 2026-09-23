import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
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
      // OfflineUiBuilderApp exposes UiBuilderChrome so embedding hosts can supply native chrome.
      api(project(":ui-builder"))
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.desktop.currentOs)
      implementation(libs.kotlinx.coroutines.core)
    }
    jvmTest.dependencies { implementation(kotlin("test")) }
  }
}

compose.desktop {
  application {
    mainClass = "ee.schimke.composeai.uibuilder.desktop.MainKt"
    // The classes are compiled for the `java-ui-builder` toolchain, so they have to be launched
    // (by `run`) and bundled (by `packageDeb`) with it. Compose Desktop's default is the JVM
    // running Gradle, which on a JDK 17 daemon fails every `run` with UnsupportedClassVersionError.
    javaHome =
      javaToolchains
        .launcherFor { languageVersion = uiBuilderJava }
        .get()
        .metadata
        .installationPath
        .asFile
        .absolutePath
    nativeDistributions {
      targetFormats(TargetFormat.Deb)
      packageName = "compose-ui-builder-desktop"
      packageVersion = providers.environmentVariable("UI_BUILDER_DESKTOP_VERSION").orNull ?: "0.0.0"
    }
  }
}
