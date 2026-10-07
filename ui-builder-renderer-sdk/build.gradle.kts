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
      @Suppress("DEPRECATION") api(compose.runtime)
      @Suppress("DEPRECATION") api(compose.ui)
      implementation(libs.kotlinx.serialization.json)
    }
    // Every platform gets the icon metadata. Only the JVM gets the `ImageVector` tables, and with
    // them material-icons-extended: the browser draws the same vectors from data it fetches
    // (`MaterialIconData`), so ~11,000 icon builders stay out of every editor tab's Wasm.
    val iconSources =
      rootProject.tasks.named<GenerateMaterialIconUiSources>("generateMaterialIconUiSources")
    commonMain { kotlin.srcDir(iconSources.flatMap { it.outputDirectory }) }
    jvmMain { kotlin.srcDir(iconSources.flatMap { it.vectorOutputDirectory }) }
    jvmMain.dependencies { @Suppress("DEPRECATION") implementation(compose.materialIconsExtended) }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

// The browser's Material icons, as data: `MaterialIconData` files written by running the JVM's
// compiled icon builders through `MaterialIconDataGenerator`. The editor's and the renderer
// runtime's Wasm bundles copy the directory to `icons/` beside their module.
val generateMaterialIconData =
  tasks.register<JavaExec>("generateMaterialIconData") {
    group = "code generation"
    description = "Writes the browser's Material icon vectors from the compiled JVM builders."
    val main = kotlin.jvm().compilations.getByName("main")
    classpath(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("ee.schimke.composeai.uibuilder.renderer.sdk.MaterialIconDataGenerator")
    javaLauncher.set(
      javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.server.get().toInt()))
      }
    )
    val output = layout.buildDirectory.dir("generated/materialIconData")
    outputs.dir(output)
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
  }

// `MaterialIconCatalogTest` holds every icon the catalog derives from its key to the inventory
// read from material-icons-extended, which is what the key list was generated from.
tasks.named<Test>("jvmTest") {
  val inventory =
    rootProject.tasks
      .named<GenerateMaterialIconInventory>("generateMaterialIconInventory")
      .flatMap { it.output }
  inputs
    .file(inventory)
    .withPropertyName("materialIconInventory")
    .withPathSensitivity(PathSensitivity.NONE)
  jvmArgumentProviders.add(
    CommandLineArgumentProvider {
      listOf("-DmaterialIconInventory=${inventory.get().asFile.absolutePath}")
    }
  )
}
