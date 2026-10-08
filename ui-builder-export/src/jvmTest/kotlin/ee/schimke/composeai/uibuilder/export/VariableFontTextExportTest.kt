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
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler

/**
 * `m3/variable-font-text` and `wear-m3/variable-font-text` exported: the call the screen makes and
 * the declaration flexpress generates for it, joined into one file. The Material 3 screen is
 * compiled against Compose Desktop, standalone, so nothing of flexpress is on the classpath.
 */
class VariableFontTextExportTest {
  private val json = Json { ignoreUnknownKeys = true }
  private val dir = Files.createTempDirectory("variable-font-text-")

  @AfterTest fun cleanup() = dir.toFile().deleteRecursively().let {}

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").isFile }

  private val fonts = FlexpressVariableFontSources {
    File(root, "assets/rc-fonts/$it").takeIf(File::isFile)?.readBytes()
  }

  private val record: ComponentRecordFile = run {
    fun read(name: String) =
      json.decodeFromString<ComponentRecordFile>(
        File(root, "docs/design/fixtures/ui-builder/$name").readText()
      )
    val m3 = read("m3-catalog-components-v1.json")
    m3.copy(components = m3.components + read("compose-foundation-components-v1.json").components)
  }

  private fun m3Design(
    properties: String,
    state: String = "{}",
    second: String? = null,
  ): DesignDocumentV1 =
    json.decodeFromString(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "flex", "title": "Flex", "revision": 0,
        "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "stateVariables": $state,
        "roots": ["column"],
        "nodes": {
          "column": {"id": "column", "componentId": "layout/column", "properties": {},
            "slots": {"children": ["title"${if (second != null) ", \"second\"" else ""}]},
            "modifiers": []},
          "title": {"id": "title", "componentId": "m3/variable-font-text",
            "properties": $properties, "slots": {}, "modifiers": [{"type": "padding", "startDp": 8, "topDp": 8, "endDp": 8, "bottomDp": 8}]}
          ${second?.let { """, "second": {"id": "second", "componentId": "m3/variable-font-text", "properties": $it, "slots": {}, "modifiers": []}""" }.orEmpty()}
        }
      }
      """
    )

  private fun export(
    document: DesignDocumentV1,
    mode: VariableFontExportMode = VariableFontExportMode.STANDALONE,
    generator: VariableFontSourceGenerator = fonts,
  ) = ScreenExportGate.export(document, record, variableFonts = VariableFontExport(generator, mode))

  private fun emitted(outcome: ScreenExportGate.Outcome): String =
    when (outcome) {
      is ScreenExportGate.Outcome.Emitted -> outcome.source
      is ScreenExportGate.Outcome.Refused -> error(outcome.reasons.joinToString("\n"))
    }

  private val held =
    """{"text": {"type": "string", "value": "Flex"},
        "font": {"type": "enum", "value": "robotoFlex"},
        "wght": {"type": "float", "value": 800},
        "wdth": {"type": "float", "value": 75},
        "fontSizeSp": {"type": "float", "value": 48},
        "color": {"type": "colorToken", "value": "primary"}}"""

  @Test
  fun `a held text is one call and one standalone declaration that compile against Compose Desktop`() {
    val source =
      emitted(
        export(
          m3Design(
            held,
            second =
              """{"text": {"type": "string", "value": "Hello"},
                  "font": {"type": "enum", "value": "googleSansFlex"},
                  "rond": {"type": "float", "value": 100}}""",
          )
        )
      )
    assertTrue("VariableFontTextRobotoFlexFlex(" in source, source)
    assertTrue("fontSize = 48.sp" in source, source)
    assertTrue("modifier = Modifier.padding(8.dp)" in source, source)
    assertTrue("VariableFontTextGoogleSansFlexHello(" in source, source)
    assertTrue("fontSize = 32.sp" in source, source)
    assertTrue("private object VariableFontTextRobotoFlexFlexOutline" in source, source)
    assertFalse("ee.schimke.flexpress" in source, source)
    assertEquals(1, Regex("(?m)^package ").findAll(source).count(), source)
    val (result, diagnostics) = compile(source)
    assertEquals(ExitCode.OK, result, "$diagnostics\n$source")
  }

  @Test
  fun `the library form says which flexpress it needs, and no generator leaves a note`() {
    val library = emitted(export(m3Design(held), VariableFontExportMode.LIBRARY))
    assertTrue("ee.schimke.flexpress:flexpress-compose:$FLEXPRESS_VERSION" in library, library)
    assertTrue("import ee.schimke.flexpress.compose.VariableFontText" in library, library)

    val pane = emitted(export(m3Design(held), generator = VariableFontSourceGenerator.Unavailable))
    assertTrue("VariableFontTextRobotoFlexFlex(" in pane, pane)
    assertTrue(
      "// VariableFontTextRobotoFlexFlex draws \"Flex\" in Roboto Flex from the font's outlines " +
        "(flexpress). It is generated when the design exports." in pane,
      pane,
    )
  }

  @Test
  fun `an axis bound to state is animated, in a build that authors state`() {
    val outcome =
      export(
        m3Design(
          """{"text": {"type": "string", "value": "Flex"},
              "wght": {"type": "state", "variable": "weight"}}""",
          state =
            """{"weight": {"type": "value", "valueType": "float", "initialValue": 400, "persistence": "preview"}}""",
        )
      )
    if (!UiBuilderBuildFeatures.remoteCompose) {
      val refused = assertIs<ScreenExportGate.Outcome.Refused>(outcome)
      assertTrue(refused.reasons.any { "stateful authoring is disabled" in it }, "$refused")
      return
    }
    val source = emitted(outcome)
    assertTrue("VariableFontTextRobotoFlexFlexWght(" in source, source)
    assertTrue("wght = { weight.value }" in source, source)
    val (result, diagnostics) = compile(source)
    assertEquals(ExitCode.OK, result, "$diagnostics\n$source")
  }

  @Test
  fun `bound text, a computed axis, an unknown font and a missing glyph are refused by name`() {
    fun reasons(properties: String): List<String> =
      assertIs<ScreenExportGate.Outcome.Refused>(export(m3Design(properties))).reasons
    assertTrue(
      reasons("""{"text": {"type": "expr", "op": "concat", "args": []}}""").any {
        "`text` is not a literal" in it
      }
    )
    assertTrue(
      reasons(
          """{"text": {"type": "string", "value": "Flex"},
              "wght": {"type": "system", "value": "time.secondOfHour"}}"""
        )
        .any { "`wght` is computed" in it }
    )
    assertTrue(
      reasons(
          """{"text": {"type": "string", "value": "Flex"},
              "font": {"type": "enum", "value": "comicSans"}}"""
        )
        .any { "`comicSans`, which is not a font it draws in" in it }
    )
    assertTrue(
      reasons(
          """{"text": {"type": "string", "value": "Flex"},
              "rond": {"type": "float", "value": 50}}"""
        )
        .any { "Roboto Flex has no rond axis a design can set" in it }
    )
    assertTrue(
      reasons("""{"text": {"type": "string", "value": "日本"}}""").any {
        "Roboto Flex has no glyph for 日 本" in it
      }
    )
  }

  private fun wearDesign(properties: String): UiBuilderDocument =
    json
      .decodeFromString<DesignDocumentV1>(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "flex", "title": "Flex", "revision": 0,
          "catalogPin": {"systemId": "wear-m3", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
          "environment": {"widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
            "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
          "stateVariables": {},
          "roots": ["screen"],
          "nodes": {
            "screen": {"id": "screen", "componentId": "wear-m3/screen-scaffold", "properties": {},
              "slots": {"content": ["list"]}, "modifiers": []},
            "list": {"id": "list", "componentId": "wear-m3/transforming-lazy-column",
              "properties": {}, "slots": {"items": ["title"]}, "modifiers": []},
            "title": {"id": "title", "componentId": "wear-m3/variable-font-text",
              "properties": $properties, "slots": {}, "modifiers": []}
          }
        }
        """
      )
      .toUiBuilderDocument()

  @Test
  fun `a Wear screen holds its axes and joins the declaration into its file`() {
    val outcome =
      WearScreenCodeExporter.export(
        wearDesign(held),
        packageName = "generated.wear",
        variableFonts = VariableFontExport(fonts, VariableFontExportMode.STANDALONE),
      )
    val source = assertIs<WearScreenCodeExporter.Result.Emitted>(outcome).source
    assertTrue("VariableFontTextRobotoFlexFlex(" in source, source)
    assertTrue("fontSize = 48.sp," in source, source)
    assertTrue("color = MaterialTheme.colorScheme.primary," in source, source)
    assertTrue("private object VariableFontTextRobotoFlexFlexOutline" in source, source)
    assertEquals(1, Regex("(?m)^package ").findAll(source).count(), source)
  }

  @Test
  fun `a size of zero or less is the default, as the canvas draws it`() {
    val source =
      emitted(
        export(
          m3Design(
            """{"text": {"type": "string", "value": "Flex"},
                "fontSizeSp": {"type": "float", "value": 0}}"""
          )
        )
      )
    assertTrue("fontSize = 32.sp" in source, source)
  }

  @Test
  fun `a routed Wear export carries the generator to the declaration`() {
    val generated =
      RecordFreeExport.generate(
        wearDesign(held),
        UiBuilderCatalogPlatform.WEAR,
        packageName = "generated.wear",
        variableFonts = VariableFontExport(fonts, VariableFontExportMode.STANDALONE),
      )
    val source = assertIs<RecordFreeExport.Generated.Emitted>(generated).source
    assertTrue("private object VariableFontTextRobotoFlexFlexOutline" in source, source)
  }

  @Test
  fun `a Wear screen refuses an axis bound to state until it declares a design's state`() {
    val outcome =
      WearScreenCodeExporter.export(
        wearDesign(
          """{"text": {"type": "string", "value": "Flex"},
              "wght": {"type": "state", "variable": "weight"}}"""
        ),
        variableFonts = VariableFontExport(fonts),
      )
    val refused = assertIs<WearScreenCodeExporter.Result.Refused>(outcome)
    assertTrue(refused.reasons.any { "binds `wght`" in it }, "$refused")
  }

  private val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI()).path
  private val desktopCompose = checkNotNull(System.getProperty("desktopCompose.classpath"))
  private val composePlugin = checkNotNull(System.getProperty("composeCompiler.plugin"))

  private fun compile(source: String): Pair<ExitCode, String> {
    val work = Files.createTempDirectory(dir, "compile-")
    val file = work.resolve("Flex.kt").also { Files.writeString(it, source) }
    val diagnostics = ByteArrayOutputStream()
    val arguments =
      listOf(
        "-no-stdlib",
        "-no-reflect",
        "-jvm-target",
        "17",
        "-Xplugin=$composePlugin",
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
}
