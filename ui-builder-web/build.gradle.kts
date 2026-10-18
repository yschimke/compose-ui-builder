import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import java.util.zip.ZipFile

plugins { `java-library` }

// Not `composeai.maven-publishing`, deliberately: this 40 MB Wasm distribution ships as a GitHub
// release asset (reached by compose-preview-server through a group-fenced ivy repository), not a
// Central coordinate. Leaving the plugin off also keeps it out of the derived BOM.
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

// The editor <-> server HTTP API version; compose-preview-server serves a pinned editor only when
// `ServeUiBuilderEditor.SUPPORTED_SERVER_API` contains it. Bump only for route changes an older
// server cannot answer. Deploy the matching server and editor together.
// Version 2 requires the published template-document endpoint for browser creation.
val serverApiVersion = 2

// The host-bridge postMessage contract (`HostBridgeApp.kt`) for IDE hosts embedding the editor over
// a document they own (e.g. VS Code's `.uid` editor). Hosts refuse unsupported numbers. Bump only
// for message changes existing hosts cannot handle.
val hostBridgeVersion = 1

// The MCP App shell (`src/mcp-app/ui-builder-mcp-app.html`, `McpAppHostApp.kt`) a server serves as
// a `text/html;profile=mcp-app` resource. The contract: the required placeholder
// `__COMPOSE_UI_BUILDER_ASSET_BASE__`; optional `__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__` (`focused`
// or `full`, default `focused`) and `__COMPOSE_UI_BUILDER_DEVICE_PRESETS__` (a URL, default none);
// and the shell's path in the archive. Bump only for changes a version-1 server would get wrong.
val mcpAppVersion = 1

abstract class WriteUiBuilderWebManifest : DefaultTask() {
  @get:Input abstract val editorVersion: Property<String>

  @get:Input abstract val serverApi: Property<Int>

  @get:Input abstract val hostBridge: Property<Int>

  @get:Input abstract val mcpApp: Property<Int>

  @get:OutputFile abstract val manifestFile: RegularFileProperty

  @TaskAction
  fun write() {
    manifestFile
      .get()
      .asFile
      .writeText(
        """{"schema":"compose-ui-builder-web/v1","version":"${editorVersion.get()}",""" +
          """"serverApi":${serverApi.get()},"rootOffline":1,"hostBridge":${hostBridge.get()},""" +
          """"mcpApp":${mcpApp.get()}}""" +
          "\n"
      )
  }
}

val webManifest =
  tasks.register<WriteUiBuilderWebManifest>("webManifest") {
    description = "Write the editor's version and server-API contract into the archive root."
    editorVersion.set(project.version.toString())
    serverApi.set(serverApiVersion)
    hostBridge.set(hostBridgeVersion)
    mcpApp.set(mcpAppVersion)
    manifestFile.set(layout.buildDirectory.file("web-manifest/ui-builder-web.json"))
  }

// The shell with this build's version written in; the asset base stays a placeholder, because only
// the server serving the archive knows the URL it serves it at.
val mcpAppShell =
  tasks.register<Copy>("mcpAppShell") {
    description = "Stage the MCP App shell that loads this archive's editor from a server origin."
    val editorVersion = project.version.toString()
    inputs.property("editorVersion", editorVersion)
    from(layout.projectDirectory.file("src/mcp-app/ui-builder-mcp-app.html"))
    filter { line -> line.replace("@UI_BUILDER_VERSION@", editorVersion) }
    into(layout.buildDirectory.dir("mcp-app"))
  }

val webArchive =
  tasks.register<Zip>("webArchive") {
    description = "Package the standalone UI-builder Wasm application as an immutable archive."
    group = "distribution"
    dependsOn(project(":ui-builder").tasks.named("wasmFrontendDist"))
    from(project(":ui-builder").layout.buildDirectory.dir("wasmDist"))
    from(webManifest)
    from(mcpAppShell) { into("mcp-app") }
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
  @get:Internal val mcpAppShellPath: String = "mcp-app/ui-builder-mcp-app.html"

  /** The offline service worker; a host serves it at `<editor root>/ui-builder-sw.js`. */
  @get:Internal val serviceWorkerPath: String = "ui-builder-sw.js"

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
          "wear-m3-capabilities-v1.json",
          "remote-m3-capabilities-v1.json",
          "fonts/fonts.json",
          // Material icon vectors; the browser build draws icons from these, not compiled code.
          "icons/icons-0.json",
          "ui-builder-web.json",
          // The offline service worker. At the root on purpose: a worker's scope cannot be wider
          // than its own directory without a `Service-Worker-Allowed` header, and the editor's
          // pages are the root's. See `ui-builder-boot.js` for when it is registered.
          serviceWorkerPath,
          mcpAppShellPath,
        )
      val missing = required - names.toSet()
      check(missing.isEmpty()) { "UI-builder web archive is missing: ${missing.sorted()}" }
      check(names.size == names.toSet().size) { "UI-builder web archive contains duplicate paths" }
      val unsafe =
        names.filter { name ->
          name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." }
        }
      check(unsafe.isEmpty()) { "UI-builder web archive contains unsafe paths: $unsafe" }
      // The server fills in the asset base, and may fill in the layout and the device presets; the
      // build must have filled in its own.
      val shell = zip.getInputStream(zip.getEntry(mcpAppShellPath)).bufferedReader().readText()
      check("__COMPOSE_UI_BUILDER_ASSET_BASE__" in shell) {
        "$mcpAppShellPath has lost its asset-base placeholder"
      }
      check("__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__" in shell) {
        "$mcpAppShellPath has lost its layout placeholder"
      }
      check("__COMPOSE_UI_BUILDER_DEVICE_PRESETS__" in shell) {
        "$mcpAppShellPath has lost its device-presets placeholder"
      }
      check("@UI_BUILDER_VERSION@" !in shell) { "$mcpAppShellPath was packaged without its version" }
      val worker =
        zip.getInputStream(zip.getEntry(serviceWorkerPath)).bufferedReader().readText()
      check("@UI_BUILDER_SW_VERSION@" !in worker && "@UI_BUILDER_SW_BUNDLE@" !in worker) {
        "$serviceWorkerPath was packaged without its bundle digest"
      }
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
