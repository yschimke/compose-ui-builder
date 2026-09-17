import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

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

val webArchive =
  configurations.create("uiBuilderWebArchive") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named("distribution"))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("ui-builder-web"))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named("ui-builder-web"))
    }
  }

dependencies {
  add(
    webArchive.name,
    "ee.schimke.composeai:compose-preview-ui-builder-web:$gateVersion",
  )
}

// What the BOM promised, checked against what the release actually staged.
//
// A platform whose constraints name a coordinate nobody published is the 3.3.0-3.8.0 failure with
// one more layer of indirection: resolution succeeds for a consumer who does not use that
// constraint, and fails for the one who does. So every constraint is resolved here, not just the
// two this fixture compiles against.
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

fun File.sha256(): String {
  val digest = MessageDigest.getInstance("SHA-256")
  inputStream().buffered().use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      digest.update(buffer, 0, count)
    }
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

tasks.register("verifyExtractionConsumer") {
  group = "verification"
  description = "Consumes the published UI-builder runtime and exact web distribution variant."
  dependsOn("runRuntimeProbe")

  doLast {
    check(!projectDir.canonicalFile.toPath().startsWith(forbiddenSourceRoot.toPath())) {
      "external consumer was created inside the producer source tree"
    }

    val runtimeClasspath = configurations.runtimeClasspath.get()
    val resolvedConfigurations = listOf(runtimeClasspath, webArchive)
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

    val webArtifact = webArchive.incoming.artifacts.artifacts.single()
    check(webArtifact.file.extension == "zip") {
      "exact UI-builder web variant resolved ${webArtifact.file.name}, not a ZIP"
    }
    val webAttributes = webArtifact.variant.attributes
    check(webAttributes.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name == "distribution")
    check(
      webAttributes.getAttribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE)?.name ==
        "ui-builder-web"
    )
    check(webAttributes.getAttribute(Usage.USAGE_ATTRIBUTE)?.name == "ui-builder-web")

    ZipFile(webArtifact.file).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toSet()
      val required =
        setOf(
          "index.html",
          "uiBuilder.mjs",
          "uiBuilder.wasm",
          "skiko.mjs",
          "skiko.wasm",
          "m3-catalog-capabilities-v1.json",
          "jetcaster-discover-operations-v1.json",
          "fonts/fonts.json",
        )
      check(names.containsAll(required)) { "web archive is missing ${required - names}" }
    }

    val coordinateDirectory =
      gateRepository.resolve("ee/schimke/composeai/compose-preview-ui-builder-web/$gateVersion")
    val moduleMetadata =
      coordinateDirectory.listFiles().orEmpty().single { it.extension == "module" }.readText()
    val pom = coordinateDirectory.listFiles().orEmpty().single { it.extension == "pom" }.readText()
    check(moduleMetadata.contains("\"org.gradle.category\": \"distribution\""))
    check(moduleMetadata.contains("\"org.gradle.libraryelements\": \"ui-builder-web\""))
    check(moduleMetadata.contains("\"org.gradle.usage\": \"ui-builder-web-api\""))
    check(moduleMetadata.contains("\"org.gradle.usage\": \"ui-builder-web\""))
    check(moduleMetadata.contains("\"sha256\": \"${webArtifact.file.sha256()}\"")) {
      "published Gradle metadata does not authenticate the resolved web archive"
    }
    check(pom.contains("<packaging>zip</packaging>"))

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
