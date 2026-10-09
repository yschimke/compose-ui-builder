package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * An `m3/surface` theme host's theme — colours, type scale, corner radius and typefaces — exports
 * as a `MaterialTheme` around the surface, the way the canvas draws the design under it, instead of
 * refusing every themed design as arguments `Surface` does not declare.
 */
class ScreenThemeExportTest {
  private val json = Json { ignoreUnknownKeys = true }

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").isFile }

  /** The m3 record plus the foundation one, for the board's `Column`; the two claim no id twice. */
  private val record: ComponentRecordFile = run {
    fun read(name: String) =
      json.decodeFromString<ComponentRecordFile>(
        File(root, "docs/design/fixtures/ui-builder/$name").readText()
      )
    val m3 = read("m3-catalog-components-v1.json")
    m3
      .newBuilder()
      .also { b ->
        b.components = m3.components + read("compose-foundation-components-v1.json").components
      }
      .build()
  }

  private fun document(theme: String = "light", surface: String, text: String = "") =
    json.decodeFromString<DesignDocumentV1>(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "themed",
        "title": "Themed",
        "revision": 0,
        "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "$theme",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "roots": ["screen"],
        "nodes": {
          "screen": {"id": "screen", "componentId": "m3/surface", "properties": {$surface},
            "slots": {"content": ["label"]}, "modifiers": []},
          "label": {"id": "label", "componentId": "m3/text",
            "properties": {"text": {"type": "string", "value": "Hello"}$text},
            "slots": {}, "modifiers": []}
        }
      }
      """
    )

  private fun source(document: DesignDocumentV1): String =
    when (val outcome = ScreenExportGate.export(document, record)) {
      is ScreenExportGate.Outcome.Emitted -> outcome.source
      is ScreenExportGate.Outcome.Refused -> error(outcome.reasons.joinToString("\n"))
    }

  @Test
  fun `colours, type scale and corner radius wrap the surface in MaterialTheme`() {
    val source =
      source(
        document(
          theme = "dark",
          surface =
            """
            "themePrimaryColor": {"type": "string", "value": "#FFD0BCFF"},
            "themeSurfaceColor": {"type": "string", "value": "#1D1F25"},
            "themeContentColor": {"type": "string", "value": "#FFFFFFFF"},
            "themeTypeScale": {"type": "float", "value": 1.25},
            "themeCornerRadiusDp": {"type": "int", "value": 20}
            """,
          text = """, "color": {"type": "colorToken", "value": "primary"}""",
        )
      )
    assertTrue("darkColorScheme(" in source, source)
    assertTrue("primary = Color(0xFFD0BCFF)" in source, source)
    assertTrue("surfaceContainerHighest = Color(0xFF1D1F25)" in source, source)
    assertTrue("onSurfaceVariant = Color(0xFFFFFFFF)" in source, source)
    assertTrue("times(1.25f)" in source, source)
    assertTrue("large = RoundedCornerShape(20.dp)" in source, source)
    assertTrue("medium = RoundedCornerShape(15.dp)" in source, source)
    assertTrue("MaterialTheme(colorScheme = colorScheme" in source, source)
    // Inside the theme a role reads the theme function's parameter, not `MaterialTheme`.
    assertFalse("MaterialTheme.colorScheme" in source, source)
    assertTrue(Regex("color = colorScheme(_\\d+)?\\.primary").containsMatchIn(source), source)
    // No typeface, so no provider.
    assertFalse("GoogleFont" in source, source)
  }

  @Test
  fun `typefaces are Google Fonts families built once from one provider`() {
    val source =
      source(
        document(
          surface =
            """
            "themeDisplayTypeface": {"type": "string", "value": "Michroma"},
            "themeBodyTypeface": {"type": "string", "value": "google:Exo 2"}
            """
        )
      )
    assertTrue("lightColorScheme()" in source, source)
    assertTrue("GoogleFont.Provider(" in source, source)
    assertEquals(1, Regex("GoogleFont\\.Provider\\(").findAll(source).count(), source)
    assertTrue("import kotlin.io.encoding.Base64" in source, source)
    assertTrue(".decode(\"MIIEqDCC" in source, source)
    assertTrue("GoogleFont(\"Michroma\")" in source, source)
    assertTrue("GoogleFont(\"Exo 2\")" in source, source)
    assertEquals(3, Regex("GoogleFont\\(\"Michroma\"\\)").findAll(source).count(), source)
    assertTrue("fontFamily = display" in source, source)
    assertTrue("fontFamily = body" in source, source)
    assertFalse("android.util" in source, source)
  }

  @Test
  fun `the default text role is provided inside the theme`() {
    val source =
      source(document(surface = """"themeTextStyle": {"type": "enum", "value": "labelLarge"}"""))
    assertTrue("ProvideTextStyle(value = " in source, source)
    assertTrue(Regex("\\.labelLarge\\) \\{\\s+Surface").containsMatchIn(source), source)
    assertFalse("MaterialTheme.typography" in source, source)
  }

  @Test
  fun `an unthemed surface exports as before, reading MaterialTheme`() {
    // The theme's record is added only to a themed screen: the generator reserves every recorded
    // component's name, so carrying `MaterialTheme` always refused this `MaterialTheme.typography`.
    val source =
      source(
        document(surface = "", text = """, "style": {"type": "enum", "value": "titleLarge"}""")
      )
    assertFalse("MaterialTheme(" in source, source)
    assertTrue("MaterialTheme.typography.titleLarge" in source, source)
  }

  @Test
  fun `a theme host inside a board themes the whole board, as the canvas draws it`() {
    val document =
      json.decodeFromString<DesignDocumentV1>(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "board", "title": "Board", "revision": 0,
          "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
          "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
            "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
          "roots": ["board"],
          "nodes": {
            "board": {"id": "board", "componentId": "layout/column", "properties": {},
              "slots": {"children": ["first", "second"]}, "modifiers": []},
            "first": {"id": "first", "componentId": "m3/surface",
              "properties": {"themePrimaryColor": {"type": "string", "value": "#FF336699"}},
              "slots": {"content": []}, "modifiers": []},
            "second": {"id": "second", "componentId": "m3/surface", "properties": {},
              "slots": {"content": []}, "modifiers": []}
          }
        }
        """
      )
    val source = source(document)
    assertTrue("primary = Color(0xFF336699)" in source, source)
    assertTrue(Regex("MaterialTheme\\([^)]*\\) \\{\\s+Column").containsMatchIn(source), source)
  }

  @Test
  fun `a themed design whose environment follows the system refuses rather than guessing`() {
    val outcome =
      ScreenExportGate.export(
        document(
          theme = "system",
          surface = """"themePrimaryColor": {"type": "string", "value": "#FF336699"}""",
        ),
        record,
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(reasons.any { "environment theme is `system`" in it }, reasons.toString())
  }

  @Test
  fun `theme properties on a surface that is not the host are spent, as the canvas ignores them`() {
    val document =
      json.decodeFromString<DesignDocumentV1>(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "nested", "title": "Nested", "revision": 0,
          "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
          "environment": {"widthDp": 360, "heightDp": 640, "density": 1.0, "theme": "light",
            "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
          "roots": ["box"],
          "nodes": {
            "box": {"id": "box", "componentId": "layout/box", "properties": {},
              "slots": {"content": ["inner"]}, "modifiers": []},
            "inner": {"id": "inner", "componentId": "m3/surface",
              "properties": {"themeTypeScale": {"type": "float", "value": 1.2}},
              "slots": {"content": []}, "modifiers": []}
          }
        }
        """
      )
    val projected = ScreenDocumentProjection.project(document)
    assertIs<ScreenDocumentProjection.Outcome.Projected>(projected)
  }
}
