/**
 * `:ui-builder-server` — the UI builder's own HTTP host, deployed as its own container.
 *
 * It replaces compose-preview-server's `SERVE_ROLE=ui-builder` container one route group at a time
 * (compose-preview-server `docs/design/SERVER_UNBUNDLING.md`, step 3). It answers the deployable
 * endpoints itself (`/healthz`, `/readyz`, `/version`) and reverse-proxies every other request,
 * WebSockets included, to the legacy container named by `UI_BUILDER_UPSTREAM_URL`. A route group
 * moves here by being handled before the proxy, so each move is one pull request in this
 * repository, with no cross-repository flag day.
 *
 * Not published to Maven Central and not a seam: compose-preview-server never resolves it. It ships
 * as `ghcr.io/yschimke/compose-ui-builder-server` (`Dockerfile` beside this file).
 */
plugins {
  application
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ktfmt)
}

ktfmt { googleStyle() }

// The host runs where the export runs (Java 21, `java-ui-builder`) once export moves here; nothing
// compiles against it, so it has no consumer floor to respect.
kotlin { jvmToolchain(libs.versions.java.ui.builder.get().toInt()) }

application {
  mainClass.set("ee.schimke.composeai.uibuilder.server.UiBuilderServerMainKt")
  applicationName = "compose-ui-builder-server"
}

dependencies {
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(kotlin("test"))
}

// The release version, read by `/version`. The release workflow sets PLUGIN_VERSION from the tag;
// anything else is a development build.
val serverVersion = providers.environmentVariable("PLUGIN_VERSION").map { it.removePrefix("v") }

val generateVersionResource =
  tasks.register("generateVersionResource") {
    val out = layout.buildDirectory.dir("generated/version")
    val version = serverVersion.orElse("0.0.0-dev")
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
      val file = out.get().file("ui-builder-server.properties").asFile
      file.parentFile.mkdirs()
      file.writeText("version=${version.get()}\n")
    }
  }

sourceSets.main { resources.srcDir(generateVersionResource) }

// `build/install/compose-ui-builder-server`, which the image copies.
distributions { main { distributionBaseName.set("compose-ui-builder-server") } }
