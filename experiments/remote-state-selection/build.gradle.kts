plugins {
  id("com.android.library") version "9.4.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
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

dependencies {
  implementation("androidx.compose.remote:remote-creation-compose:1.0.0-alpha19")
  testImplementation("androidx.compose.remote:remote-player-view:1.0.0-alpha19")
  testImplementation("androidx.compose.remote:remote-core:1.0.0-alpha19")
  testImplementation("androidx.compose.remote:remote-player-core:1.0.0-alpha19")
  testImplementation("androidx.test:core:1.7.0")
  testImplementation("org.robolectric:robolectric:4.17")
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
