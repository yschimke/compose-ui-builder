import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import java.util.zip.ZipFile

plugins { `java-library` }

// NOT `composeai.maven-publishing`, and that absence is the decision.
//
// Applying it is what puts a module in the Central release set and in the BOM. This archive is a
// 40 MB Wasm distribution that a host unpacks — nothing compiles against it, nothing resolves it
// transitively — so it ships as a GitHub release asset instead, and compose-preview-server reaches
// it through a group-fenced ivy repository over that release. Central carries the three jars a
// consumer actually compiles or resolves against.
//
// Leaving the plugin off is also what keeps the BOM honest: the set is derived, so a module that
// does not publish cannot be constrained by a platform that promises it. A BOM entry for a
// coordinate nobody uploaded is exactly what made compose-preview-serve 3.3.0 through 3.8.0
// unresolvable.
group = "ee.schimke.composeai"

// The same derivation the convention plugin applies to the published modules: `PLUGIN_VERSION` in
// CI, a patch-bumped SNAPSHOT off `.release-please-manifest.json` locally. It names the release
// asset, so it has to agree with the version the jars carry.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

// The editor <-> server HTTP API this editor speaks. compose-preview-server serves a pinned editor
// only when its `ServeUiBuilderEditor.SUPPORTED_SERVER_API` contains this number, which is what
// lets an instance pin an editor release without a server release (compose-preview-server#1035).
// Bump it only for a change to the routes the editor calls that an older server cannot answer, and
// land the server's support for the new number first.
val serverApiVersion = 1

abstract class WriteUiBuilderWebManifest : DefaultTask() {
  @get:Input abstract val editorVersion: Property<String>

  @get:Input abstract val serverApi: Property<Int>

  @get:OutputFile abstract val manifestFile: RegularFileProperty

  @TaskAction
  fun write() {
    manifestFile
      .get()
      .asFile
      .writeText(
        """{"schema":"compose-ui-builder-web/v1","version":"${editorVersion.get()}",""" +
          """"serverApi":${serverApi.get()}}""" +
          "\n"
      )
  }
}

val webManifest =
  tasks.register<WriteUiBuilderWebManifest>("webManifest") {
    description = "Write the editor's version and server-API contract into the archive root."
    editorVersion.set(project.version.toString())
    serverApi.set(serverApiVersion)
    manifestFile.set(layout.buildDirectory.file("web-manifest/ui-builder-web.json"))
  }

val webArchive =
  tasks.register<Zip>("webArchive") {
    description = "Package the standalone UI-builder Wasm application as an immutable archive."
    group = "distribution"
    dependsOn(project(":ui-builder").tasks.named("wasmFrontendDist"))
    from(project(":ui-builder").layout.buildDirectory.dir("wasmDist"))
    from(webManifest)
    archiveBaseName.set("compose-preview-" + project.name)
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }

tasks.named<Jar>("jar") { enabled = false }

configurations.named("apiElements") {
  attributes {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("distribution"))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("ui-builder-web"))
    attribute(Usage.USAGE_ATTRIBUTE, objects.named("ui-builder-web-api"))
  }
  outgoing.artifacts.clear()
  outgoing.artifact(webArchive)
}

configurations.named("runtimeElements") {
  description = "Immutable Compose/Wasm UI-builder frontend archive."
  attributes {
    attribute(Category.CATEGORY_ATTRIBUTE, objects.named("distribution"))
    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named("ui-builder-web"))
    attribute(Usage.USAGE_ATTRIBUTE, objects.named("ui-builder-web"))
  }
  outgoing.artifacts.clear()
  outgoing.artifact(webArchive)
}

abstract class VerifyUiBuilderWebArchive : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val archiveFile: RegularFileProperty

  @TaskAction
  fun verify() {
    val archive = archiveFile.get().asFile
    ZipFile(archive).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toList()
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
          "ui-builder-web.json",
        )
      val missing = required - names.toSet()
      check(missing.isEmpty()) { "UI-builder web archive is missing: ${missing.sorted()}" }
      check(names.size == names.toSet().size) { "UI-builder web archive contains duplicate paths" }
      val unsafe =
        names.filter { name ->
          name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." }
        }
      check(unsafe.isEmpty()) { "UI-builder web archive contains unsafe paths: $unsafe" }
    }
  }
}

val verifyUiBuilderWebArchive =
  tasks.register<VerifyUiBuilderWebArchive>("verifyUiBuilderWebArchive") {
    description = "Verify the frontend archive carries a complete, safely-named bundle."
    group = "verification"
    dependsOn(webArchive)
    archiveFile.set(webArchive.flatMap { it.archiveFile })
  }

tasks.named("check") { dependsOn(verifyUiBuilderWebArchive) }
