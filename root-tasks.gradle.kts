// The root project's release wiring, applied from `build.gradle.kts`.
//
// Nothing here reaches a published artifact. It is a separate file because the publish plan
// (`.github/scripts/maven-publish-plan.sh`) republishes every module when `build.gradle.kts`
// changes, and release wiring edits should not. Anything that changes what a module builds belongs
// in `build.gradle.kts` or build-logic.

// The release's Maven set — derived, never listed.
// ---------------------------------------------------------------------------
//
// A module is in the release exactly when it applies the publishing plugin, which is also exactly
// when it can reach a POM as a resolvable coordinate. Hand-maintained lists drifted apart before
// and shipped POMs naming coordinates that were never uploaded; deriving the set leaves no list to
// forget, and `checkPublishedPomCoordinates` catches the rest.
val publishedProjectPaths: SetProperty<String> = objects.setProperty(String::class.java)

// The tripwires on the publish set, as their own task so something other than the release can run
// them (`check-ui-builder-external-consumer.sh` does).
val checkPublishSet =
  tasks.register("checkPublishSet") {
    group = "verification"
    description = "Verifies the derived Maven publish set before anything is uploaded."

    // A tripwire, not a second list: if plugin detection breaks, the derived set silently empties.
    val paths = publishedProjectPaths
    val planned = providers.gradleProperty("composeai.publishSet").orNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    doFirst {
      val derived = paths.get()
      // The modules this release uploads to Central. Not the same as the seams
      // `UI_BUILDER_PROJECT_BOUNDARY.md` names: `:ui-builder-web` is a seam delivered as a GitHub
      // release asset, never a Central coordinate.
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

      // The inverse tripwire: `:ui-builder-web` packages a ~40 MB Wasm frontend that must never be
      // put on Central.
      check(":ui-builder-web" !in derived) {
        "`:ui-builder-web` is in the Maven publish set. It ships as a GitHub release asset, not a " +
          "Central coordinate - see the ivy repository in compose-preview-server's " +
          "settings.gradle.kts. Remove the publishing plugin from it rather than relaxing this."
      }

      // The set is derived twice and the two must agree: here from applied plugins after
      // configuration, and in `settings.gradle.kts` by scanning build scripts as text (`:bom` needs
      // it at configuration time under Isolated Projects). A plugin applied where the text scan
      // cannot see it would otherwise leave the BOM missing a coordinate. `:bom` itself is the one
      // legitimate difference.
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

// The modules a planned release uploads, from `-Pcomposeai.publishSet` (artifact ids written by
// `.github/scripts/maven-publish-plan.sh`). Absent means no plan ran and everything publishes;
// empty means publish nothing, BOM included — the two must not be collapsed. Restates
// `PublishedVersions.parsePublishSet`, which the root script cannot see.
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

// The same set as project paths, one per line, for
// `scripts/check-ui-builder-external-consumer.sh`, so the gate and release cannot drift.
tasks.register("printPublishedProjectPaths") {
  group = "publishing"
  description = "Prints the project path of every module this release publishes, one per line."
  val paths = publishedProjectPaths
  doLast { paths.get().sorted().forEach { println(it) } }
}

// The Gradle tasks this release runs, one per line, honouring `-Pcomposeai.publishSet`. Empty
// output on a planned release means "publish nothing", which the release job treats as success.
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

// Every published POM must name coordinates a consumer can resolve, checked for every published
// module (a dangling coordinate in any module's POM breaks transitive consumers too).
//
// Registered once against every project applying the publishing plugin, the same derivation
// `publishReleaseArtifacts` uses. Reads the generated POM: `unspecified`, or a `groupId` equal to
// the root project name, means an unpublished project dependency.
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

    // `dependsOn` alone does not order these against `checkPublishSet`; an upload cannot be undone,
    // so the check must run before it, not merely alongside.
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
