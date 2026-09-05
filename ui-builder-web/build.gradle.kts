import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import java.util.zip.ZipFile

plugins {
  `java-library`
  alias(libs.plugins.maven.publish)
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

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  if (!project.version.toString().endsWith("SNAPSHOT")) signAllPublications()
  coordinates(group.toString(), publishedArtifactId, project.version.toString())
  pom {
    name.set("Compose Preview — UI Builder Web")
    description.set("Immutable Compose/Wasm frontend archive for the Compose Preview UI builder.")
    url.set("https://github.com/yschimke/compose-preview-server")
    inceptionYear.set("2026")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("yschimke")
        name.set("Yuri Schimke")
        url.set("https://github.com/yschimke")
      }
    }
    scm {
      url.set("https://github.com/yschimke/compose-preview-server")
      connection.set("scm:git:https://github.com/yschimke/compose-preview-server.git")
      developerConnection.set("scm:git:ssh://git@github.com/yschimke/compose-preview-server.git")
    }
  }
}

abstract class VerifyUiBuilderWebPublication : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val archiveFile: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val moduleMetadataFile: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val pomFile: RegularFileProperty

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

    val metadata = moduleMetadataFile.get().asFile.readText()
    val archiveName = archive.name
    check(metadata.contains("\"org.gradle.category\": \"distribution\""))
    check(metadata.contains("\"org.gradle.libraryelements\": \"ui-builder-web\""))
    check(metadata.contains("\"org.gradle.usage\": \"ui-builder-web\""))
    check(metadata.contains("\"org.gradle.usage\": \"ui-builder-web-api\""))
    check(metadata.split("\"url\": \"$archiveName\"").size - 1 == 2) {
      "both published Gradle variants must resolve to $archiveName"
    }
    check(!metadata.contains("\"url\": \"${archiveName.removeSuffix(".zip")}.jar\"")) {
      "published Gradle metadata still exposes an empty primary JAR"
    }

    val pom = pomFile.get().asFile.readText()
    check(pom.contains("<packaging>zip</packaging>")) {
      "published Maven POM does not declare the frontend ZIP as its primary artifact"
    }
  }
}

val verifyUiBuilderWebPublication =
  tasks.register<VerifyUiBuilderWebPublication>("verifyUiBuilderWebPublication") {
    description = "Verify the frontend archive and its published Maven/Gradle artifact shape."
    group = "verification"
    dependsOn(
      webArchive,
      tasks.named("generateMetadataFileForMavenPublication"),
      tasks.named("generatePomFileForMavenPublication"),
    )
    archiveFile.set(webArchive.flatMap { it.archiveFile })
    moduleMetadataFile.set(layout.buildDirectory.file("publications/maven/module.json"))
    pomFile.set(layout.buildDirectory.file("publications/maven/pom-default.xml"))
  }

tasks.named("check") { dependsOn(verifyUiBuilderWebPublication) }
