plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

/**
 * The JVM hosting layer for the editor: offline and remote sessions, packaged catalogs, design
 * files, in-process export and the device-grant flow, shared by the desktop app and the IntelliJ
 * plugin.
 *
 * No `compose.desktop.currentOs`: the plugin runs on the IDE's own Skiko.
 */
kotlin {
  jvmToolchain(libs.versions.java.ui.builder.get().toInt())
  jvm()

  sourceSets {
    getByName("jvmMain") {
      // The builder's own fixtures (the Jetcaster benchmark design, the foundation vocabulary) —
      // never a catalog: catalogs are read from where they publish (PublishedCatalogStore).
      resources.srcDir(rootProject.layout.projectDirectory.dir("docs/design/fixtures/ui-builder"))
    }
    jvmMain.dependencies {
      // OfflineUiBuilderSessionView exposes UiBuilderChrome so embedding hosts can supply native
      // chrome.
      api(project(":ui-builder"))
      implementation(libs.compose.material3)
      implementation(libs.kotlinx.coroutines.core)
      // PublishedUiBuilderCatalog: the offline host composes each catalog from what it publishes,
      // exactly as a preview server does. Not the render bundle — that is the frontend's own
      // packaged previews, which a host of the editor neither needs nor may carry.
      implementation(project(":ui-builder-runtime")) {
        exclude(group = "ee.schimke.composeai", module = "ui-builder-render-bundle")
      }
    }
    jvmTest.dependencies {
      implementation(kotlin("test"))
      // The export tests draw through Skia.
      @Suppress("DEPRECATION") implementation(compose.desktop.currentOs)
    }
  }
}

// The hosts read catalogs from where they publish; their tests must not reach the network, so
// they read the cutover captures (one delivery commit per catalog) through the same store.
tasks.withType<Test>().configureEach {
  systemProperty(
    "compose.uiBuilder.catalogDir",
    rootProject.file("ui-builder-runtime/src/test/resources/catalog-cutover").absolutePath,
  )
}
