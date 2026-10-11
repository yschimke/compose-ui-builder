import java.net.URLClassLoader

// Task types `:ui-builder`'s build script registers, as ordinary build-logic classes.

/**
 * Formats one generated file the way `ktfmtCheck` judges it — which is not `ktfmt
 * --google-style`.
 *
 * The CLI's `Formatter.GOOGLE_FORMAT` has `preserveLambdaBreaks = true`; `ktfmt-gradle` 0.27.0
 * builds options via `FormattingOptionsBean`, which leaves it at the default `false`. This output
 * is compared against the Jetcaster fixture that `ktfmtCheckAll` enforces, so it must match the
 * plugin exactly: the values below are `KtfmtExtension.googleStyle()` plus defaults, through the
 * same six-argument constructor `KtfmtWorkAction.toFormattingOptions` calls.
 *
 * ktfmt is loaded reflectively from the `ktfmtCli` configuration under the platform loader, keeping
 * one ktfmt version in the build and its Kotlin runtime out of Gradle's.
 */
abstract class FormatLikeKtfmtPlugin : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.Classpath
  abstract val ktfmtClasspath: org.gradle.api.file.ConfigurableFileCollection

  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val source: org.gradle.api.file.RegularFileProperty

  @org.gradle.api.tasks.TaskAction
  fun format() {
    val urls = ktfmtClasspath.files.map { it.toURI().toURL() }.toTypedArray()
    URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
      val strategyClass =
        loader.loadClass("com.facebook.ktfmt.format.TrailingCommaManagementStrategy")
      val optionsClass = loader.loadClass("com.facebook.ktfmt.format.FormattingOptions")
      val int = Int::class.javaPrimitiveType
      val boolean = Boolean::class.javaPrimitiveType
      val options =
        optionsClass
          .getConstructor(int, int, int, strategyClass, boolean, boolean)
          .newInstance(
            // KtfmtExtension.DEFAULT_MAX_WIDTH; googleStyle() leaves it alone.
            100,
            // googleStyle(): blockIndent and continuationIndent.
            2,
            2,
            // googleStyle(): TrailingCommaManagementStrategy.COMPLETE.
            strategyClass.getField("COMPLETE").get(null),
            // KtfmtExtension.DEFAULT_REMOVE_UNUSED_IMPORTS.
            true,
            // KtfmtExtension.DEFAULT_DEBUGGING_PRINT_OPTS.
            false,
          )
      val format =
        loader
          .loadClass("com.facebook.ktfmt.format.Formatter")
          .getMethod("format", optionsClass, String::class.java)
      val file = source.get().asFile
      file.writeText(format.invoke(null, options, file.readText()) as String)
    }
  }
}

abstract class VerifyGeneratedSource : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val checkedIn: org.gradle.api.file.RegularFileProperty

  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val expected: org.gradle.api.file.RegularFileProperty

  @org.gradle.api.tasks.TaskAction
  fun verify() {
    check(checkedIn.get().asFile.readBytes().contentEquals(expected.get().asFile.readBytes())) {
      "Generated Jetcaster Compose is stale. Run ./gradlew :ui-builder:generateJetcasterComposeFixture"
    }
  }
}

/**
 * Embeds the component record the Compose export reads, so the editor judges a design against the
 * same artefact the server exports against.
 *
 * Generated from `m3-catalog-components-v1.json` (not copied), as a Kotlin constant because
 * resource loading differs between JVM and wasmJs. `compose-foundation-components-v1.json` is
 * merged in as `ComponentRecordSource` does server-side, since `layout/` and `asset/` components
 * belong to foundation.
 */
