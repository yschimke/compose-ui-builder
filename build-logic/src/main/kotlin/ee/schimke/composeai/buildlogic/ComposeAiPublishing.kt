package ee.schimke.composeai.buildlogic

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.io.File
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure

/**
 * The artifact id a module publishes under: its flattened project path behind the repository's
 * prefix (`:ui-builder-runtime` -> `compose-preview-ui-builder-runtime`). Derived so the POM and
 * the BOM constraint agree. The `compose-preview-` prefix keeps the names these coordinates were
 * already released under.
 */
internal fun Project.publishedArtifactId(): String =
  "compose-preview-" + path.removePrefix(":").replace(':', '-')

/**
 * The version this build publishes [artifactId] at (or the tag itself, with no id): the release
 * tag when there is one, otherwise the next patch as a snapshot.
 *
 * `PLUGIN_VERSION` comes from the release workflow; otherwise the version derives from
 * `.release-please-manifest.json`, so local `publishToMavenLocal` cannot collide with a release.
 * On a planned release (`-Pcomposeai.publishSet`), skipped modules keep their last published
 * version from `publishing-manifest.json` via [PublishedVersions], shared with
 * [resolvedPublishedVersion] for the BOM.
 */
internal fun Project.publishedVersion(artifactId: String? = null): String {
  val tag = providers.environmentVariable("PLUGIN_VERSION").orNull?.takeIf(String::isNotBlank)
  return when {
    tag == null -> nextPatchSnapshotVersion()
    // The BOM passes no id: it publishes at the tag whenever it publishes at all.
    artifactId == null -> tag
    else -> resolvedPublishedVersion(artifactId, tag)
  }
}

/** [PublishedVersions.resolve] over this build's `composeai.publishSet` and manifest file. */
fun Project.resolvedPublishedVersion(artifactId: String, tagVersion: String): String {
  val publishSet =
    PublishedVersions.parsePublishSet(providers.gradleProperty(PUBLISH_SET_PROPERTY).orNull)
  // Read only when it can matter: a full release, and every local build, has no manifest to read.
  val manifest =
    if (publishSet == null || artifactId in publishSet) ""
    else
      rootDir.resolve(PUBLISH_MANIFEST).takeIf(File::isFile)?.readText()
        ?: error(
          "'$artifactId' is not in the publish set but $PUBLISH_MANIFEST does not exist in " +
            "$rootDir. maven-publish-plan.sh --write-manifest writes it; without it there is no " +
            "version a skipped module can safely carry."
        )
  return PublishedVersions.resolve(artifactId, tagVersion, publishSet, manifest)
}

/** The Gradle property carrying the modules a planned release publishes. */
const val PUBLISH_SET_PROPERTY = "composeai.publishSet"

/** The file, at the repository root, recording the version each coordinate is published at. */
const val PUBLISH_MANIFEST = "publishing-manifest.json"

private fun Project.nextPatchSnapshotVersion(): String {
  val manifest =
    generateSequence(rootDir) { it.parentFile }
      .map { it.resolve(".release-please-manifest.json") }
      .firstOrNull(File::isFile)
      ?: error("Could not find .release-please-manifest.json from $rootDir")
  val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest.readText())!!.groupValues[1]
  val (major, minor, patch) = current.split(".").map(String::toInt)
  return "$major.$minor.${patch + 1}-SNAPSHOT"
}

/**
 * Everything a published POM carries beyond its coordinates, for library modules and the platform.
 * Snapshots are unsigned (so the external-consumer gate can stage locally); releases are signed and
 * fail without a key.
 */
internal fun Project.configureComposeAiPublication(
  artifactId: String,
  displayName: String,
  artifactDescription: String,
  inceptionYear: Property<String>,
) {
  extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    if (!version.toString().endsWith("SNAPSHOT")) {
      signAllPublications()
    }
    coordinates("ee.schimke.composeai", artifactId, version.toString())
    pom {
      name.set(displayName)
      description.set(artifactDescription)
      url.set("https://github.com/yschimke/compose-ui-builder")
      this.inceptionYear.set(inceptionYear)
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
        url.set("https://github.com/yschimke/compose-ui-builder")
        connection.set("scm:git:https://github.com/yschimke/compose-ui-builder.git")
        developerConnection.set("scm:git:ssh://git@github.com/yschimke/compose-ui-builder.git")
      }
    }
  }
}
