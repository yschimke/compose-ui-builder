pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

// Which line the three Remote Compose groups (`androidx.compose.remote`,
// `androidx.wear.compose.remote`, `androidx.glance.wear`) resolve from: `release` (default, the
// versions in build.gradle.kts) or `snapshot` (`-Pcomposeai.remoteCompose=snapshot`), the
// androidx-main build named by `androidxSnapshotBuildId`. The three move together: they share
// `remote-creation*`.
//
// The exporter writes the alpha24 spellings (`valueChangeAction`, `remember { MutableRemoteInt(…) }`,
// …), which no released line carries yet, so `verify.sh` runs this proof on the snapshot lane.
// TODO(alpha24): once 1.0.0-alpha24 is on google(), move the release version in build.gradle.kts
// to it and run `verify.sh` on the release lane again.
val remoteComposeLine =
  providers.gradleProperty("composeai.remoteCompose").orElse("release").get().trim().lowercase()

require(remoteComposeLine == "release" || remoteComposeLine == "snapshot") {
  "composeai.remoteCompose must be 'release' or 'snapshot', was '$remoteComposeLine'"
}

// Build ids age out of androidx.dev after a few weeks; if artifacts 404, pick a fresh one from
// https://androidx.dev/snapshots/builds.
val androidxSnapshotBuildId =
  providers.gradleProperty("androidxSnapshotBuildId").orElse("16942710").get().trim()

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    // One build id, group-scoped and snapshots-only, so the trio cannot skew and nothing else can
    // drift onto an unreviewed snapshot.
    if (remoteComposeLine == "snapshot") {
      maven("https://androidx.dev/snapshots/builds/$androidxSnapshotBuildId/artifacts/repository") {
        name = "androidxSnapshots"
        content {
          includeGroupByRegex("androidx\\.compose\\.remote.*")
          includeGroupByRegex("androidx\\.wear\\.compose\\.remote.*")
          includeGroupByRegex("androidx\\.glance\\.wear.*")
        }
        mavenContent { snapshotsOnly() }
      }
    }
  }
}

rootProject.name = "remote-state-selection-proof"
