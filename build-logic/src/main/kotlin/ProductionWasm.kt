import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

// Which production Wasm executable the distribution tasks package: after Binaryen (`optimized/`),
// or the production link alone (`kotlin/`). Both are dead-code eliminated; Binaryen only shrinks
// and speeds up what survives (61 MB to 28.8 MB for `:ui-builder`), and it is the slow half: ~6
// minutes for `:ui-builder` and ~8.5 for `:ui-builder-renderer` on a CI runner, at a 5.6 GB peak.
//
// On by default, so every local build, `main` and every release ship the optimised bytes.
// `-PuiBuilder.wasmOpt=false` is for pull-request CI, which proves the archives assemble and
// verify without paying for an optimisation pass whose output only `main` publishes.

/** Whether `-PuiBuilder.wasmOpt` leaves Binaryen on; anything but `false` does. */
val Project.wasmOptEnabled: Boolean
  get() = providers.gradleProperty("uiBuilder.wasmOpt").orNull?.toBoolean() ?: true

/** The task that produces [productionWasmDir]. */
val Project.productionWasmTaskName: String
  get() =
    if (wasmOptEnabled) "compileProductionExecutableKotlinWasmJsOptimize"
    else "compileProductionExecutableKotlinWasmJs"

/** The production executable to package: `optimized/` after Binaryen, `kotlin/` without it. */
val Project.productionWasmDir: Provider<Directory>
  get() {
    val variant = if (wasmOptEnabled) "optimized" else "kotlin"
    return layout.buildDirectory.dir("compileSync/wasmJs/main/productionExecutable/$variant")
  }
