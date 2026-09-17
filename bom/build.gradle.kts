plugins { id("composeai.maven-publishing-platform") }

// The BOM for everything this repository publishes.
//
// Four coordinates on one version line, and a consumer wanting three of them has to name three
// versions and keep them in step. compose-preview-server does exactly that today — four entries
// in its version catalog behind one `composeai-ui-builder` ref — which works precisely because a
// human keeps them equal. Importing this platform makes that structural instead:
//
//     implementation(platform("ee.schimke.composeai:compose-preview-ui-builder-bom:<version>"))
//     implementation("ee.schimke.composeai:compose-preview-ui-builder-runtime")
//     implementation("ee.schimke.composeai:compose-preview-ui-builder-export")
//
// A mismatch here is not a resolution error: `:ui-builder-runtime` and `:ui-builder-export` share
// the screen-model generator and disagree about a design's projection, so the symptom is an export
// that differs between the browser and the service. That is the failure this platform prevents.
//
// The constraints are DERIVED, never listed. `settings.gradle.kts` collects every project path
// whose build script applies `composeai.maven-publishing` and hands them over as a system property;
// the artifact id comes from `publishedArtifactId`, the same function the publishing plugin uses to
// name the POM, so the set the BOM promises and the set that publishes cannot drift apart. That is
// the whole lesson of compose-preview-server's 3.3.0-3.8.0 breakage, applied one layer up.
val publishedProjectPaths =
  providers
    .systemProperty("composeai.publishedProjectPaths")
    .get()
    .split(",")
    .filter(String::isNotBlank)

dependencies {
  constraints {
    publishedProjectPaths
      .map { path -> "compose-preview-" + path.removePrefix(":").replace(':', '-') }
      .sorted()
      .forEach { artifactId -> api("ee.schimke.composeai:$artifactId:${project.version}") }
  }
}

composeAiPlatformPublishing {
  coordinates(
    artifactId = "compose-preview-ui-builder-bom",
    displayName = "Compose UI Builder — Bill of Materials",
    description =
      "Version constraints for every Compose UI Builder artifact, so a host aligns the design " +
        "service, the export projection and the packaged frontend with one coordinate.",
  )
  inceptionYear.set("2026")
}
