# Exported Remote Kotlin, compiled

The Remote Kotlin export tests (`RemoteDrawingExportTest`, `RemoteDrawingGradientExportTest`,
`RemoteClockTemplatesTest` and the others that write under `ui-builder-export/build/draw-proof` and
`build/remote-call-proof`) assert on strings. This project compiles those same files against the
released AndroidX libraries a consumer of the export builds them with, so a constructor, overload or
import the emitter assumes is checked by kotlinc rather than by reading the libraries' sources.

```shell
./gradlew -PuiBuilderRemoteCompose=true :ui-builder-export:jvmTest
ANDROID_HOME=/path/to/android-sdk ./gradlew -p experiments/remote-export-compile \
  -Pcomposeai.remoteCompose=snapshot compileDebugKotlin
```

The exporter writes the Remote Compose **alpha24** API (`remember { MutableRemoteInt(…) }`,
`valueChangeAction`, `RemoteTextAlign`, `RemoteContentScale`, `RemoteRole`, `RemotePaintingStyle`,
`lerp` for colours, `RemoteAction`, …). Until 1.0.0-alpha24 is released that API exists only in
androidx.dev snapshot builds, so `-Pcomposeai.remoteCompose=snapshot` resolves all three Remote
Compose groups (`androidx.compose.remote`, `androidx.wear.compose.remote`, `androidx.glance.wear`)
as `1.0.0-SNAPSHOT` from the one build `androidxSnapshotBuildId` names (16942710 by default;
`-PandroidxSnapshotBuildId=…` to try another). That is the lane CI compiles on.

Without the flag the versions default to the ones remote-m3-catalog, the builder's Remote consumer,
pins (alpha21 / remote-material3 alpha13 / glance-wear alpha20), which do not compile the current
export; override them with `-PcomposeRemote=…`, `-PwearComposeRemote=…` or `-PglanceWear=…` to try
another release. When alpha24 ships, the release defaults move to it and CI drops the flag.
Several proof files are one widget exported with one property changed, so each file is compiled in a
package of its own (`proof.drawproof.<file>`). `build/remote-state-selection`, the state-selection
exports, is compiled too.

CI runs it in the `ui-builder-remote-compose` job, after the export tests that write the sources.
Android dependencies stay in this standalone build, outside the production module graph.
