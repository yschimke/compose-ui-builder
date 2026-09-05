import java.util.zip.ZipFile

plugins {
  `java-library`
  alias(libs.plugins.maven.publish)
}

group = "ee.schimke.composeai"

val publishedArtifactId = "compose-preview-ui-builder-render-bundle"

/**
 * The packaged UI-builder render bundle, as an artifact of its own.
 *
 * ## Why this is not a resource in `:ui-builder-runtime`
 *
 * It was one, and the edge pointed the wrong way. `:ui-builder-runtime` is server-side and
 * published; the bundle is a *frontend* build output — a polyglot PNG whose embedded ZIP carries
 * `:ui-builder`'s compiled JVM previews and their minimal classpath. A published server jar
 * carrying the frontend's class files had two costs. It made `:ui-builder-runtime` depend on
 * `:ui-builder`'s build, which a repository split cannot carry across a boundary
 * ([#346](https://github.com/yschimke/compose-preview-server/issues/346)); and it pinned the
 * frontend's JVM target to the server's, because those class files ship inside the server's
 * artifact and every consumer of it has to be able to load them.
 *
 * This module owns the packaging instead. It sits on the frontend side of that line, exactly as
 * `:ui-builder-web` does for `wasmDist`, so `:ui-builder-runtime` reaches the bundle through a
 * coordinate — the one thing that survives a repository boundary.
 *
 * ## Why a jar and not a distribution archive
 *
 * `:ui-builder-web` publishes a Zip with distribution attributes because its consumer unpacks it
 * into a served directory. This one's consumer is `PackagedUiBuilderRenderBundle.copyTo`, which
 * reads `getResourceAsStream`. A classpath lookup does not care *which* jar answers it, so
 * shipping the bundle as an ordinary resource in a sibling jar leaves that public API — and every
 * caller of it — byte-for-byte unchanged, while moving the bytes out of the runtime's own artifact.
 *
 * ## Why there is no source set
 *
 * Deliberately none, and it is the point: a module that compiles nothing has no JVM target to
 * inherit or impose. The frontend can move its own toolchain without this artifact acquiring an
 * opinion about the server's.
 */
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

base { archivesName.set(publishedArtifactId) }

/**
 * The resource path is the contract.
 *
 * `PackagedUiBuilderRenderBundle.RESOURCE` names this exact path and is public API of
 * `compose-preview-ui-builder-runtime`. Moving the bytes between artifacts is invisible to a
 * consumer precisely as long as the path does not move with them, so the two are pinned together
 * by `UiBuilderRenderBundleResourcePathTest` in that module rather than by hope.
 */
val bundleResourceDirectory = "ee/schimke/composeai/uibuilder/renderer"

val bundleResourceName = "ui-builder-renderer.bundle.png"

// The same shape `:ui-builder-runtime` used when it owned these bytes, moved rather than rewritten:
// a `processResources` copy out of `:ui-builder`'s build directory, which this build already proves
// works under the configuration cache. This module has no other resource and no source at all, so
// the copy is the whole of its jar.
tasks.processResources {
  // Locals, not the script properties directly: a `rename` transformer is serialized into the
  // configuration cache, and a lambda reading a script-level `val` captures the whole script object
  // with it — which the cache refuses. Copying the two strings here captures two strings.
  val resourceDirectory = bundleResourceDirectory
  val resourceName = bundleResourceName
  dependsOn(project(":ui-builder").tasks.named("composePreviewBundle"))
  from(project(":ui-builder").layout.buildDirectory.file("compose-previews/bundle.png")) {
    into(resourceDirectory)
    rename { resourceName }
  }
}

/**
 * A jar that silently lost its only reason to exist is worse than a build failure.
 *
 * `composePreviewBundle` is a render-producing task in another module; a configuration change that
 * left it out, or a rename of the file it writes, would otherwise publish an empty artifact and
 * fail at a consumer's runtime with "packaged UI-builder renderer bundle is missing".
 */
val verifyRenderBundlePackaged =
  tasks.register("verifyRenderBundlePackaged") {
    description = "Fail the build if the published jar does not carry the render bundle."
    group = "verification"
    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    // Locals for the same reason the copy above takes them: a `doLast` action is cached, and
    // reading `publishedArtifactId` straight from the script would drag the script into the cache.
    val entry = "$bundleResourceDirectory/$bundleResourceName"
    val artifactId = publishedArtifactId
    inputs.file(jarFile)
    doLast {
      ZipFile(jarFile.get().asFile).use { jar ->
        val packaged = checkNotNull(jar.getEntry(entry)) { "$artifactId does not carry $entry" }
        check(packaged.size > 0) { "$artifactId carries an empty $entry" }
      }
    }
  }

tasks.named("check") { dependsOn(verifyRenderBundlePackaged) }

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  if (!project.version.toString().endsWith("SNAPSHOT")) signAllPublications()
  coordinates(group.toString(), publishedArtifactId, project.version.toString())
  pom {
    name.set("Compose Preview — UI Builder Render Bundle")
    description.set(
      "The packaged Compose preview the UI-builder runtime renders a saved design through."
    )
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