abstract class EmbedComponentRecord : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val record: org.gradle.api.file.RegularFileProperty

  /** Merged in when set; `:ui-builder-export`'s Remote Material 3 record has no foundation half. */
  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.Optional
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val foundation: org.gradle.api.file.RegularFileProperty

  /** The Kotlin `val` the JSON is written to, `internal` to the module that embeds it. */
  @get:org.gradle.api.tasks.Input
  abstract val constantName: org.gradle.api.provider.Property<String>

  /** Where the JSON came from, for the generated file's header. */
  @get:org.gradle.api.tasks.Input
  abstract val sourceDescription: org.gradle.api.provider.Property<String>

  init {
    constantName.convention("EMBEDDED_COMPONENT_RECORD_JSON")
    sourceDescription.convention(
      "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json\n" +
        "// merged with compose-foundation-components-v1.json beside it\n" +
        "// by :ui-builder:embedComponentRecord"
    )
  }

  @get:org.gradle.api.tasks.OutputFile abstract val output: org.gradle.api.file.RegularFileProperty

  /**
   * [record] with [foundation]'s components appended, as compact JSON (the text ships and stays
   * resident in Wasm). Parsed, not spliced, so reformatting either file cannot break it.
   *
   * The skip rule matches `ComponentRecordSource.withFoundation` exactly (canonical id and
   * component ids), so the browser and server see the same record.
   */
  private fun merged(): String {
    @Suppress("UNCHECKED_CAST")
    val base = groovy.json.JsonSlurper().parse(record.get().asFile) as MutableMap<String, Any?>
    if (!foundation.isPresent) {
      return groovy.json.JsonOutput.toJson(base)
    }
    @Suppress("UNCHECKED_CAST")
    val extra = groovy.json.JsonSlurper().parse(foundation.get().asFile) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val components = base["components"] as List<Map<String, Any?>>
    val taken = components.mapNotNull { it["canonicalId"] as? String }.toSet()
    @Suppress("UNCHECKED_CAST")
    val claimed = components.flatMap { (it["componentIds"] as? List<String>).orEmpty() }.toSet()
    @Suppress("UNCHECKED_CAST")
    val added =
      (extra["components"] as List<Map<String, Any?>>).filterNot { candidate ->
        candidate["canonicalId"] in taken ||
          (candidate["componentIds"] as? List<String>).orEmpty().any { it in claimed }
      }
    base["components"] = components + added
    return groovy.json.JsonOutput.toJson(base)
  }

  @org.gradle.api.tasks.TaskAction
  fun generate() {
    val json = merged()
    val file = output.get().asFile
    file.parentFile.mkdirs()
    file.writeText(
      buildString {
        appendLine("package ee.schimke.composeai.uibuilder")
        appendLine()
        appendLine("// Generated from ${sourceDescription.get()}. Do not edit.")
        appendLine()
        // A raw string with every `$` escaped (`typeFqn` values contain them); hence `val`, not
        // `const val`.
        appendLine("internal val ${constantName.get()}: String =")
        val escaped = json.trimEnd().replace("$", "\${'\$'}")
        appendLine("  \"\"\"" + escaped + "\"\"\"")
      }
    )
  }
}

/**
 * Fails when the `androidx.window` the server's renderer sidecar carries is not the one this
 * module's design render links against.
 *
 * The sidecar sits ahead of the bundle's dependencies on the parent classpath (see
 * `server/build.gradle.kts`), so its copy wins at render time; drift would surface as a
 * `NoSuchMethodError` mid-render. Lives here because `window-core` arrives via
 * `compose-material3-adaptive` in this module.
 */
abstract class CheckWindowSidecarVersion : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.Classpath
  abstract val runtimeClasspath: org.gradle.api.file.ConfigurableFileCollection

  @get:org.gradle.api.tasks.Input abstract val pinned: org.gradle.api.provider.Property<String>

  @org.gradle.api.tasks.TaskAction
  fun verify() {
    val prefix = "window-core-desktop-"
    val jar =
      runtimeClasspath.files.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(".jar") }
        ?: error(
          "this module no longer resolves $prefix*.jar — if `androidx.window` has left the render " +
            "path, drop the `androidx-window` catalog entry and the server's sidecar dependency " +
            "with it (see #812)"
        )
    val resolved = jar.name.removePrefix(prefix).removeSuffix(".jar")
    check(resolved == pinned.get()) {
      "the renderer sidecar pins androidx.window ${pinned.get()} but this module renders against " +
        "$resolved; the sidecar copy wins on the daemon's parent loader, so set " +
        "`androidx-window` in gradle/libs.versions.toml to $resolved (#812)"
    }
  }
}

/**
 * The newest releases' notes from `CHANGELOG.md`, embedded as Kotlin so the home screen can show
 * them without fetching. Features are shown, fixes only for a release without features; links and
 * hashes are dropped.
 */
abstract class EmbedReleaseNotes : org.gradle.api.DefaultTask() {
  @get:org.gradle.api.tasks.InputFile
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val changelog: org.gradle.api.file.RegularFileProperty

  /** How many releases to keep. */
  @get:org.gradle.api.tasks.Input abstract val releases: org.gradle.api.provider.Property<Int>

  @get:org.gradle.api.tasks.OutputFile abstract val output: org.gradle.api.file.RegularFileProperty

  init {
    releases.convention(4)
  }

