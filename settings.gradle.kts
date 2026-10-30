// The convention plugins and the build's own task types, as an included build rather than
// `buildSrc` (which rebuilds every invocation and invalidates every build script).
pluginManagement {
  includeBuild("build-logic")

  repositories {
    // Google Maven first, scoped to its own groups, so AGP and androidx never reach the plugin
    // portal or Central: under load those answer a miss with HTTP 429, which fails the build,
    // instead of the 404 that would have moved on to Google.
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    gradlePluginPortal()
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
  // Project preference exists solely for the Node Ivy repository Kotlin/Wasm adds; the build
  // scripts declare no repositories.
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

    // The CMP Wear port (`ee.schimke.wearcmp:*`): Wear Compose Material 3 / Foundation compiled for
    // Compose Multiplatform (`jvm` and `wasmJs`), so the canvas draws real Wear components. See
    // `docs/design/UI_BUILDER_WEAR_SCREEN.md`. Fenced with `includeGroup` so it can never satisfy
    // an
    // `androidx.*` or `ee.schimke.composeai` request. The device-preview lane uses genuine AARs.
    maven("https://raw.githubusercontent.com/yschimke/wear-m3-catalog-out/wear-compose-cmp-maven/") {
      name = "wearComposeCmpPort"
      content { includeGroup("ee.schimke.wearcmp") }
    }
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
// Optional composite builds against sibling checkouts. Upstreams resolve as published coordinates
// by default; opting in with `includeBuild` picks up sibling edits with no publishing step:
//
//     ./gradlew check -PlocalBuilds=tools
//     ./gradlew check -PlocalBuilds=tools,daemon
//     ./gradlew check -PlocalBuilds=all
//
// Siblings default to a checkout beside this one; `-PlocalBuild.<name>=<path>` overrides. A named
// sibling that is missing is an error, never a silent fall back to Maven. Gradle substitutes
// matching coordinates automatically; a sibling whose artifactId differs from its project name
// needs an explicit `dependencySubstitution` rule here.
val localBuildRoots =
  mapOf(
    "tools" to "../compose-ai-tools",
    "daemon" to "../compose-preview-daemon",
    "contracts" to "../compose-preview-contracts",
  )

val requestedLocalBuilds =
  providers.gradleProperty("localBuilds").orNull
    ?.split(",")
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    .orEmpty()
    .flatMap { requested ->
      if (requested == "all") localBuildRoots.keys else listOf(requested)
    }
    .distinct()

requestedLocalBuilds.forEach { name ->
  val default =
    requireNotNull(localBuildRoots[name]) {
      "Unknown local build '$name'. Known siblings: ${localBuildRoots.keys.sorted()}, or 'all'."
    }
  val directory =
    file(providers.gradleProperty("localBuild.$name").orNull ?: default).canonicalFile
  require(
    directory.resolve("settings.gradle.kts").isFile || directory.resolve("settings.gradle").isFile
  ) {
    "-PlocalBuilds names '$name' but $directory is not a Gradle build. Clone it there, or point " +
      "at your checkout with -PlocalBuild.$name=<path>."
  }
  logger.lifecycle("Composite build: $name -> $directory")
  includeBuild(directory)
}

// BuildFetch remote build cache beside the local one. Off unless a token resolves; writes only
// from trusted CI (ON_CI=true on pushes to `main`), PRs read. `-Pcomposeai.remoteCache=off` skips
// it.
val onCi = providers.environmentVariable("ON_CI").orElse("false").get().toBoolean()

// Non-blank view of one env var or Gradle property: an unset secret that CI still exports as an
// empty string neither shadows a later fallback nor enables the cache with an empty credential.
val nonBlank = { source: Provider<String> -> source.map { it.trim() }.filter { it.isNotEmpty() } }
val cacheToken =
  nonBlank(providers.environmentVariable("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN"))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_COMPOSEAI_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.environmentVariable("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orElse(nonBlank(providers.gradleProperty("BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN")))
    .orNull
val remoteCacheDisabled =
  providers.gradleProperty("composeai.remoteCache").orElse("on").get().trim().lowercase() == "off"

buildCache {
  local { isEnabled = true }
  remote<HttpBuildCache> {
    url = uri("https://cache.eu-central-a.buildfetch.com/8ESz2z/gradle/")
    credentials {
      username = "token-auth"
      password = cacheToken
    }
    isPush = onCi && !remoteCacheDisabled
    isEnabled = cacheToken != null && !remoteCacheDisabled
  }
}

rootProject.name = "compose-ui-builder"

// The UI builder modules. Their boundary is documented in
// `docs/design/UI_BUILDER_PROJECT_BOUNDARY.md`; the seams it names are what the server consumes.

include(":ui-builder")

// Native Compose Desktop host for the editor. It is an in-project consumer of the editor's JVM
// target, not a server seam: it can run the offline protocol without an HTTP host.
include(":ui-builder-desktop")

// The JVM hosting layer both native hosts share: sessions, packaged catalogs, design files,
// in-process export. A library, so the IntelliJ plugin depends on it rather than on the desktop
// app.
include(":ui-builder-host-jvm")

// The IntelliJ host lives in yschimke/compose-preview-ide and consumes a pinned source checkout.

include(":ui-builder-export")

// The sample documents tests and previews build from. Not published, and not a seam.
include(":ui-builder-export-fixtures")

include(":ui-builder-codegen-jvm")
include(":ui-builder-gradle-plugin")
include(":ui-builder-production-consumer")

include(":ui-builder-runtime")

include(":ui-builder-renderer")

// The catalog-facing source seam: protocol, inspection DTOs and the sandboxed Wasm host. Catalogs
// compile it from a pinned source checkout and ship only their self-contained renderer ZIP.
include(":ui-builder-renderer-sdk")

include(":ui-builder-web")

// The packaged render bundle as its own artifact: it is frontend build output, so it does not
// belong inside the server-side `:ui-builder-runtime` jar. A release asset, not on Central.
include(":ui-builder-render-bundle")

// The variable fonts the export's code generator reads, as a release asset rather than ~4 MB of
// every `compose-preview-ui-builder-export-jvm` upload.
include(":ui-builder-export-fonts")

include(":ui-builder-artwork")

include(":ui-builder-reference-jetcaster")

include(":ui-builder-generated-jetcaster")

// The BOM. It is not a member of the set it constrains, which is why it applies the platform
// plugin rather than the library one, and why the scan below cannot match it.
include(":bom")

// The published set, derived: project paths that publish to Maven Central, passed to `:bom` as a
// closure-free system property. Read from the build scripts, where the decision is made, so the
// BOM cannot drift. Matched with the closing quote so `composeai.maven-publishing-platform` (`:bom`
// itself) is not included.
val publishedProjectPaths = buildList {
  fun visit(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
    if (
      descriptor.buildFile.exists() &&
        descriptor.buildFile.readText().contains("composeai.maven-publishing\")")
    ) {
      add(descriptor.path)
    }
    descriptor.children.forEach(::visit)
  }
  rootProject.children.forEach(::visit)
}

System.setProperty("composeai.publishedProjectPaths", publishedProjectPaths.joinToString(","))
