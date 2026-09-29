package ee.schimke.composeai.buildlogic

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.io.File
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure

/**
 * The artifact id a module publishes under: its project path, flattened, behind the repository's
 * prefix.
 *
 * `:ui-builder-runtime` -> `compose-preview-ui-builder-runtime`. Derived rather than declared
 * because two things have to agree about it and neither can see the other: the POM a module
 * publishes, and the constraint the BOM writes for that module. The daemon repository states the
 * same rule for the same reason, and pins it with a test.
 *
 * The prefix is `compose-preview-`, not `compose-ui-builder-`, and that is not a tidiness
 * question: these coordinates were released up to 3.24.0 from compose-preview-server under exactly
 * these names, and its version catalog names them today. A repository moving does not get to
 * rename the artifacts it already shipped.
 */
internal fun Project.publishedArtifactId(): String =
  "compose-preview-" + path.removePrefix(":").replace(':', '-')

/**
 * The version this build publishes [artifactId] at (or the tag itself, with no id): the release tag when there is one, otherwise
 * the next patch as a snapshot.
 *
 * `PLUGIN_VERSION` is what the release workflow exports. Without it - every local build and every
 * CI run that is not a release - the version is derived from `.release-please-manifest.json`, so a
 * developer's `publishToMavenLocal` cannot collide with a released coordinate.
 *
 * On a PLANNED release (`-Pcomposeai.publishSet`, written by `maven-publish-plan.sh`) a module the
 * plan skips carries the version it last published at, read from `publishing-manifest.json`, so a
 * POM never names a sibling version that was never uploaded. That resolution lives in
 * [PublishedVersions] and is applied here, for the modules, and by [resolvedPublishedVersion], for
 * the BOM's constraints, so the two cannot disagree.
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
 * Everything a published POM carries beyond its coordinates, in one place for the library modules
 * and the platform alike.
 *
 * Signing is conditional on the version: a snapshot is not signed, which is what lets the
 * external-consumer gate stage the whole publish set into a local repository with no key and no
 * Maven Central credentials. A release is signed, and fails loudly without a key rather than
 * publishing something unverifiable.
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
