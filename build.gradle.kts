import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrLink
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnLockStoreTask

plugins {
  base
  // Task types for this build's scripts (the Material icon generators, `:ui-builder`'s fixture
  // tasks); see `BuildTasksPlugin`.
  id("composeai.build-tasks")
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.intellij.platform) apply false
}

val materialIconGeneratorClasspath =
  configurations.create("materialIconGeneratorClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
  }

dependencies {
  materialIconGeneratorClasspath(libs.material.icons.extended.desktop)
  materialIconGeneratorClasspath(libs.material.icons.core.desktop)
}

val generateMaterialIconInventory =
  tasks.register<GenerateMaterialIconInventory>("generateMaterialIconInventory") {
    group = "code generation"
    description = "Indexes every vector in the shipped Compose Material Icons artifact."
    iconClasspath.from(materialIconGeneratorClasspath)
    output.set(layout.buildDirectory.file("generated/materialIcons/material-icon-inventory.tsv"))
  }

val generateMaterialIconUiSources =
  tasks.register<GenerateMaterialIconUiSources>("generateMaterialIconUiSources") {
    group = "code generation"
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    outputDirectory.set(layout.buildDirectory.dir("generated/materialIcons/ui-builder"))
  }

val generateMaterialIconExportSource =
  tasks.register<GenerateMaterialIconExportSource>("generateMaterialIconExportSource") {
    group = "code generation"
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    outputDirectory.set(layout.buildDirectory.dir("generated/materialIcons/ui-builder-export"))
  }

val m3MaterialIconCatalogFixture =
  layout.projectDirectory.file("docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json")
val generateMaterialIconCatalogFixture =
  tasks.register<GenerateMaterialIconCatalogFixture>("generateMaterialIconCatalogFixture") {
    inventory.set(generateMaterialIconInventory.flatMap { it.output })
    catalog.set(m3MaterialIconCatalogFixture)
    output.set(
      layout.buildDirectory.file("generated/materialIcons/m3-catalog-capabilities-v1.json")
    )
  }

// `wear-m3-capabilities-v1.json` is deliberately absent here. It is a **golden**, owned by
// `SynthesisedCatalogGoldenTest`: `wearM3Catalog` is synthesised in Kotlin from the packaged
// Material 3 catalog, so the golden already carries the full icon list and regenerating it is what
// records an icon-catalog change. Patching the same file here as well gave it two writers that
// produce the same icons in different bytes — the allowlist spliced onto one line here, expanded
// one entry per line by the golden's `Json { prettyPrint = true }` — and `VerifyMatchingFile`
// compares bytes, so no content of the file could satisfy both. Every push since the icon catalog
// grew was red on whichever of the two ran last (#710).
//
// `m3-catalog-capabilities-v1.json` has no golden and stays here, which is what this task is for.
tasks.register<UpdateMaterialIconCatalogFixtures>("updateMaterialIconCatalogFixture") {
  group = "code generation"
  description = "Updates the m3 icon allowlist from the shipped Material Icons artifact."
  generatedM3.set(generateMaterialIconCatalogFixture.flatMap { it.output })
  checkedInM3.set(m3MaterialIconCatalogFixture)
}

val checkMaterialIconCatalogFixture =
  tasks.register<VerifyMatchingFile>("checkMaterialIconCatalogFixture") {
    checkedIn.set(m3MaterialIconCatalogFixture)
    expected.set(generateMaterialIconCatalogFixture.flatMap { it.output })
  }

tasks.named("check") { dependsOn(checkMaterialIconCatalogFixture) }

tasks.named("check") {
  group = "verification"
  dependsOn(
    ":ui-builder:check",
    ":ui-builder-desktop:check",
    ":ui-builder-intellij-plugin:check",
    ":ui-builder-artwork:check",
    ":ui-builder-export:check",
    ":ui-builder-generated-jetcaster:check",
    ":ui-builder-reference-jetcaster:check",
    ":ui-builder-render-bundle:check",
    ":ui-builder-renderer:check",
    ":ui-builder-renderer-sdk:check",
    ":ui-builder-runtime:check",
    ":ui-builder-web:check",
  )
}

