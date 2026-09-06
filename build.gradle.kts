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

tasks.register("ktfmtCheckAll") {
  group = "verification"
  dependsOn(
    ":ui-builder-runtime:ktfmtCheck",
    ":ui-builder-renderer:ktfmtCheck",
    ":server:ktfmtCheck",
    ":slot-preview-runtime:ktfmtCheck",
    ":ui-builder:ktfmtCheck",
    ":ui-builder-generated-jetcaster:ktfmtCheck",
    ":ui-builder-reference-jetcaster:ktfmtCheck",
    ":usage-source-psi:ktfmtCheck",
    ":wasm-ui:ktfmtCheck",
  )
}

tasks.register("ktfmtFormat") {
  group = "formatting"
  dependsOn(
    ":ui-builder-runtime:ktfmtFormat",
    ":ui-builder-renderer:ktfmtFormat",
    ":server:ktfmtFormat",
    ":slot-preview-runtime:ktfmtFormat",
    ":ui-builder:ktfmtFormat",
    ":ui-builder-generated-jetcaster:ktfmtFormat",
    ":ui-builder-reference-jetcaster:ktfmtFormat",
    ":usage-source-psi:ktfmtFormat",
    ":wasm-ui:ktfmtFormat",
  )
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

subprojects {
  val modulePath = path
  plugins.withId("com.vanniktech.maven.publish") {
    publishedProjectPaths.add(modulePath)
    publishReleaseArtifacts.configure { dependsOn("$modulePath:publishAndReleaseToMavenCentral") }
  }
}
