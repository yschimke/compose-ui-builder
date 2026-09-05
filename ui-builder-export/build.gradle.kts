// `:ui-builder-export` — the projection from a saved design onto the screen the generator consumes.
//
// It exists because two very different callers need the same answer to one question — *what does
// this document mean?* — and until now only one of them could ask it. `:server` projects a saved
// `DesignDocumentV1` onto `ScreenDocument` and runs the real `ScreenGenerator`; the browser editor,
// which is wasm, could not reach that code at all and kept a hand-written emitter of its own. The
// two then disagreed about which designs export, which is the drift this module ends.
//
// **Kotlin Multiplatform** (jvm + wasmJs) for exactly that reason, and it is only possible because
// `ee.schimke.composeai:screen-model` publishes the generator for both targets. `preview-discovery`
// carries the same code for the JVM alone, which is why this could not be done before 1.77.0.
//
// **No Compose dependency.** This is projection and generation, not rendering: `:ui-builder` may
// depend on it without inverting anything, and `:server` may without pulling Compose UI onto a
// server classpath.
//
// **Published**, and it has to be. `:server` depends on it (`implementation(project(...))`), so
// `compose-preview-serve`'s POM names it; an unpublished project dependency is recorded there as
// `compose-preview-server:ui-builder-export-jvm:unspecified` — a coordinate nobody can resolve —
// and that is what shipped in 3.1.0, breaking resolution of `compose-preview-serve` for every
// consumer, this repository's own `:cli` wire-drift tests included. It publishes in lockstep with
// `:server` and `:ui-builder-runtime` for the same reason they do: one version line, named by the
// POM that depends on it.
plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.maven.publish)
}

group = "ee.schimke.composeai"

val publishedArtifactId = "compose-preview-ui-builder-export"

// Same derivation as `:server` and `:ui-builder-runtime` — `PLUGIN_VERSION` in CI, a patch-bumped
// SNAPSHOT off `.release-please-manifest.json` locally. Without it Gradle leaves `project.version`
// as `unspecified`, which is precisely the string that broke 3.1.0's POM.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set(publishedArtifactId) }

ktfmt { googleStyle() }

kotlin {
  // Pinned to `java-server`, and pinned *explicitly*. This module had no toolchain at all, so its
  // published JVM jar took whatever JVM happened to run Gradle — 17 on CI today, and silently
  // whatever a contributor's `JAVA_HOME` says. That is a published artifact on `compose-ai-tools`'
  // `:cli` compile classpath: the one place in this build where a drifting class-file version is
  // someone else's red build rather than ours. It is stated here so it cannot drift, and so that a
  // reader comparing this file with `:ui-builder`'s sees the line between the two floors.
  jvmToolchain(libs.versions.java.server.get().toInt())

  jvm()

  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      api(libs.composeai.screen.model)
      api(libs.composeai.ui.builder.protocol)
      implementation(libs.kotlinx.serialization.json)
    }
    commonTest.dependencies { implementation(kotlin("test")) }
  }
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  if (!project.version.toString().endsWith("SNAPSHOT")) signAllPublications()
  coordinates(group.toString(), publishedArtifactId, project.version.toString())
  pom {
    name.set("Compose Preview — UI Builder Export")
    description.set(
      "Projection from a saved UI-builder design onto the screen model the Compose generator " +
        "consumes, shared by the server and the browser editor so the two agree about what a " +
        "design exports."
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