// ---------------------------------------------------------------------------
// One Kotlin/Wasm executable links at a time.
// ---------------------------------------------------------------------------
//
// `kotlin.daemon.jvmargs=-Xmx6g` above is sized for ONE ir2wasm pass. There is one Kotlin daemon
// for the whole build, `org.gradle.parallel=true`, and twenty-six of these link tasks across seven
// modules — so `check` runs several of them inside that single 6 GB heap and it dies with "Not
// enough memory to run compilation".
//
// That is what took `main` red from #710 (the complete Material icon inventory) through #711's
// 4g -> 6g bump, which raised the ceiling without changing how many passes share it. The failure
// moved between `:ui-builder:compileTestDevelopmentExecutableKotlinWasmJs` and
// `:ui-builder-renderer:compileDevelopmentExecutableKotlinWasmJs` run to run — whichever pair
// happened to overlap, which is the signature of contention rather than of one task being too big.
//
// The evidence that a single pass does fit: the `visual-harness` job compiles four of these
// executables at the same 6 GB and passes, because it runs `--no-daemon --no-parallel
// --max-workers=1`. And `:ui-builder:compileTestDevelopmentExecutableKotlinWasmJs` — the one CI
// dies on — links in 5m13s at 6 GB on a 15 GB machine when nothing else shares the daemon.
//
// So the constraint is stated where it is true, rather than by serialising a whole CI job or by
// raising a number that has to be raised again the next time the icon set grows. A shared build
// service with `maxParallelUsages = 1` is Gradle's way to say "these tasks must not run
// concurrently with each other"; everything else in the build stays parallel.
//
// Matched by TASK TYPE, not by name. A name pattern is the same hand-kept list `ktfmtCheckAll`
// was, one rename away from silently matching nothing and letting the OOM back in with no test to
// notice — and there is no cheap test for "CI has enough memory". `KotlinJsIrLink` is the type
// Kotlin gives every executable link; if it is renamed the build stops compiling here instead.
abstract class WasmLinkLane : BuildService<BuildServiceParameters.None>

val wasmLinkLane =
  gradle.sharedServices.registerIfAbsent("wasmLinkLane", WasmLinkLane::class) {
    maxParallelUsages.set(1)
  }

subprojects {
  tasks.withType<KotlinJsIrLink>().configureEach { usesService(wasmLinkLane) }
}

// Kotlin registers this root task after the Wasm projects are configured. Its action already treats
// an absent lock as "no dependencies", but Gradle 9 validates the task's non-optional input first
// and fails before that action can make the decision. On a fresh CI checkout Yarn can legitimately
// produce no lock (for example while its cache is restored), so skip the store operation in that
// case. When Yarn does write the lock, this remains the normal task and copies it to kotlin-js-store.
tasks.withType<YarnLockStoreTask>().configureEach {
  onlyIf("the generated Yarn lock exists") { inputFile.asFile.get().isFile }
}

// The ktfmt tasks write per-file results under `build/tmp/<task>/<uuid>/` and delete the directory
// when they finish, so they are shared state a task cleans up after itself. The lane keeps two of
// them from running at once for the same reason `wasmLinkLane` exists above: the constraint is
// real, and the service is Gradle's way to state it without serialising the rest of the build.
abstract class KtfmtLane : BuildService<BuildServiceParameters.None>

val ktfmtLane =
  gradle.sharedServices.registerIfAbsent("ktfmtLane", KtfmtLane::class) {
    maxParallelUsages.set(1)
  }

// The formatting aggregates — DERIVED, never listed, for the reason the Maven set below is.
//
// They were hand-kept lists and had drifted by four modules: `mcp`, `native-catalog-m3`,
// `ui-builder-artwork` and `ui-builder-export`. `AGENTS.md` tells every contributor and every
// agent to run `ktfmtCheckAll` before committing, CI's `check` reaches those four anyway, so the
// gate that was supposed to save a round trip was the thing costing one — twice in one session on
// the same file. A list of modules that has to be edited when a module is added is not a gate,
// it is a reminder.
//
// Keyed on the ktfmt plugin rather than on the module list: a module formats if and only if it
// applies the plugin, which is the same fact from the only place that states it.
val ktfmtCheckAll = tasks.register("ktfmtCheckAll") { group = "verification" }

val ktfmtFormatAll = tasks.register("ktfmtFormat") { group = "formatting" }

