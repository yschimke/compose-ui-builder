import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { java }

val gateVersion = providers.gradleProperty("gateVersion").get()
val gateRepository = file(providers.gradleProperty("gateRepository").get()).canonicalFile
val forbiddenSourceRoot = file(providers.gradleProperty("forbiddenSourceRoot").get()).canonicalFile

// Through the BOM, not by naming a version — because that is how a consumer is meant to do it, and
// a gate that resolves the runtime by an explicit version proves nothing about whether the platform
// it publishes alongside actually constrains anything.
dependencies {
  implementation(platform("ee.schimke.composeai:compose-preview-ui-builder-bom:$gateVersion"))
  implementation("ee.schimke.composeai:compose-preview-ui-builder-runtime")
}

// Every coordinate the BOM constrains, resolved — not just the one this fixture compiles against.
//
// A platform whose constraints name something nobody published is the 3.3.0-3.8.0 failure with one
// more layer of indirection: it resolves fine for a consumer who does not use that constraint, and
// fails for the one who does. Asserting the POM's `<dependencyManagement>` (below) says the BOM
// PROMISES the right set; resolving this configuration says the promise can be kept.
val bomMembers =
  configurations.create("bomMembers") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }

dependencies {
  add(bomMembers.name, platform("ee.schimke.composeai:compose-preview-ui-builder-bom:$gateVersion"))
}

tasks.register<JavaExec>("runRuntimeProbe") {
  dependsOn(tasks.named("classes"))
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set("ExternalRuntimeConsumer")
}

fun ByteArray.contains(needle: ByteArray): Boolean {
  if (needle.isEmpty()) return true
  return indices.any { start ->
    start + needle.size <= size &&
      needle.indices.all { offset -> this[start + offset] == needle[offset] }
  }
}

tasks.register("verifyExtractionConsumer") {
  group = "verification"
  description = "Consumes the published UI-builder runtime through the published BOM."
  dependsOn("runRuntimeProbe")

  doLast {
    check(!projectDir.canonicalFile.toPath().startsWith(forbiddenSourceRoot.toPath())) {
      "external consumer was created inside the producer source tree"
    }

    val runtimeClasspath = configurations.runtimeClasspath.get()
    val resolvedConfigurations = listOf(runtimeClasspath, bomMembers)
    resolvedConfigurations.forEach { configuration ->
      val projectComponents =
        configuration.incoming.resolutionResult.allComponents
          .filter { component -> component.id != configuration.incoming.resolutionResult.root.id }
          .mapNotNull { component -> component.id as? ProjectComponentIdentifier }
      check(projectComponents.isEmpty()) {
        "external consumer resolved producer projects: ${projectComponents.joinToString()}"
      }
      configuration.resolve().forEach { artifact ->
        check(!artifact.canonicalFile.toPath().startsWith(forbiddenSourceRoot.toPath())) {
          "resolved artifact reads the producer source tree: $artifact"
        }
      }
    }

    val runtimeArtifact =
      runtimeClasspath.incoming.artifacts.artifacts.single { artifact ->
        val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
        id?.group == "ee.schimke.composeai" && id.module == "compose-preview-ui-builder-runtime"
      }
    check(runtimeArtifact.file.extension == "jar")
    ZipFile(runtimeArtifact.file).use { jar ->
      check(
        jar.getEntry("ee/schimke/composeai/uibuilder/service/UiBuilderServicePort.class") != null
      )
      check(jar.getEntry("ee/schimke/composeai/uibuilder/catalogs/m3-catalog-v1.json") != null)
      // The render bundle is deliberately NOT in this jar any more (#346). It is a frontend build
      // output and now ships as `compose-preview-ui-builder-render-bundle`, resolved transitively
      // through the runtime's `api` edge — asserted below on the classpath, which is the only
      // property `PackagedUiBuilderRenderBundle.copyTo` actually needs.
      check(
        jar.getEntry(
          "ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png"
        ) == null
      ) {
        "the render bundle is back inside compose-preview-ui-builder-runtime"
      }
    }

    // What a consumer resolving only `compose-preview-ui-builder-runtime` must still get: the
    // bundle, from wherever. Reading it off the resolved classpath rather than out of a named
    // artifact is the consumer's own view of it, and the one that would break if the transitive
    // edge were ever weakened to `implementation`.
    val bundleEntry = "ee/schimke/composeai/uibuilder/renderer/ui-builder-renderer.bundle.png"
    val bundleCarriers =
      runtimeClasspath.resolve().filter { artifact ->
        artifact.extension == "jar" &&
          ZipFile(artifact).use { jar -> jar.getEntry(bundleEntry) != null }
      }
    check(bundleCarriers.size == 1) {
      "expected exactly one artifact carrying $bundleEntry, found $bundleCarriers"
    }

    // Every constraint the BOM publishes must name something the same release staged.
    val bomPom =
      gateRepository
        .resolve("ee/schimke/composeai/compose-preview-ui-builder-bom/$gateVersion")
        .listFiles()
        .orEmpty()
        .single { it.extension == "pom" }
        .readText()
    val constrained =
      Regex("<artifactId>([^<]+)</artifactId>\\s*<version>([^<]+)</version>")
        .findAll(bomPom.substringAfter("<dependencyManagement>", ""))
        .map { it.groupValues[1] to it.groupValues[2] }
        .toList()
    check(constrained.isNotEmpty()) { "the BOM constrains nothing; the derived set is empty" }
    constrained.forEach { (artifactId, version) ->
      check(version == gateVersion) {
        "the BOM constrains $artifactId to $version, not the $gateVersion this release staged"
      }
      val staged = gateRepository.resolve("ee/schimke/composeai/$artifactId/$version")
      check(staged.isDirectory && staged.listFiles().orEmpty().any { it.extension == "pom" }) {
        "the BOM constrains $artifactId:$version, which this release did not publish"
      }
    }
    logger.lifecycle("BOM constrains ${constrained.size} published coordinate(s)")

    val forbiddenBytes = forbiddenSourceRoot.path.toByteArray(StandardCharsets.UTF_8)
    val leakedFiles =
      gateRepository
        .walkTopDown()
        .filter { it.isFile && it.readBytes().contains(forbiddenBytes) }
        .map { it.relativeTo(gateRepository).path }
        .toList()
    check(leakedFiles.isEmpty()) {
      "published repository leaks producer source paths: ${leakedFiles.joinToString()}"
    }
  }
}
