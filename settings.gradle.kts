pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

// Development only: selected upstream publications compiled by stage-local-dependency.py.
// Normal builds still use the released catalog. The manifest includes every staged KMP variant.
val localDependencyManifest = providers.gradleProperty("localDependencies").orNull?.let { file(it) }
val localDependencyProperties = java.util.Properties()

localDependencyManifest?.let { manifest ->
  require(manifest.isFile) { "Local dependency manifest does not exist: $manifest" }
  manifest.reader().use(localDependencyProperties::load)
}

val localDependencyVersions =
  localDependencyProperties.getProperty("coordinates")?.split(",")?.associate { coordinate ->
    val parts = coordinate.split(":")
    require(parts.size == 3 && parts.all { it.isNotBlank() }) {
      "Invalid local dependency coordinate: $coordinate"
    }
    "${parts[0]}:${parts[1]}" to parts[2]
  } ?: emptyMap()

if (localDependencyManifest != null) {
  require(localDependencyVersions.isNotEmpty()) { "Local dependency manifest has no coordinates" }
  val modelVersion = localDependencyVersions["ee.schimke.composeai:screen-model"]
  val discoveryVersion = localDependencyVersions["ee.schimke.composeai:preview-discovery"]
  require(modelVersion == discoveryVersion) {
    "Stage screen-model and preview-discovery together: both contain the shared generator classes"
  }
}

dependencyResolutionManagement {
  // Kotlin/Wasm adds the Node distribution as an Ivy repository when its setup task is realized.
  // The build scripts declare no repositories; project preference exists solely for that
  // plugin-owned toolchain repository.
  repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
  repositories {
    localDependencyManifest?.let { manifest ->
      val repository =
        manifest.parentFile.resolve(
          requireNotNull(localDependencyProperties.getProperty("repository")) {
            "Local dependency manifest has no repository"
          }
        )
      require(repository.isDirectory) { "Local dependency repository does not exist: $repository" }
      exclusiveContent {
        forRepository {
          maven {
            name = "uiBuilderLocal"
            url = repository.toURI()
          }
        }
        filter {
          localDependencyVersions.keys.forEach { module ->
            val (group, artifact) = module.split(":")
            includeModule(group, artifact)
          }
        }
      }
    }
    google()
    mavenCentral()
  }
}

if (localDependencyVersions.isNotEmpty()) {
  logger.lifecycle("Using local dependency publications: ${localDependencyVersions.keys.sorted()}")
  gradle.beforeProject {
    configurations.configureEach {
      resolutionStrategy.eachDependency {
        localDependencyVersions["${requested.group}:${requested.name}"]?.let { version ->
          useVersion(version)
          because("Explicit localDependencies manifest")
        }
      }
    }
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

// The packaged render bundle the runtime materializes, as its own artifact rather than a resource
// inside `:ui-builder-runtime`'s jar. It is a *frontend* build output — the polyglot carries
// `:ui-builder`'s compiled JVM previews — and a published server-side jar was the wrong place to
// keep it: the edge pointed the wrong way across the layer line and pinned the frontend's JVM
// target to the server's. See yschimke/compose-preview-server#346.
include(":ui-builder-render-bundle")

include(":ui-builder-artwork")

include(":ui-builder-reference-jetcaster")

include(":ui-builder-generated-jetcaster")

include(":native-catalog-m3")
