plugins {
  id("com.android.library") version "9.5.3"
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

android {
  namespace = "proof.selection"
  compileSdk = 37
  defaultConfig { minSdk = 26 }
  buildFeatures { compose = true }
  testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
  jvmToolchain(21)
}

// `-Pcomposeai.remoteCompose=snapshot` (see settings.gradle.kts) resolves Remote Compose from one
// androidx.dev build. The export writes alpha24 spellings, which only that lane carries until
// 1.0.0-alpha24 is released. TODO(alpha24): move the release version to 1.0.0-alpha24.
val snapshotLane =
  providers.gradleProperty("composeai.remoteCompose").orNull?.trim()?.lowercase() == "snapshot"
val composeRemote = if (snapshotLane) "1.0.0-SNAPSHOT" else "1.0.0-alpha23"

dependencies {
  implementation("androidx.compose.remote:remote-creation-compose:$composeRemote")
  if (snapshotLane) {
    implementation("androidx.compose.remote:remote-creation-expression:$composeRemote")
  }
  testImplementation("androidx.compose.remote:remote-player-view:$composeRemote")
  testImplementation("androidx.compose.remote:remote-core:$composeRemote")
  testImplementation("androidx.compose.remote:remote-player-core:$composeRemote")
  testImplementation("androidx.test:core:1.7.0")
  testImplementation("org.robolectric:robolectric:4.18.1")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
  testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
  testLogging {
    events("passed", "failed", "skipped")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

androidComponents {
  onVariants { variant ->
    val productionBoundActions = providers.gradleProperty("boundActionProductionProof").orNull == "true"
    if (productionBoundActions || providers.gradleProperty("boundActionProof").orNull == "true") {
      variant.sources.kotlin?.addStaticSourceDirectory(
        if (productionBoundActions) "build/bound-action-production-source/remote"
        else "build/bound-action-source/remote"
      )
      variant.hostTests.values.forEach { test ->
        test.sources.kotlin?.addStaticSourceDirectory("src/boundActionTest/kotlin")
      }
    }
    variant.sources.kotlin?.addStaticSourceDirectory(
      "../../ui-builder-export/build/remote-state-selection"
    )
    variant.sources.kotlin?.addStaticSourceDirectory(
      if (providers.gradleProperty("scopedProjectionProof").isPresent)
        "build/scoped-projection-source/remote"
      else "build/repetition-source/remote"
    )
  }
}
