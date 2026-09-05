pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  // Kotlin/Wasm adds the Node distribution as an Ivy repository when its setup task is realized.
  // The build scripts declare no repositories; project preference exists solely for that
  // plugin-owned toolchain repository.
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "compose-preview-server"

include(":ui-builder-runtime")
include(":server")
// The MCP server — `compose-preview mcp serve`. Moved here from compose-ai-tools because the layer
// rule places a module that needs an HTTP server in this repository (compose-ai-tools#5176); it
// consumes the layer-1 daemon/render-session coordinates it used to reach as projects.
include(":mcp")
include(":usage-source-psi")
include(":wasm-ui")
include(":ui-builder")
include(":ui-builder-export")
include(":ui-builder-renderer")
include(":ui-builder-web")
include(":ui-builder-artwork")
include(":ui-builder-reference-jetcaster")
include(":ui-builder-generated-jetcaster")
include(":native-catalog-m3")
include(":slot-preview-runtime")
