import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import java.util.zip.ZipFile

plugins {
  `java-library`
}

group = "ee.schimke.composeai"

val publishedArtifactId = "compose-preview-ui-builder-web"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val current =
        Regex(""""\.":\s*"([^"]+)"""")
          .find(rootDir.resolve(".release-please-manifest.json").readText())!!
          .groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

val webArchive =
  tasks.register<Zip>("webArchive") {
    description = "Package the standalone UI-builder Wasm application as an immutable archive."
    group = "distribution"
    dependsOn(project(":ui-builder").tasks.named("wasmFrontendDist"))
    from(project(":ui-builder").layout.buildDirectory.dir("wasmDist"))
    archiveBaseName.set(publishedArtifactId)
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }

tasks.named<Jar>("jar") { enabled = false }

configurations.named("apiElements") {
  attributes {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("distribution"))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("ui-builder-web"))
    attribute(Usage.USAGE_ATTRIBUTE, objects.named("ui-builder-web-api"))
  }
  outgoing.artifacts.clear()
  outgoing.artifact(webArchive)
}

configurations.named("runtimeElements") {
  description = "Immutable Compose/Wasm UI-builder frontend archive."
  attributes {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("distribution"))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("ui-builder-web"))
    attribute(Usage.USAGE_ATTRIBUTE, objects.named("ui-builder-web"))
  }
  outgoing.artifacts.clear()
  outgoing.artifact(webArchive)
}

abstract class VerifyUiBuilderWebArchive : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val archiveFile: RegularFileProperty

  @TaskAction
  fun verify() {
    val archive = archiveFile.get().asFile
    ZipFile(archive).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toList()
      val required =
        setOf(
          "index.html",
          "uiBuilder.mjs",
          "uiBuilder.wasm",
          "skiko.mjs",
          "skiko.wasm",
          "m3-catalog-capabilities-v1.json",
          "jetcaster-discover-operations-v1.json",
          "fonts/fonts.json",
        )
      val missing = required - names.toSet()
      check(missing.isEmpty()) { "UI-builder web archive is missing: ${missing.sorted()}" }
      check(names.size == names.toSet().size) { "UI-builder web archive contains duplicate paths" }
      val unsafe =
        names.filter { name ->
          name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." }
        }
      check(unsafe.isEmpty()) { "UI-builder web archive contains unsafe paths: $unsafe" }
    }
  }
}

val verifyUiBuilderWebArchive =
  tasks.register<VerifyUiBuilderWebArchive>("verifyUiBuilderWebArchive") {
    description = "Verify the frontend archive carries a complete, safely-named bundle."
    group = "verification"
    dependsOn(webArchive)
    archiveFile.set(webArchive.flatMap { it.archiveFile })
  }

tasks.named("check") { dependsOn(verifyUiBuilderWebArchive) }
