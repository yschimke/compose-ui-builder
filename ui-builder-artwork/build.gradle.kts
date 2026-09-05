plugins {
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

ktfmt { googleStyle() }

kotlin {
  // `java-ui-builder`, for the same reason `:ui-builder` is: this module's JVM classes exist only
  // to be compiled into that module's preview and packed into the render bundle. Left at the
  // default they would be whatever JVM ran Gradle, which is the drift the two floors exist to
  // remove — and a 17 half of a 21 lane reads like a constraint when it is an accident.
  jvmToolchain(libs.versions.java.ui.builder.get().toInt())

  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }

  sourceSets {
    commonMain.dependencies {
      @Suppress("DEPRECATION") implementation(compose.runtime)
      @Suppress("DEPRECATION") implementation(compose.foundation)
      @Suppress("DEPRECATION") implementation(compose.ui)
      @Suppress("DEPRECATION") implementation(compose.components.resources)
    }
  }
}

compose.resources {
  publicResClass = true
  packageOfResClass = "ee.schimke.composeai.uibuilder.artwork.generated.resources"
}

val verifyOwnedArtwork =
  tasks.register<Exec>("verifyOwnedArtwork") {
    description = "Verify checked-in project-owned artwork bytes and manifest hashes."
    group = "verification"
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/generate-ui-builder-artwork.mjs", "--check")
  }

val verifyArtworkLanes =
  tasks.register<Exec>("verifyArtworkLanes") {
    description = "Verify every Jetcaster lane consumes the same offline artwork binding."
    group = "verification"
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/check-ui-builder-artwork-lanes.mjs")
  }

tasks.named("check") { dependsOn(verifyOwnedArtwork, verifyArtworkLanes) }
