import ee.schimke.composeai.buildlogic.resolvedPublishedVersion

plugins { id("composeai.maven-publishing-platform") }

// The BOM for everything this repository publishes to Maven Central, so consumers keep the
// coordinates in step structurally (a mismatch between runtime and export would make browser and
// service exports differ):
//
//     implementation(platform("ee.schimke.composeai:compose-preview-ui-builder-bom:<version>"))
//     implementation("ee.schimke.composeai:compose-preview-ui-builder-runtime")
//     implementation("ee.schimke.composeai:compose-preview-ui-builder-export")
//
// Constraints are derived, never listed: `settings.gradle.kts` collects projects applying
// `composeai.maven-publishing`, and artifact ids come from `publishedArtifactId`, as the POMs do.
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
      .forEach { artifactId ->
        // On a planned release a coordinate the plan skipped stays at the version it last
        // published at; naming the tag here would promise an artifact nobody uploaded.
        api(
          "ee.schimke.composeai:$artifactId:" +
            resolvedPublishedVersion(artifactId, project.version.toString())
        )
      }
  }
}

composeAiPlatformPublishing {
  coordinates(
    artifactId = "compose-preview-ui-builder-bom",
    displayName = "Compose UI Builder — Bill of Materials",
    description =
      "Version constraints for every Compose UI Builder artifact on Maven Central, so a host " +
        "aligns the design service, the export projection and the render bundle with one " +
        "coordinate. The web editor ships as a GitHub release asset and is not constrained here.",
  )
  inceptionYear.set("2026")
}
