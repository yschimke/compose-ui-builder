// The convention plugins (`composeai.maven-publishing`, `composeai.maven-publishing-platform`).
// An included build rather than `buildSrc`: `buildSrc` is rebuilt for every invocation of any
// task, and this one already carries `buildSrc` for the Material icon generators.
pluginManagement {
  includeBuild("build-logic")

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

    // ── The CMP Wear port, GROUP-FENCED ─────────────────────────────────────────────────────────
    // `ee.schimke.wearcmp:*` — Wear Compose Material 3 / Foundation compiled for Compose
    // Multiplatform, published from `yschimke/wear-m3-catalog`'s `wear-compose-cmp-maven` branch.
    // It publishes `jvm` and `wasmJs` variants, which are exactly `:ui-builder`'s two targets, and
    // it is what lets the canvas draw Wear components instead of renaming three of them to
    // Material 3 lookalikes. See `docs/design/UI_BUILDER_WEAR_SCREEN.md`.
    //
    // Fenced to the one group with `includeGroup` rather than a prefix guess, so this repository
    // can never satisfy a request for an `androidx.*` or `ee.schimke.composeai` artifact by
    // accident. The device-preview lane is unaffected: it renders through the native
    // `wear-m3-catalog` bundle against the genuine AndroidX AARs, and the port never reaches it.
    maven("https://raw.githubusercontent.com/yschimke/wear-m3-catalog/wear-compose-cmp-maven/") {
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
// ── Optional composite builds against sibling checkouts ───────────────────────────────────────
//
// This repository resolves its upstream — `ee.schimke.composeai:*` from compose-ai-tools, the
// preview daemon, the contracts line — as PUBLISHED COORDINATES, and that is the default because
// it is what CI and every fresh clone do. But the coordinates move under us constantly, and the
// two ways to test a change to one of them from here were both bad: publish it to Maven Central
// first, or run `scripts/stage-local-dependency.py` and rebuild the staged repository on every
// edit.
//
// `includeBuild` is the third way, and it is the one that makes an edit in a sibling checkout show
// up here on the next Gradle invocation with no publishing step at all. It is OPT-IN: naming no
// sibling resolves everything from Maven exactly as before.
//
//     ./gradlew check -PlocalBuilds=tools
//     ./gradlew check -PlocalBuilds=tools,daemon
//     ./gradlew check -PlocalBuilds=all
//
// Each sibling defaults to a checkout beside this one, and `-PlocalBuild.<name>=<path>` overrides
// that when yours lives somewhere else. A named sibling whose directory is missing is an error
// rather than a silent fall back to Maven: "I asked for my local tools and got the released one"
// is exactly the confusion this exists to remove.
//
// WHAT THIS DOES NOT DO: declare substitution rules. Gradle substitutes an included build's
// projects for external coordinates automatically when the group and module name match, which is
// the case for every upstream here — they publish the coordinates their projects are named after.
// A sibling whose artifactId differs from its project name needs an explicit
// `dependencySubstitution` rule, and the consumer that needs one should add it here rather than
// rename a project to suit a coordinate.
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

rootProject.name = "compose-ui-builder"

// ── The UI builder ─────────────────────────────────────────────────────────────────────────────
//
// Extracted from yschimke/compose-preview-server, where these modules were already a second
// project with an enforced boundary (`docs/design/UI_BUILDER_PROJECT_BOUNDARY.md`, which came with
// them). That boundary is now a repository boundary, and the four modules its table named as seams
// are what the server still consumes.

include(":ui-builder")

// Native Compose Desktop host for the editor. It is an in-project consumer of the editor's JVM
// target, not a server seam: it can run the offline protocol without an HTTP host.
include(":ui-builder-desktop")

include(":ui-builder-export")

include(":ui-builder-runtime")

include(":ui-builder-renderer")

// The catalog-facing source seam: protocol, inspection DTOs and the sandboxed Wasm host. Catalogs
// compile it from a pinned source checkout and ship only their self-contained renderer ZIP.
include(":ui-builder-renderer-sdk")

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

// The BOM. It is not a member of the set it constrains, which is why it applies the platform
// plugin rather than the library one, and why the scan below cannot match it.
include(":bom")

// ── The published set, derived ─────────────────────────────────────────────────────────────────
//
// Project paths that publish to Maven Central, handed to `:bom` as a closure-free system property.
//
// Read out of the build scripts rather than kept as a list here. The build file is where the
// decision to publish is actually made, so that is what this reads. A hand-kept list goes stale
// silently, and going stale means a BOM that omits a coordinate (a consumer pins it by hand and
// skews) or names one that was never published (resolution fails). compose-preview-server shipped
// six consecutive unresolvable releases from exactly that, with two hand-kept copies of one set.
//
// Matched with its closing quote (`composeai.maven-publishing")`) rather than as a bare substring:
// `composeai.maven-publishing-platform` starts with the same 26 characters, so a prefix match would
// pull `:bom` into the list of things the BOM constrains and it would constrain itself.
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
