# Exported Remote Kotlin, compiled

The Remote Kotlin export tests (`RemoteDrawingExportTest`, `RemoteDrawingGradientExportTest`,
`RemoteClockTemplatesTest` and the others that write under `ui-builder-export/build/draw-proof` and
`build/remote-call-proof`) assert on strings. This project compiles those same files against the
released AndroidX libraries a consumer of the export builds them with, so a constructor, overload or
import the emitter assumes is checked by kotlinc rather than by reading the libraries' sources.

```shell
./gradlew :ui-builder-export:jvmTest
ANDROID_HOME=/path/to/android-sdk ./gradlew -p experiments/remote-export-compile compileDebugKotlin
```

The versions default to the ones remote-m3-catalog, the builder's Remote consumer, pins; override
them with `-PcomposeRemote=…`, `-PwearComposeRemote=…` or `-PglanceWear=…` to try another release.
Several proof files are one widget exported with one property changed, so each file is compiled in a
package of its own (`proof.drawproof.<file>`).

CI runs it in the `ui-builder-remote-compose` job, after the export tests that write the sources.
Android dependencies stay in this standalone build, outside the production module graph.
