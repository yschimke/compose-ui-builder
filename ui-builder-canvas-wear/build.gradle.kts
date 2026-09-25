plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.compose.preview)
}

ktfmt { googleStyle() }

// The Wear and Remote Compose Material 3 canvas, as an add-on to `:ui-builder`'s built-in
// Material 3 one.
//
// `:ui-builder` draws Material 3 itself and nothing else: a catalog it does not build in reaches
// the
// browser editor through the renderer runtime that catalog publishes (`wear-m3-catalog` publishes
// `wear-m3` and `remote-m3`'s). Hosts that cannot load a Wasm runtime — the desktop app, the
// IntelliJ plugin, and the server's render bundle — draw those catalogs in-process instead, and
// this
// module is how: it registers a `UiBuilderCanvasAddon` that `:ui-builder` finds on the JVM
// classpath.
// It is also the only module that links the Wear Compose Multiplatform port, which is what keeps
// the
// web editor and the Material 3 renderer runtime free of it.
val uiBuilderJava = JavaLanguageVersion.of(libs.versions.java.ui.builder.get().toInt())

kotlin {
  jvmToolchain(uiBuilderJava.asInt())

  jvm()
  // `binaries.executable()` for the same reason as `:ui-builder-artwork`: Compose's
  // `checkComposeUiTestConfigurationForWasmJs` fails a wasmJs target without one (CMP-4906).
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    binaries.executable()
  }

  compilerOptions { optIn.add("ee.schimke.composeai.uibuilder.canvas.UiBuilderCanvasAddonApi") }

  sourceSets {
    commonMain.dependencies {
      api(project(":ui-builder"))
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.material3)
      @Suppress("DEPRECATION") implementation(compose.ui)
      // Wear Compose, for real, on the canvas. See `docs/design/UI_BUILDER_WEAR_SCREEN.md`: this is
      // the CMP port rather than `androidx.wear.compose`, because that one is an Android AAR with
      // no JVM or `wasmJs` variant to resolve. The port keeps the upstream package names.
      implementation(libs.wearcmp.compose.material3)
      implementation(libs.wearcmp.compose.foundation)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
    getByName("jvmMain").dependencies {
      implementation(compose.desktop.currentOs)
      implementation(libs.compose.ui.tooling.preview)
      implementation(project.dependencies.platform(libs.composeai.daemon.bom))
      implementation(libs.composeai.data.preview.overrides.runtime)
      // The artwork keys the Wear widget sample previews name.
      implementation(project(":ui-builder-artwork"))
    }
    getByName("jvmTest").dependencies {
      @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class) @Suppress("DEPRECATION")
      implementation(compose.uiTest)
      implementation(compose.desktop.currentOs)
    }
  }
}

tasks.withType<Test>().configureEach {
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(uiBuilderJava) })
}

// The server's render bundle: `ProductionUiBuilderPreview`, which draws any served catalog — the
// built-in Material 3 one and this module's Wear and Remote Compose ones — with `editorOverlay =
// false`. It moved here from `:ui-builder` with the Wear canvas, because the bundle's classes are
// traced from this entry point and `:ui-builder` alone no longer reaches them.
//
// In `afterEvaluate` for the reason `:ui-builder`'s used to be: the plugin registers the task in an
// `afterEvaluate` of its own, and a `configureEach` added earlier is silently overridden.
afterEvaluate {
  tasks.named<ee.schimke.composeai.plugin.BundlePreviewTask>("composePreviewBundle") {
    previewIds.set(
      listOf(
        "ee.schimke.composeai.uibuilder.ProductionUiBuilderPreviewKt.ProductionUiBuilderPreview"
      )
    )
  }
}