subprojects {
  plugins.withId("com.ncorti.ktfmt.gradle") {
    ktfmtCheckAll.configure { dependsOn(tasks.named("ktfmtCheck")) }
    ktfmtFormatAll.configure { dependsOn(tasks.named("ktfmtFormat")) }

    // Two things the ktfmt plugin does not do for itself, both about `build/`.
    //
    // Its *scripts* tasks walk the project directory — `project.fileTree(projectDir)` filtered to
    // `**/*.kt` and `**/*.kts` — so the walk descends into `build/`, where every other ktfmt task
    // writes per-file results under `build/tmp/<task>/<uuid>/` and deletes the directory when it
    // finishes. A walk that meets a deletion fails the task with "Could not read path", which is
    // how CI lost `ktfmtCheckScripts` on a file under `ktfmtCheckKmpCommonMain`'s temp directory.
    // Nothing under `build/` is authored, so nothing under it is worth walking.
    //
    // And because those temp directories are state a task cleans up after itself, no two ktfmt
    // tasks may run at once — see `ktfmtLane` above. Everything else in the build stays parallel.
    tasks.withType<com.ncorti.ktfmt.gradle.tasks.KtfmtBaseTask>().configureEach {
      exclude("build/**")
      usesService(ktfmtLane)
    }
  }
}

// ---------------------------------------------------------------------------
// The release's Maven set — DERIVED, never listed.
// ---------------------------------------------------------------------------
//
// Restored from compose-preview-server at 3c074dd, which deleted it along with the coordinates in
// that repository's #794. It is reinstated rather than rewritten because it is the residue of two
// expensive failures, and a fresh implementation would have to rediscover both. The module names
// in the history below are that repository's; the shapes are this one's.
//
// What a release publishes to Maven Central used to be a list typed into
// `release.yml`'s `run:` line, and a SECOND list typed into
// `scripts/check-ui-builder-external-consumer.sh`. Two hand-maintained copies of one set, with
// nothing checking that they agreed — and they stopped agreeing.
//
// `:ui-builder-render-bundle` became an `api` dependency of `:ui-builder-runtime` in #346 and was
// added to the gate script's list. Nobody added it to `release.yml`. From 3.3.0 to 3.8.0 every
// release therefore published a `compose-preview-ui-builder-runtime` POM naming
// `compose-preview-ui-builder-render-bundle:<version>` at `compile` scope, an artifact that has
// never existed on Maven Central — so `compose-preview-serve` was unresolvable for six consecutive
// releases. It went unnoticed because compose-ai-tools pins 3.2.0, the last release before the
// break.
//
// `:server`'s `checkPublishedPomCoordinates` could not catch it. That task looks for the 3.1.0
// failure — a project with NO publishing configuration, which reaches the POM as `unspecified`.
// `:ui-builder-render-bundle` has a group, a version and coordinates, so its POM entry is
// well-formed in every way except that nothing ever uploaded it.
//
// Deriving the set closes both failures at once: a module is in the release exactly when it applies
// the publishing plugin, which is also exactly when it can reach a POM as a resolvable coordinate.
// Add the plugin and the release publishes it; don't, and `checkPublishedPomCoordinates` fails the
// build the moment it reaches a POM. There is no longer a list to forget.
val publishedProjectPaths: SetProperty<String> = objects.setProperty(String::class.java)

