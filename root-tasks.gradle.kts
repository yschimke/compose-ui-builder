// The root project's release wiring, applied from `build.gradle.kts`.
//
// Nothing in this file reaches a published artifact: it derives the Maven publish set, names the
// tasks a release runs and checks the published POMs. That is why it is its own file. The publish plan
// (`.github/scripts/maven-publish-plan.sh`) treats `build.gradle.kts` as a shared build input and
// publishes every module when it changes; this file is outside that set, so editing the release
// wiring does not re-upload unchanged coordinates. Anything that changes what a module builds -- a
// plugin, a dependency, a generated source, a feature flag -- belongs in `build.gradle.kts` or
// build-logic instead.

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
    val planned = providers.gradleProperty("composeai.publishSet").orNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
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
      // A planned subset is a subset of the derived set, never a way around it: a plan naming an
      // artifact id nothing publishes would silently upload less than the release promised.
      val derivedIds =
        (derived - ":bom").map { "compose-preview-" + it.removePrefix(":").replace(':', '-') }.toSet()
      val unknown = planned.orEmpty() - derivedIds
      check(unknown.isEmpty()) {
        "The publish plan names ${unknown.sorted()}, which no module publishes. " +
          "The derived set is ${derivedIds.sorted()}."
      }
      check(scanned == derived - ":bom") {
        "The release set and the BOM's set disagree. Applying the plugin says " +
          "${(derived - ":bom").sorted()}; reading the build scripts in settings.gradle.kts says " +
          "${scanned.sorted()}. One of them cannot see something the other can."
      }
    }
  }

// The modules a planned release uploads, from `-Pcomposeai.publishSet` (written by
// `.github/scripts/maven-publish-plan.sh`, as artifact ids). ABSENT means no plan ran and everything
// publishes - the default, and the `workflow_dispatch` recovery path. EMPTY means the plan ran and
// found nothing, which publishes nothing at all, the BOM included. The two must not be collapsed.
// Deliberately restates `PublishedVersions.parsePublishSet`, which the modules and `:bom` use: the
// root build script cannot see build-logic's classes.
val publishSet: Set<String>? =
  providers
    .gradleProperty("composeai.publishSet")
    .orNull
    ?.split(",")
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.toSet()

fun publishedArtifactIdOf(modulePath: String) =
  "compose-preview-" + modulePath.removePrefix(":").replace(':', '-')

/** Is [modulePath] uploaded by this release? The BOM follows the plan rather than being in it. */
fun publishesInThisRelease(modulePath: String): Boolean =
  when {
    publishSet == null -> true
    modulePath == ":bom" -> publishSet.isNotEmpty()
    else -> publishedArtifactIdOf(modulePath) in publishSet
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

// The Gradle tasks this release runs, one per line, honouring `-Pcomposeai.publishSet`. Empty output
// on a planned release means "publish nothing", which the release job treats as success.
tasks.register("printPublishTasks") {
  group = "publishing"
  description = "Prints the publish task of each module this release uploads, one per line."
  notCompatibleWithConfigurationCache("Reads the publish plan through script-level helpers")
  val paths = publishedProjectPaths
  doLast {
    paths
      .get()
      .filter { publishesInThisRelease(it) }
      .sorted()
      .forEach { println("$it:publishAndReleaseToMavenCentral") }
  }
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
    if (publishesInThisRelease(modulePath)) {
      publishReleaseArtifacts.configure { dependsOn("$modulePath:publishAndReleaseToMavenCentral") }
    }

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