  @org.gradle.api.tasks.TaskAction
  fun embed() {
    val notes = parseReleaseNotes(changelog.get().asFile.readText(), releases.get())
    fun literal(value: String) =
      "\"" +
        value
          .replace("\\", "\\\\")
          .replace("\"", "\\\"")
          .replace("$", "\\$")
          .replace("\n", " ") +
        "\""
    val body =
      notes.joinToString(",\n") { (version, date, items) ->
        "  UiBuilderReleaseNote(${literal(version)}, ${literal(date)}, listOf(" +
          items.joinToString(", ") { literal(it) } +
          "))"
      }
    val file = output.get().asFile
    file.parentFile.mkdirs()
    file.writeText(
      "// Generated from CHANGELOG.md by :ui-builder:embedReleaseNotes. Do not edit.\n" +
        "package ee.schimke.composeai.uibuilder.editor\n\n" +
        "internal val EMBEDDED_RELEASE_NOTES: List<UiBuilderReleaseNote> =\n" +
        "  listOf(\n$body\n  )\n"
    )
  }

  companion object {
    private val RELEASE = Regex("""^## \[?([0-9][^\]\s]*)\]?(?:\([^)]*\))?\s*\(([^)]*)\)""")
    private val SECTION = Regex("""^### (.+)$""")
    private val ITEM = Regex("""^\* (.+)$""")

    /** `(version, date, items)` for the newest [limit] releases, newest first. */
    fun parseReleaseNotes(text: String, limit: Int): List<Triple<String, String, List<String>>> {
      data class Release(
        val version: String,
        val date: String,
        val features: MutableList<String> = mutableListOf(),
        val fixes: MutableList<String> = mutableListOf(),
      )
      val releases = mutableListOf<Release>()
      var section = ""
      for (line in text.lines()) {
        val header = RELEASE.find(line)
        if (header != null) {
          if (releases.size == limit) break
          releases += Release(header.groupValues[1], header.groupValues[2])
          section = ""
          continue
        }
        SECTION.find(line)?.let { section = it.groupValues[1].trim().lowercase() }
        val item = ITEM.find(line)?.groupValues?.get(1) ?: continue
        val release = releases.lastOrNull() ?: continue
        val clean =
          item
            .replace(Regex("""\s*\(\[[^\]]*\]\([^)]*\)\)"""), "")
            .replace(Regex("""^\*\*[^*]+:\*\*\s*"""), "")
            .trim()
        if (clean.isEmpty()) continue
        when (section) {
          "features" -> release.features += clean
          "bug fixes" -> release.fixes += clean
        }
      }
      return releases.map {
        Triple(it.version, it.date, (it.features.ifEmpty { it.fixes }).take(4))
      }
    }
  }
}

/**
 * The design document and mutation JSON Schemas, extracted from the pinned `ui-builder-protocol`
 * jar (generated from the protocol's serializers since 3.13.0) rather than maintained by hand.
 * Published under this repository's names with stable `$id`s, since releases attach them at
 * per-version URLs that documents cite.
 */
abstract class ExtractProtocolSchemas : org.gradle.api.DefaultTask() {
  /** The protocol's JVM jar; its `schemas/` entries are the generated schemas. */
  @get:org.gradle.api.tasks.InputFiles
  @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
  abstract val protocolJar: org.gradle.api.file.ConfigurableFileCollection

  /** Jar entry under `schemas/` → the file name this repository publishes it as. */
  @get:org.gradle.api.tasks.Input
  abstract val schemas: org.gradle.api.provider.MapProperty<String, String>

  /** Published file name → the `$id` it carries. */
  @get:org.gradle.api.tasks.Input
  abstract val ids: org.gradle.api.provider.MapProperty<String, String>

  /** Written as `<output>/schemas/<name>`, so it can be a resource root. */
  @get:org.gradle.api.tasks.OutputDirectory
  abstract val output: org.gradle.api.file.DirectoryProperty

  @org.gradle.api.tasks.TaskAction
  fun extract() {
    val target = output.get().asFile.resolve("schemas")
    target.deleteRecursively()
    target.mkdirs()
    val jar = protocolJar.files.single()
    java.util.zip.ZipFile(jar).use { zip ->
      schemas.get().forEach { (entry, name) ->
        val text =
          zip.getEntry("schemas/$entry")?.let { zip.getInputStream(it).reader().readText() }
            ?: throw org.gradle.api.GradleException(
              "${jar.name} carries no schemas/$entry; is the contracts pin older than 3.13.0?"
            )
        val id = ids.get()[name]
        // The generated file opens `{` on a line of its own; the `$id` goes first inside it.
        check(text.startsWith("{\n")) { "schemas/$entry does not start with an object" }
        val published = if (id == null) text else "{\n  \"\$id\": \"$id\",\n" + text.substring(2)
        target.resolve(name).writeText(published)
      }
    }
  }
}