// The tripwires on the publish set, as their own task so something OTHER than the release can run
// them. They used to live inside `publishReleaseArtifacts`, where the only thing that ever executed
// them was the release itself - which is how a tripwire demanding `:ui-builder-web` survived to
// fail the 3.26.0 release, having never once run. `check-ui-builder-external-consumer.sh` runs this
// now, so the gate covers the assertions as well as the publishing.
val checkPublishSet =
  tasks.register("checkPublishSet") {
    group = "verification"
    description = "Verifies the derived Maven publish set before anything is uploaded."

    // A tripwire, not a second list. The derived set is only as good as the plugin detection above:
    // rename the plugin id, move publishing into a convention plugin, and the set silently empties
    // while the release job stays green and publishes nothing.
    val paths = publishedProjectPaths
    doFirst {
      val derived = paths.get()
      // The modules this release uploads to Central. NOT the same set as the four seams
      // `UI_BUILDER_PROJECT_BOUNDARY.md` names, and conflating the two is what broke the 3.26.0
      // release: `:ui-builder-web` is a seam, but it reaches compose-preview-server as a GitHub
      // release asset, never as a Central coordinate. Requiring it here demanded a module that must
      // never be in this set, so the check could only ever fail.
      val missing =
        listOf(
            ":ui-builder-runtime",
            ":ui-builder-export",
            ":ui-builder-render-bundle",
            ":bom",
          )
          .filterNot(derived::contains)
      check(missing.isEmpty()) {
        "The derived Maven publish set is missing ${missing.joinToString(", ")} - it resolved to " +
          "${derived.sorted()}. The set is every subproject applying the maven-publish plugin; if " +
          "publishing moved somewhere this no longer detects, fix the detection rather than " +
          "listing modules by hand."
      }

      // The inverse tripwire, and the reason the one above can safely shrink. `:ui-builder-web`
      // packages a ~40 MB Wasm frontend; applying the publishing plugin to it would put that on
      // Central under a version that can never be withdrawn. Its absence from this set is a
      // decision, so it is asserted rather than left to a comment in its build script.
      check(":ui-builder-web" !in derived) {
        "`:ui-builder-web` is in the Maven publish set. It ships as a GitHub release asset, not a " +
          "Central coordinate - see the ivy repository in compose-preview-server's " +
          "settings.gradle.kts. Remove the publishing plugin from it rather than relaxing this."
      }

      // The set is derived TWICE, and the two derivations have to agree.
      //
      // This one is what a plugin is applied to, resolved after configuration. `settings.gradle.kts`
      // derives the other by reading the build scripts as text, before any project is configured,
      // because `:bom` needs it at configuration time and a system property is the only closure-free
      // way to hand it over under Isolated Projects.
      //
      // Two derivations of one set is the shape of the failure this whole file is about, so they are
      // compared rather than trusted. They disagree the moment a module applies the plugin somewhere
      // the text scan cannot see it -- through another convention plugin, say -- and the symptom
      // would otherwise be a BOM quietly missing a coordinate it was supposed to constrain.
      //
      // `:bom` is the one legitimate difference: it applies the PLATFORM plugin, so it belongs to
      // the release set but not to the set the BOM constrains.
      val scanned =
        (System.getProperty("composeai.publishedProjectPaths") ?: "")
          .split(",")
          .filter(String::isNotBlank)
          .toSet()
      check(scanned == derived - ":bom") {
        "The release set and the BOM's set disagree. Applying the plugin says " +
          "${(derived - ":bom").sorted()}; reading the build scripts in settings.gradle.kts says " +
          "${scanned.sorted()}. One of them cannot see something the other can."
      }
    }
  }

val publishReleaseArtifacts =
  tasks.register("publishReleaseArtifacts") {
    group = "publishing"
    description = "Publishes every module of this release to Maven Central."
    dependsOn(checkPublishSet)
  }

// The same set, as project paths, one per line, for callers that cannot depend on a Gradle task —
// `scripts/check-ui-builder-external-consumer.sh` builds its own publish task names from this so the
// gate and the release cannot drift apart again.
tasks.register("printPublishedProjectPaths") {
  group = "publishing"
  description = "Prints the project path of every module this release publishes, one per line."
  val paths = publishedProjectPaths
  doLast { paths.get().sorted().forEach { println(it) } }
}

// Every published POM must name coordinates a consumer can resolve — checked for EVERY published
// module, not just `:server`.
//
// 3.1.0 shipped one that could not. `:server` gained `implementation(project(":ui-builder-export"))`
// while that module had no publishing configuration, so Gradle wrote the only identity it had into
// the POM — `compose-preview-server:ui-builder-export-jvm:unspecified` — and every consumer of
// `compose-preview-serve:3.1.0` failed to resolve it, including compose-ai-tools' own wire-drift
// tests. Nothing caught it: the build was green, the publish succeeded, and the artifact was broken
// only for the people downloading it.
//
// The check that came out of that lived in `server/build.gradle.kts`, where `tasks` is `:server`'s
// tasks, so it only ever read `:server`'s own POM. Six modules publish. A project dependency on an
// unpublished project added to `:ui-builder-runtime` — or `:mcp`, `-export`, `-web`,
// `-render-bundle` — reaches THAT module's POM as `unspecified` and nothing looked at it, while
// `compose-preview-serve` becomes unresolvable all the same because consumers resolve it
// transitively. That is not hypothetical: the 3.3.0-3.8.0 breakage was a dangling coordinate in
// `ui-builder-runtime`'s POM, the one module shape the old check could not see.
//
// So it is registered here, once, against every project that applies the publishing plugin — the
// same derivation `publishReleaseArtifacts` uses, so the set that gets published and the set that
// gets checked cannot drift apart.
//
// It reads the GENERATED POM rather than the build files. `unspecified` is the tell for an
// unpublished project dependency, and a `groupId` equal to the Gradle root project name is the tell
// for the same thing wearing a different mask — neither can appear in a POM anyone can use.
abstract class CheckPublishedPomCoordinates : DefaultTask() {
  @get:InputFiles abstract val pomFiles: ConfigurableFileCollection

