plugins {
  base
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.ktfmt) apply false
  alias(libs.plugins.maven.publish) apply false
}

tasks.named("check") {
  group = "verification"
  dependsOn(
    ":ui-builder-runtime:check",
    ":ui-builder-renderer:check",
    ":ui-builder-web:check",
    ":server:check",
    ":slot-preview-runtime:check",
    ":ui-builder:check",
    ":ui-builder-generated-jetcaster:check",
    ":ui-builder-reference-jetcaster:check",
    ":usage-source-psi:check",
    ":wasm-ui:check",
  )
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
  }
}

// ---------------------------------------------------------------------------
// The release's Maven set — DERIVED, never listed.
// ---------------------------------------------------------------------------
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

val publishReleaseArtifacts =
  tasks.register("publishReleaseArtifacts") {
    group = "publishing"
    description = "Publishes every module of this release to Maven Central."

    // A tripwire, not a second list. The derived set is only as good as the plugin detection above:
    // rename the plugin id, move publishing into a convention plugin, and the set silently empties
    // while the release job stays green and publishes nothing. These two are the modules whose
    // absence would be a release with no library in it at all.
    val paths = publishedProjectPaths
    doFirst {
      val derived = paths.get()
      val missing = listOf(":server", ":mcp").filterNot(derived::contains)
      check(missing.isEmpty()) {
        "The derived Maven publish set is missing ${missing.joinToString(", ")} - it resolved to " +
          "${derived.sorted()}. The set is every subproject applying the maven-publish plugin; if " +
          "publishing moved somewhere this no longer detects, fix the detection rather than " +
          "listing modules by hand."
      }
    }
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
