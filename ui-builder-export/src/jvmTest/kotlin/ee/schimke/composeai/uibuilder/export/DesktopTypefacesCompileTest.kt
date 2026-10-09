package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/**
 * A theme's typefaces in the [TypefaceTarget.DESKTOP] form, put through a real Kotlin compiler
 * against Compose Multiplatform Desktop — the classpath an m3-catalog design is rendered on, which
 * has no `androidx.compose.ui.text.googlefonts` and failed every typeface at `Unresolved reference
 * 'GoogleFont'`.
 *
 * The whole generated screen is compiled, not a fragment: `SystemFont` is `@ExperimentalTextApi`,
 * an error-level opt-in, so the test that matters is that the generator wrote the `@OptIn` where
 * the lookups are built, against the library's own marker. The compile classpath is CMP's
 * `material3` for the desktop target and everything it brings (`ui-text` among it), and nothing
 * Android; the build hands it over as `-DdesktopCompose.classpath`.
 */
class DesktopTypefacesCompileTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val dir = Files.createTempDirectory("desktop-typefaces-")

  @AfterTest fun cleanup() = dir.toFile().deleteRecursively().let {}

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").isFile }

  private fun fixture(name: String) = File(root, "docs/design/fixtures/ui-builder/$name").readText()

  private val record: ComponentRecordFile = run {
    fun read(name: String) = json.decodeFromString<ComponentRecordFile>(fixture(name))
    val m3 = read("m3-catalog-components-v1.json")
    m3
      .newBuilder()
      .also { b ->
        b.components = m3.components + read("compose-foundation-components-v1.json").components
      }
      .build()
  }

  private val document =
    json.decodeFromString<DesignDocumentV1>(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "typefaces",
        "title": "Typefaces",
        "revision": 0,
        "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "roots": ["screen"],
        "nodes": {
          "screen": {"id": "screen", "componentId": "m3/surface", "properties": {
              "themeDisplayTypeface": {"type": "string", "value": "Lobster"},
              "themeBodyTypeface": {"type": "string", "value": "google:Exo 2"},
              "themeTypeScale": {"type": "float", "value": 1.25}
            },
            "slots": {"content": ["label"]}, "modifiers": []},
          "label": {"id": "label", "componentId": "m3/text",
            "properties": {"text": {"type": "string", "value": "Hello"}},
            "slots": {}, "modifiers": []}
        }
      }
      """
    )

  private fun source(typefaces: TypefaceTarget? = null): String =
    when (
      val outcome =
        if (typefaces == null) ScreenExportGate.export(document, record)
        else ScreenExportGate.export(document, record, typefaces = typefaces)
    ) {
      is ScreenExportGate.Outcome.Emitted -> outcome.source
      is ScreenExportGate.Outcome.Refused -> error(outcome.reasons.joinToString("\n"))
    }

  @Test
  fun `the desktop form looks each family up by name and compiles against CMP desktop`() {
    val source = source(TypefaceTarget.DESKTOP)
    assertFalse("googlefonts" in source, source)
    assertFalse("GoogleFont" in source, source)
    assertFalse("Base64" in source, source)
    assertTrue("import androidx.compose.ui.text.platform.SystemFont" in source, source)
    listOf("Normal", "Medium", "Bold").forEach {
      assertTrue("SystemFont(\"Lobster\", FontWeight.$it)" in source, source)
      assertTrue("SystemFont(\"Exo 2\", FontWeight.$it)" in source, source)
    }
    assertTrue("ExperimentalTextApi::class" in source, source)
    assertTrue("fontFamily = display" in source, source)
    assertTrue("fontFamily = body" in source, source)

    val (result, diagnostics) = compile(source)
    assertEquals(ExitCode.OK, result, "$diagnostics\n$source")
  }

  /**
   * The failure the desktop form exists for, reproduced: the Android form, compiled the same way,
   * fails exactly as the m3 design lane did. It is still the default, so a caller compiling on
   * desktop has to ask for [TypefaceTarget.DESKTOP].
   */
  @Test
  fun `the Android form does not compile against CMP desktop`() {
    val source = source(TypefaceTarget.ANDROID)
    val (result, diagnostics) = compile(source)
    assertEquals(ExitCode.COMPILATION_ERROR, result, source)
    assertTrue("unresolved reference 'GoogleFont'" in diagnostics, diagnostics)
  }

  /** The standard library, and CMP desktop's Material 3 with everything it brings. */
  private val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI()).path
  private val desktopCompose =
    checkNotNull(System.getProperty("desktopCompose.classpath")) {
      "the build passes CMP desktop's classpath as -DdesktopCompose.classpath"
    }

  private fun compile(source: String): Pair<ExitCode, String> {
    val work = Files.createTempDirectory(dir, "compile-")
    val file = work.resolve("Typefaces.kt").also { Files.writeString(it, source) }
    val diagnostics = ByteArrayOutputStream()
    val arguments =
      listOf(
        "-no-stdlib",
        "-no-reflect",
        "-jvm-target",
        "17",
        "-classpath",
        listOf(stdlib, desktopCompose).joinToString(File.pathSeparator),
        "-d",
        work.resolve("classes").toString(),
        file.toString(),
      )
    val result =
      PrintStream(diagnostics).use { K2JVMCompiler().exec(it, *arguments.toTypedArray()) }
    return result to diagnostics.toString()
  }

  @Test
  fun `the desktop form reports every family it could only look up`() {
    val projected =
      assertIs<ScreenDocumentProjection.Outcome.Projected>(
        ScreenDocumentProjection.project(document, typefaces = TypefaceTarget.DESKTOP)
      )
    assertEquals(listOf("Lobster", "Exo 2"), projected.systemFontFamilies)
    val note = SystemFontLookups.note("Lobster")
    assertTrue("SystemFont(\"Lobster\")" in note, note)
    assertTrue("default face" in note, note)
  }

  @Test
  fun `the Android form is unchanged and is still the default`() {
    val android = source(TypefaceTarget.ANDROID)
    assertEquals(android, source())
    assertTrue("import androidx.compose.ui.text.googlefonts.GoogleFont" in android, android)
    assertTrue("GoogleFont(\"Lobster\")" in android, android)
    assertTrue("GoogleFont.Provider(" in android, android)
    assertFalse("SystemFont" in android, android)
    assertFalse("ExperimentalTextApi" in android, android)
    val projected =
      assertIs<ScreenDocumentProjection.Outcome.Projected>(
        ScreenDocumentProjection.project(document)
      )
    assertEquals(emptyList(), projected.systemFontFamilies)
  }

  @Test
  fun `a desktop native surface selects the desktop form and nothing else does`() {
    fun semantics(name: String): JsonObject =
      json.parseToJsonElement(fixture(name)).jsonObject["statusSemantics"]!!.jsonObject
    assertEquals(
      TypefaceTarget.DESKTOP,
      TypefaceTarget.forCatalog(semantics("m3-catalog-capabilities-v1.json")),
    )
    assertEquals(TypefaceTarget.DESKTOP, TypefaceTarget.forNativeBackend("desktop"))
    assertEquals(TypefaceTarget.ANDROID, TypefaceTarget.forNativeBackend("android"))
    assertEquals(TypefaceTarget.ANDROID, TypefaceTarget.forNativeBackend(null))
    assertEquals(
      TypefaceTarget.ANDROID,
      TypefaceTarget.forCatalog(json.parseToJsonElement("""{"platform": "wear"}""").jsonObject),
    )
  }
}
