# UI builder web artifact

This packaging-only module turns the standalone `:ui-builder` Compose/Wasm output into the
immutable `compose-preview-ui-builder-web` archive. `:server` consumes its dedicated Gradle variant
instead of reaching into the frontend project's task graph or output directory.

The archive is the extraction seam: a future independently released builder can replace the
project dependency with the published coordinate without changing server distribution assembly.
It contributes nothing to the server runtime classpath.

“Immutable” means the server consumes one versioned archive and never a mutable frontend output
directory. It is not a reproducible-build claim: the current Kotlin/Wasm development compiler can
change the generated Wasm, import-object module, and source map across otherwise identical clean
compilations. Release provenance must therefore record the published archive's content hash.
