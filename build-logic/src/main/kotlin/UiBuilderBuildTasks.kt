import java.net.URLClassLoader

// Task types `:ui-builder`'s build script registers. They lived inline in that script, which made
// them uncompilable on their own, untestable, and recompiled with every edit to the script; here
// they are ordinary classes of the build-logic included build.

/**
 * Formats one generated file the way `ktfmtCheck` will judge it — which is NOT `ktfmt
 * --google-style`.
 *
 * Both gates resolve ktfmt 0.64 and both say `googleStyle`, so they read as interchangeable. They
 * are not. `Formatter.GOOGLE_FORMAT`, which the CLI's `--google-style` uses as-is, carries
 * `preserveLambdaBreaks = true`; `ktfmt-gradle` 0.27.0 builds its options through
 * `FormattingOptionsBean`, whose six fields do not include that one, so the plugin formats with
 * ktfmt's default of `false`. A lambda an author broke across lines and that would fit on one is
 * therefore kept by the CLI and collapsed by the plugin — the same file, two answers, and the
 * plugin's is the one CI enforces (#822).
 *
 * That mattered here because the Jetcaster fixture is formatted by one and checked by the other:
 * this task's output is compared against
 * `ui-builder-generated-jetcaster/.../JetcasterDiscoverExpanded.kt`, which `ktfmtCheckAll` holds to
 * the plugin's formatting. The day `ScreenGenerator` emits such a lambda the two gates would want
 * different bytes and neither could be satisfied.
 *
 * So this reproduces the plugin's options exactly rather than approximating them with a CLI flag —
 * there is no flag: `--google-style`, `--meta-style` and `--kotlinlang-style` are the only styles
 * the CLI exposes and none of them is what the plugin does. The numbers below are
 * `KtfmtExtension.googleStyle()` plus that class's defaults, and the constructor chosen is the same
 * six-argument one `KtfmtWorkAction.toFormattingOptions` calls, so anything ktfmt adds a default
 * for lands here the way it lands there.
 *
 * ktfmt is loaded from the `ktfmtCli` configuration reflectively, under the platform loader: it
 * keeps ONE version of ktfmt in the build (the version catalog's) and keeps its Kotlin runtime out
 * of Gradle's.
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
 * Generated from `m3-catalog-components-v1.json` rather than copied, because a second copy of the
 * record is the drift this module is removing. It lands as a Kotlin constant rather than a resource
 * because resource loading differs between the JVM and wasmJs, and the panel must behave the same
 * in both — the browser is where it actually runs.
 *
 * `compose-foundation-components-v1.json` is merged in for the same reason `ComponentRecordSource`
 * unions it server-side: `layout/column` and `asset/image` belong to `androidx.compose.foundation`
 * rather than to any catalog, and a panel judging a design without them would report every layout
 * node as having no record while the server exported it happily. The two files share no component,
 * so the merge is a concatenation; the server owns the rule for when they would, and this states
 * the same outcome on the side that cannot see it.
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
   * [record] with [foundation]'s components appended, as JSON text.
   *
   * Parsed rather than spliced textually: both files are written by hand and a splice assuming
   * either one's formatting would break the first time somebody reformatted it.
   *
   * The skip is `ComponentRecordSource.withFoundation`'s, rule for rule — the canonical id AND the
   * component ids — because this constant and that union describe the same record to two readers.
   * Keyed on the canonical id alone, a catalog that carried its own `Column` under a canonical id
   * of its own would keep it on the server and get BOTH here, so the browser's panel would see
   * `layout/column` claimed twice and refuse an export the server writes happily. The two files
   * share neither key today; a rule that only holds while that is true is not the rule.
   */
  private fun merged(): String {
    @Suppress("UNCHECKED_CAST")
    val base = groovy.json.JsonSlurper().parse(record.get().asFile) as MutableMap<String, Any?>
    if (!foundation.isPresent) {
      return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(base))
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
    return groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(base))
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
        // A raw string, with every `$` escaped: `typeFqn` values carry them (a nested classifier
        // is `Arrangement${'$'}Vertical` in a JVM name), and Kotlin would read them as template
        // interpolation. `val` rather than `const val` for the same reason — an escaped raw string
        // is not a compile-time constant.
        appendLine("internal val ${constantName.get()}: String =")
        val escaped = json.trimEnd().replace("$", "\${'\$'}")
        appendLine("  \"\"\"" + escaped + "\"\"\"")
      }
    )
  }
}

/**
 * Fails when the `androidx.window` the server's renderer sidecar carries is not the one this
 * module's design render actually links against.
 *
 * The sidecar copy exists because the daemon force-delegates `androidx.*` classes to its parent
 * loader while promoting jars to that parent by GROUP, and the JetBrains port
 * `org.jetbrains.androidx.window:window-core` matches the package rule but not the group rule —
 * `server/build.gradle.kts` carries the full explanation and
 * [#812](https://github.com/yschimke/compose-preview-server/issues/812) the failure it caused.
 *
 * The consequence for versions is the part worth a gate. The sidecar sits AHEAD of the bundle's own
 * dependencies on the parent classpath, so its copy is the one the render links against whatever
 * the bundle recorded. Pinned here and resolved there, the two can drift the next time the adaptive
 * artifacts move — and the symptom would be a `NoSuchMethodError` in the middle of a render, on a
 * lane only the visual harness exercises. Comparing them at build time costs nothing and names the
 * two numbers.
 *
 * Lives in this module because this is where the version is decided: `window-core` arrives under
 * `compose-material3-adaptive`, and reading it anywhere else would be reading a copy.
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
