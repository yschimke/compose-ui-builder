plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

/**
 * The JVM hosting layer for the editor: offline and remote sessions, the packaged catalogs, design
 * files, in-process export and the device-grant flow.
 *
 * It lived inside `:ui-builder-desktop`, the desktop *application*, so the IntelliJ plugin had to
 * depend on an app module — `main()`, window code and packaging configuration included — to get at
 * a session. The desktop app and the plugin now both depend on this library, and each keeps only
 * what is its own: a window and a File menu, or a tool window and an editor provider.
 *
 * No `compose.desktop.currentOs` here: the IntelliJ plugin runs on the IDE's own Skiko, and a
 * native runtime on this library's classpath is one the plugin would have to exclude again.
 */
kotlin {
  jvmToolchain(libs.versions.java.ui.builder.get().toInt())
  jvm()

  sourceSets {
    getByName("jvmMain") {
      resources.srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
    }
    jvmMain.dependencies {
      // OfflineUiBuilderSessionView exposes UiBuilderChrome so embedding hosts can supply native
      // chrome.
      api(project(":ui-builder"))
      @Suppress("DEPRECATION") implementation(compose.material3)
      implementation(libs.kotlinx.coroutines.core)
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      // The export tests draw through Skia.
      @Suppress("DEPRECATION") implementation(compose.desktop.currentOs)
    }
  }
}
