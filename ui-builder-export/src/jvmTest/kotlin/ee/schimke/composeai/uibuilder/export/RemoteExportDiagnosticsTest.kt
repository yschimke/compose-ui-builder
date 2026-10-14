package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * What an emitted widget says beside its source (compose-preview-server #1666): an export that
 * compiles only with `material-icons-extended`, throws when captured, or leaves a setting out says
 * so on the node, where it used to return no diagnostics at all.
 */
class RemoteExportDiagnosticsTest {
  @Test
  fun `the inventory records which icons ship only in material-icons-extended`() {
    assertTrue(MaterialIconArtifacts.requiresExtended("pauseCircle"))
    assertTrue(MaterialIconArtifacts.requiresExtended("filled/pauseCircle"))
    CORE_KEYS.forEach { assertFalse(MaterialIconArtifacts.requiresExtended(it), it) }
    // The default glyph an unset icon draws must compile everywhere.
    assertFalse(MaterialIconArtifacts.requiresExtended(REMOTE_ICON_DEFAULT_KEY))
    // Not an icon key at all: refused by the emitter, not warned about here.
    assertFalse(MaterialIconArtifacts.requiresExtended("pause"))
  }

  @Test
  fun `an extended-only icon is a warning on its node naming the dependency`() {
    val emitted = emitted(icon("pauseCircle"))

    assertTrue("Icons.Filled.PauseCircle.toRemoteImageVector()" in emitted.source, emitted.source)
    val diagnostic = emitted.diagnostics.single()
    assertEquals(DiagnosticSeverityV1.WARNING, diagnostic.severity)
    assertEquals(ExportDiagnosticCodes.MATERIAL_ICONS_EXTENDED_REQUIRED, diagnostic.code)
    assertEquals("body", diagnostic.nodeId)
    assertTrue("androidx.compose.material:material-icons-extended" in diagnostic.message)
    assertTrue("`imageVector`" in diagnostic.message, diagnostic.message)
    assertTrue("Icons.Filled.PauseCircle" in diagnostic.message, diagnostic.message)
  }

  @Test
  fun `a core icon carries no diagnostic`() {
    CORE_KEYS.forEach { key -> assertEquals(emptyList(), emitted(icon(key)).diagnostics, key) }
    assertEquals(emptyList(), emitted(UiBuilderNode("body", REMOTE_ICON_COMPONENT_ID)).diagnostics)
  }

  @Test
  fun `the record-free entry hands the diagnostics on`() {
    val generated =
      RecordFreeExport.generate(
        widget(icon("pauseCircle")),
        UiBuilderCatalogPlatform.REMOTE_COMPOSE,
      )

    val emitted = assertIs<RecordFreeExport.Generated.Emitted>(generated)
    assertEquals(
      listOf(ExportDiagnosticCodes.MATERIAL_ICONS_EXTENDED_REQUIRED),
      emitted.diagnostics.map(ExportDiagnosticV1::code),
    )
  }

  @Test
  fun `an adaptive widget names an icon once though it writes it for both sizes`() {
    val document =
      widget(icon("pauseCircle")).let {
        it.copy(
          nodes =
            it.nodes +
              ("host" to
                UiBuilderNode(
                  "host",
                  AdaptiveWearWidget.COMPONENT_ID,
                  slots = mapOf(AdaptiveWearWidget.HEADLINE to listOf("body")),
                ))
        )
      }
    val result = WearWidgetCodeExporter.export(document)

    val emitted = assertIs<WearWidgetCodeExporter.Result.Emitted>(result, result.toString())
    assertEquals(1, emitted.diagnostics.size, emitted.diagnostics.toString())
  }

  @Test
  fun `a Wear screen's extended-only icon is a warning on its iconKey`() {
    val document =
      UiBuilderDocument(
        schema = "ui-builder-design-v1",
        id = "wear-screen",
        title = "Wear screen",
        revision = 1,
        catalogPin = JsonObject(emptyMap()),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf("screen"),
        nodes =
          mapOf(
            "screen" to
              UiBuilderNode(
                "screen",
                WearScreenCodeExporter.SCAFFOLD,
                slots = mapOf("content" to listOf("glyph")),
              ),
            "glyph" to
              UiBuilderNode(
                "glyph",
                WearScreenCodeExporter.ICON,
                properties = buildJsonObject { put("iconKey", enum("pauseCircle")) },
              ),
          ),
      )

    val result = WearScreenCodeExporter.export(document)

    val emitted = assertIs<WearScreenCodeExporter.Result.Emitted>(result, result.toString())
    val diagnostic = emitted.diagnostics.single()
    assertEquals(ExportDiagnosticCodes.MATERIAL_ICONS_EXTENDED_REQUIRED, diagnostic.code)
    assertEquals("glyph", diagnostic.nodeId)
    assertTrue("`iconKey`" in diagnostic.message, diagnostic.message)
  }

  /**
   * Glance Wear's profile does not admit the collapsible and flow layouts: the file compiles and
   * the capture throws, so the comment above the call is not enough.
   */
  @Test
  fun `a layout outside the widget profile is an error on its node`() {
    mapOf(
        "layout/collapsible-column" to "LAYOUT_COLLAPSIBLE_COLUMN",
        "layout/collapsible-row" to "LAYOUT_COLLAPSIBLE_ROW",
        "layout/flow-row" to "LAYOUT_FLOW",
      )
      .forEach { (componentId, operation) ->
        val emitted = emitted(UiBuilderNode("body", componentId))

        assertTrue("// $operation is outside the Glance Wear widget profile." in emitted.source)
        val diagnostic = emitted.diagnostics.single()
        assertEquals(DiagnosticSeverityV1.ERROR, diagnostic.severity, componentId)
        assertEquals(ExportDiagnosticCodes.OUTSIDE_WIDGET_PROFILE, diagnostic.code)
        assertEquals("body", diagnostic.nodeId)
        assertTrue(componentId in diagnostic.message && operation in diagnostic.message)
      }
  }

  @Test
  fun `a column writes its vertical arrangement`() {
    val centred =
      emitted(
          UiBuilderNode(
            "body",
            "layout/column",
            properties = buildJsonObject { put("verticalArrangement", enum("center")) },
          )
        )
        .source
    assertTrue("verticalArrangement = RemoteArrangement.Center" in centred, centred)

    val spaced =
      emitted(
          UiBuilderNode(
            "body",
            "layout/column",
            properties =
              buildJsonObject {
                put("verticalArrangement", enum("bottom"))
                put("verticalSpacingDp", literal("float", JsonPrimitive(8)))
              },
          )
        )
        .source
    assertTrue(
      Regex(
          """verticalArrangement = RemoteArrangement\.spacedBy\([^,]+, RemoteAlignment\.Bottom\)"""
        )
        .containsMatchIn(spaced),
      spaced,
    )

    val distributed =
      emitted(
          UiBuilderNode(
            "body",
            "layout/column",
            properties = buildJsonObject { put("verticalArrangement", enum("spaceBetween")) },
          )
        )
        .source
    assertTrue("verticalArrangement = RemoteArrangement.SpaceBetween" in distributed, distributed)
  }

  @Test
  fun `a row writes its horizontal arrangement`() {
    val source =
      emitted(
          UiBuilderNode(
            "body",
            "layout/row",
            properties = buildJsonObject { put("horizontalArrangement", enum("end")) },
          )
        )
        .source

    assertTrue("horizontalArrangement = RemoteArrangement.End" in source, source)
  }

  @Test
  fun `a text writes its overflow`() {
    val emitted =
      emitted(
        UiBuilderNode(
          "body",
          REMOTE_TEXT_COMPONENT_ID,
          properties =
            buildJsonObject {
              put("text", literal("string", JsonPrimitive("Now playing")))
              put("maxLines", literal("int", JsonPrimitive(1)))
              put("overflow", enum("ellipsis"))
            },
        )
      )

    assertTrue("overflow = TextOverflow.Ellipsis" in emitted.source, emitted.source)
    assertTrue("import androidx.compose.ui.text.style.TextOverflow" in emitted.source)
    assertEquals(emptyList(), emitted.diagnostics)
  }

  @Test
  fun `a text setting the export does not write is a warning, not a silent drop`() {
    val emitted =
      emitted(
        UiBuilderNode(
          "body",
          "m3/text",
          properties =
            buildJsonObject {
              put("text", literal("string", JsonPrimitive("Now playing")))
              put("fontWeight", enum("bold"))
              // The value that changes nothing says nothing.
              put("fontStyle", enum("normal"))
            },
        )
      )

    val diagnostic = emitted.diagnostics.single()
    assertEquals(DiagnosticSeverityV1.WARNING, diagnostic.severity)
    assertEquals(ExportDiagnosticCodes.SETTING_NOT_WRITTEN, diagnostic.code)
    assertEquals("body", diagnostic.nodeId)
    assertTrue("`fontWeight`" in diagnostic.message, diagnostic.message)
  }

  private fun icon(key: String) =
    UiBuilderNode(
      "body",
      REMOTE_ICON_COMPONENT_ID,
      properties = buildJsonObject { put("imageVector", enum(key)) },
    )

  private fun enum(value: String) = literal("enum", JsonPrimitive(value))

  private fun literal(type: String, value: JsonPrimitive) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to value))

  private fun emitted(body: UiBuilderNode): WearWidgetCodeExporter.Result.Emitted {
    val result = WearWidgetCodeExporter.export(widget(body))
    return assertIs<WearWidgetCodeExporter.Result.Emitted>(result, result.toString())
  }

  private fun widget(body: UiBuilderNode) =
    UiBuilderDocument(
      schema = "ui-builder-design-v1",
      id = "remote-diagnostics",
      title = "Remote diagnostics",
      revision = 1,
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("host"),
      nodes =
        mapOf(
          "host" to
            UiBuilderNode(
              "host",
              "remote-m3/widget-container-small",
              slots = mapOf("content" to listOf("body")),
            ),
          "body" to body,
        ),
    )

  private companion object {
    /** Icon keys whose vectors are in `material-icons-core`, so they compile anywhere. */
    val CORE_KEYS = listOf("add", "home", "search", "settings")
  }
}
