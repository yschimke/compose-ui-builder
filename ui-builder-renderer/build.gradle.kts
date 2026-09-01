import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

val rendererRuntimeId =
  providers.gradleProperty("uiBuilderRendererRuntimeId").orElse("m3-2026.09-protocol1")

kotlin {
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
    outputModuleName.set("uiBuilderRenderer")
    binaries.executable()
  }

  sourceSets {
    commonMain.dependencies {
      implementation(project(":ui-builder"))
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.ui)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

val runtimeAssets =
  tasks.register<Sync>("runtimeAssets") {
    dependsOn("wasmJsDevelopmentExecutableCompileSync", "processSkikoRuntimeForKWasm")
    dependsOn("wasmJsProcessResources")
    from(layout.buildDirectory.dir("compileSync/wasmJs/main/developmentExecutable/kotlin"))
    from(layout.buildDirectory.dir("compose/skiko-runtime-processed-wasmjs")) {
      include("skiko.mjs", "skiko.wasm")
    }
    from(layout.buildDirectory.dir("kotlin-multiplatform-resources/aggregated-resources/wasmJs"))
    from(layout.projectDirectory.dir("src/wasmJsMain/resources")) { include("index.html") }
    from(rootProject.layout.projectDirectory.dir("wasm-ui/src/wasmJsMain/resources")) {
      include("js-joda.esm.js")
    }
    from(rootProject.layout.projectDirectory.dir("assets/rc-fonts")) {
      include("*.ttf", "fonts.json", "*OFL.txt", "LICENSE.txt")
      into("fonts")
    }
    into(layout.buildDirectory.dir("runtimeAssets"))
  }

abstract class AssembleRendererRuntime : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val assetsDirectory: DirectoryProperty

  @get:Input abstract val runtimeId: Property<String>

  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @get:Inject abstract val fileSystemOperations: FileSystemOperations

  @TaskAction
  fun assemble() {
    val source = assetsDirectory.get().asFile
    val output = outputDirectory.get().asFile
    fileSystemOperations.sync {
      from(source)
      into(output)
    }
    val digest = MessageDigest.getInstance("SHA-256")
    output
      .walkTopDown()
      .filter(File::isFile)
      .map { it.relativeTo(output).invariantSeparatorsPath to it.readBytes() }
      .sortedBy { it.first }
      .forEach { (path, bytes) ->
        digest.update(path.encodeToByteArray())
        digest.update(0)
        digest.update(bytes.size.toString().encodeToByteArray())
        digest.update(0)
        digest.update(bytes)
      }
    val integrity = digest.digest().joinToString("") { "%02x".format(it) }
    output
      .resolve("runtime-manifest.json")
      .writeText(
        """{"schema":"compose-ui-builder-runtime/v1","runtimeId":"${runtimeId.get()}","protocolVersion":1,"entrypoint":"index.html","integritySha256":"$integrity"}"""
      )
  }
}

tasks.register<AssembleRendererRuntime>("wasmRendererDist") {
  description = "Assemble the renderer-only CMP/Wasm runtime with an integrity manifest."
  group = "distribution"
  dependsOn(runtimeAssets)
  assetsDirectory.set(layout.buildDirectory.dir("runtimeAssets"))
  runtimeId.set(rendererRuntimeId)
  outputDirectory.set(layout.buildDirectory.dir("wasmRendererDist"))
}

val rendererArchive =
  tasks.register<Zip>("rendererArchive") {
    description = "Package the renderer-only immutable CMP/Wasm runtime."
    group = "distribution"
    dependsOn("wasmRendererDist")
    from(layout.buildDirectory.dir("wasmRendererDist"))
    archiveBaseName.set("compose-preview-ui-builder-renderer")
    archiveVersion.set(rendererRuntimeId)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }

val rendererDistribution =
  configurations.create("rendererDistribution") {
    description = "Renderer-only CMP/Wasm runtime archive."
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named("distribution"))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("ui-builder-renderer"))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named("ui-builder-renderer"))
    }
    outgoing.artifact(rendererArchive)
  }

abstract class VerifyRendererRuntime : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val archiveFile: RegularFileProperty

  @get:Input abstract val expectedRuntimeId: Property<String>

  @TaskAction
  fun verify() {
    ZipFile(archiveFile.get().asFile).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toSet()
      val required =
        setOf(
          "runtime-manifest.json",
          "index.html",
          "uiBuilderRenderer.mjs",
          "uiBuilderRenderer.wasm",
          "skiko.mjs",
          "skiko.wasm",
        )
      check(names.containsAll(required)) { "renderer archive is missing ${required - names}" }
      check(names.none { it.startsWith('/') || it.contains("../") || '\\' in it }) {
        "renderer archive contains an unsafe path"
      }
      val manifest = zip.getInputStream(zip.getEntry("runtime-manifest.json")).reader().readText()
      check(manifest.contains("\"runtimeId\":\"${expectedRuntimeId.get()}\""))
      check(manifest.contains("\"protocolVersion\":1"))
      check(manifest.contains(Regex("\"integritySha256\":\"[a-f0-9]{64}\"")))
      val wasm =
        zip
          .getInputStream(zip.getEntry("uiBuilderRenderer.wasm"))
          .readBytes()
          .toString(Charsets.ISO_8859_1)
      listOf("Compose UI Builder", "Component catalog search", "Local session").forEach { marker ->
        check(marker !in wasm) { "renderer archive contains editor-only marker '$marker'" }
      }
    }
  }
}

val verifyRendererRuntime =
  tasks.register<VerifyRendererRuntime>("verifyRendererRuntime") {
    dependsOn(rendererArchive)
    archiveFile.set(rendererArchive.flatMap { it.archiveFile })
    expectedRuntimeId.set(rendererRuntimeId)
  }

tasks.named("check") { dependsOn("wasmJsTest", verifyRendererRuntime) }