  @get:Input abstract val rootProjectName: Property<String>

  @get:Input abstract val modulePath: Property<String>

  @TaskAction
  fun check() {
    val root = rootProjectName.get()
    val bad =
      pomFiles.files
        .filter { it.isFile }
        .flatMap { pom ->
          Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL)
            .findAll(pom.readText())
            .map { it.groupValues[1] }
            .filter { dep ->
              dep.contains("<version>unspecified</version>") ||
                dep.contains("<groupId>$root</groupId>")
            }
            .map { dep ->
              val field = { name: String ->
                Regex("<$name>([^<]*)</$name>").find(dep)?.groupValues?.get(1) ?: "?"
              }
              "${field("groupId")}:${field("artifactId")}:${field("version")}"
            }
            .toList()
        }
        .sorted()

    check(bad.isEmpty()) {
      "${modulePath.get()} publishes a POM naming dependencies nobody can resolve: " +
        "${bad.joinToString(", ")}.\n" +
        "A project dependency reaches the POM as a coordinate, so every project this module " +
        "depends on at runtime has to be published - give it the maven-publish plugin, a group, a " +
        "version and coordinates, and it joins the release set automatically. This is what broke " +
        "compose-preview-serve 3.1.0, and again 3.3.0 through 3.8.0."
    }
  }
}

subprojects {
  val modulePath = path
  plugins.withId("com.vanniktech.maven.publish") {
    publishedProjectPaths.add(modulePath)
    publishReleaseArtifacts.configure { dependsOn("$modulePath:publishAndReleaseToMavenCentral") }

    // `dependsOn` alone does NOT order these against `checkPublishSet` - Gradle is free to run them
    // in any order, or at once. That is not a theoretical gap: the 3.26.0 release uploaded to
    // Central and THEN failed its own tripwire, because the assertions ran on the aggregating task,
    // which by definition runs after everything it depends on. An upload cannot be undone, so the
    // check has to be ordered ahead of it rather than merely required alongside it.
    tasks.matching { it.name == "publishAndReleaseToMavenCentral" }.configureEach {
      mustRunAfter(checkPublishSet)
    }

    val pomCheck =
      tasks.register<CheckPublishedPomCoordinates>("checkPublishedPomCoordinates") {
        description = "Fails if this module's published POM names an unresolvable coordinate."
        group = "verification"
        dependsOn(tasks.withType<GenerateMavenPom>())
        pomFiles.from(tasks.withType<GenerateMavenPom>().map { it.destination })
        rootProjectName.set(rootProject.name)
        this.modulePath.set(modulePath)
      }
    tasks.named("check") { dependsOn(pomCheck) }
  }
}

// One compile-time choice for the editor and the MCP adapter that hosts it.
// No environment, URL or request parameter can turn a released build's feature set on.
val remoteComposeAuthoring = providers.gradleProperty("uiBuilderRemoteCompose").orElse("false").map {
  require(it == "true" || it == "false") { "uiBuilderRemoteCompose must be true or false" }
  it.toBooleanStrict()
}

// `generateMcpBuildFeatures` stayed behind with `:mcp`, which is the server's module. The flag is
// still one compile-time choice; it is now made in two builds, and a release pairs them.
listOf(
  Triple("generateUiBuilderBuildFeatures", "ee.schimke.composeai.uibuilder", "UiBuilderBuildFeatures"),
).forEach { (taskName, packageName, objectName) ->
  tasks.register(taskName) {
    val enabled = remoteComposeAuthoring
    val output = layout.buildDirectory.dir("generated/$taskName")
    inputs.property("uiBuilderRemoteCompose", enabled)
    outputs.dir(output)
    doLast {
      val directory = output.get().asFile.apply { mkdirs() }
      directory.resolve("$objectName.kt").writeText(
        "package $packageName\n\n" +
          "/** Build-time feature selection. Enable with -PuiBuilderRemoteCompose=true. */\n" +
          "object $objectName {\n  const val remoteCompose: Boolean = ${enabled.get()}\n}\n"
      )
    }
  }
}
