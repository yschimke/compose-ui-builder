import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

// Which production Wasm executable the distribution tasks package: after Binaryen (`optimized/`),
// or the production link alone (`kotlin/`). Binaryen shrinks and speeds up the output but is slow
// and memory-hungry. On by default (local builds, `main`, releases); `-PuiBuilder.wasmOpt=false`
// lets pull-request CI skip it.

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
