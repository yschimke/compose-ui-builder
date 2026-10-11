package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.ExportProjection
import ee.schimke.composeai.discovery.ExportProjectionJson
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * A catalog that publishes an export projection gets the REAL library call: `wear-m3/button` with
 * `variant = filled-tonal` exports as `FilledTonalButton(colors = ButtonDefaults.…)`, from the
 * projection's JSON alone, with nothing in this module knowing what a Wear button is.
 */
class ProjectedComposeExportTest {
  private fun parameter(
    name: String,
    type: String,
    typeFqn: String? = null,
    default: Boolean = false,
    slot: Boolean = false,
    lambda: Boolean = false,
    receiver: String? = null,
  ) =
    TargetParameter.Builder(name = name, type = type)
      .also { b ->
        b.typeFqn = typeFqn
        b.composableSlotReceiver = receiver
        b.hasDefault = default
        b.composableSlot = slot
        if (lambda) b.lambdaReturnTypeFqn = "kotlin.Unit"
      }
      .build()

  private fun record(name: String, componentIds: List<String>, parameters: List<TargetParameter>) =
    ComponentRecord.Builder(
        canonicalId = "wear/androidx.wear.compose.material3.${name}Kt.$name",
        symbol =
          ComponentSymbol.Builder(
              jvmOwner = "androidx.wear.compose.material3.${name}Kt",
              callable = "androidx.wear.compose.material3.$name",
              name = name,
              origin = ComponentOrigin.LIBRARY,
            )
            .build(),
      )
      .also { b ->
        b.componentIds = componentIds
        b.signatureKnown = true
        b.parameters = parameters
        b.code =
          ComponentCode.Builder()
            .also { c ->
              c.call = if (name.endsWith("Button")) "$name(onClick = {})" else "$name()"
              c.imports = listOf("androidx.wear.compose.material3.$name")
            }
            .build()
      }
      .build()

  private fun button(name: String, ids: List<String>) =
    record(
      name,
      ids,
      listOf(
        parameter("onClick", "() -> Unit", "kotlin.Function0", lambda = true),
        parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
        parameter("enabled", "Boolean", "kotlin.Boolean", default = true),
        parameter("colors", "ButtonColors", BUTTON_COLORS, default = true),
        parameter(
          "label",
          "RowScope.() -> Unit",
          slot = true,
          receiver = "androidx.compose.foundation.layout.RowScope",
        ),
        parameter(
          "secondaryLabel",
          "RowScope.() -> Unit",
          default = true,
          slot = true,
          receiver = "androidx.compose.foundation.layout.RowScope",
        ),
      ),
    )

  private val records =
    ComponentRecordFile.Builder(
        "wear",
        "release",
        listOf(
          button("Button", listOf("wear-m3/button")),
          button("FilledTonalButton", emptyList()),
          record(
            "ListHeader",
            listOf("wear-m3/list-header"),
            listOf(
              parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
              parameter(
                "content",
                "RowScope.() -> Unit",
                slot = true,
                receiver = "androidx.compose.foundation.layout.RowScope",
              ),
            ),
          ),
          record(
            "Text",
            listOf("wear-m3/text"),
            listOf(
              parameter("text", "String", "kotlin.String"),
              parameter("modifier", "Modifier", "androidx.compose.ui.Modifier", default = true),
            ),
          ),
        ),
      )
      .build()

  /** As a catalog publishes it, on `statusSemantics.components["wear-m3/button"]`. */
  private val buttonProjection =
    Json.parseToJsonElement(
      """
      { "variantProperty": "variant",
        "targets": [
          { "record": "wear/androidx.wear.compose.material3.ButtonKt.Button",
            "slots": { "content": "label" },
            "parameters": {
              "enabled": { "property": "enabled", "codec": "bool", "omitWhen": true },
              "colors": { "call": "androidx.wear.compose.material3.ButtonDefaults.buttonColors",
                          "type": "$BUTTON_COLORS",
                          "arguments": { "containerColor": { "property": "containerColor",
                            "codec": { "color": { "scheme": "androidx.wear.compose.material3.MaterialTheme" } } } } } } },
          { "record": "wear/androidx.wear.compose.material3.FilledTonalButtonKt.FilledTonalButton",
            "variants": ["filled-tonal"],
            "slots": { "content": "label" },
            "parameters": {
              "enabled": { "property": "enabled", "codec": "bool", "omitWhen": true },
              "colors": { "call": "androidx.wear.compose.material3.ButtonDefaults.filledTonalButtonColors",
                          "type": "$BUTTON_COLORS",
                          "arguments": { "containerColor": { "property": "containerColor",
                            "codec": { "color": { "scheme": "androidx.wear.compose.material3.MaterialTheme" } } } } } } } ] }
      """
    )

  private val textProjection =
    Json.parseToJsonElement(
      """
      { "targets": [ { "record": "wear/androidx.wear.compose.material3.TextKt.Text",
          "parameters": { "text": { "property": "text", "codec": "text" } } } ] }
      """
    )

  /** The button again, recognising one or two lines as `label` and `secondaryLabel`. */
  private val patternedButtonProjection =
    Json.parseToJsonElement(
      """
      { "targets": [
          { "record": "wear/androidx.wear.compose.material3.ButtonKt.Button",
            "slotPatterns": { "content": {
              "patterns": [ { "match": [
                { "lines": { "text": "wear-m3/text", "min": 1, "max": 2 },
                  "to": ["label", "secondaryLabel"] } ] } ] } } } ] }
      """
    )

  /** `ListHeader`'s header is its `text` property, written as the catalog's own `Text`. */
  private val listHeaderProjection =
    Json.parseToJsonElement(
      """
      { "targets": [
          { "record": "wear/androidx.wear.compose.material3.ListHeaderKt.ListHeader",
            "synthesize": { "content": { "component": "wear-m3/text",
                                         "properties": { "text": "text" } } } } ] }
      """
    )

  private val projections: Map<String, ExportProjection> =
    mapOf(
      "wear-m3/button" to ExportProjectionJson.decode(buttonProjection).getOrThrow(),
      "wear-m3/text" to ExportProjectionJson.decode(textProjection).getOrThrow(),
    )

  private val root =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "docs/design/evidence/ui-builder-repetition-export").isDirectory }

  private fun document(
    button: DesignNodeV1,
    labelProperties: Map<String, UiValueV1> = emptyMap(),
  ): DesignDocumentV1 {
    val base =
      Json.decodeFromString<DesignDocumentV1>(
        File(root, "docs/design/evidence/ui-builder-repetition-export/repetition-initial.uid")
          .readText()
      )
    val label =
      DesignNodeV1(
        "label",
        "wear-m3/text",
        properties = mapOf("text" to StringValueV1("Go")) + labelProperties,
      )
    return base.copy(
      title = "Projected",
      stateVariables = emptyMap(),
      components = emptyMap(),
      roots = listOf(button.id),
      nodes = mapOf(button.id to button, label.id to label),
    )
  }

  private fun button(vararg properties: Pair<String, UiValueV1>) =
    DesignNodeV1(
      "go",
      "wear-m3/button",
      properties = properties.toMap(),
      slots = mapOf("content" to listOf("label")),
    )

  private fun emitted(document: DesignDocumentV1): String {
    val outcome = ScreenExportGate.export(document, records, projections)
    return assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
  }

  @Test
  fun `a variant exports as the real library composable with its factory colours`() {
    val source =
      emitted(
        document(
          button(
            "variant" to EnumValueV1("filled-tonal"),
            "enabled" to BooleanValueV1(false),
            "containerColor" to ColorTokenValueV1("primary"),
          )
        )
      )
    assertContains(source, "FilledTonalButton(")
    assertContains(source, "enabled = false")
    assertContains(
      source,
      "ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary)",
    )
    assertContains(source, "Text(text = \"Go\")")
    assertContains(source, "import androidx.wear.compose.material3.FilledTonalButton")
  }

  @Test
  fun `an unstyled default keeps the library's own colours and defaults`() {
    val source = emitted(document(button()))
    assertContains(source, "Button(")
    assertFalse("FilledTonalButton" in source, source)
    assertFalse("colors =" in source, source)
    assertFalse("enabled =" in source, source)
  }

  @Test
  fun `a property the projection does not write is refused rather than dropped`() {
    val outcome =
      ScreenExportGate.export(
        document(button("shape" to StringValueV1("pill"))),
        records,
        projections,
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(reasons.any { "`shape` is not written by" in it }, reasons.toString())
  }

  @Test
  fun `a malformed literal is refused with the parameter it was for`() {
    val outcome =
      ScreenExportGate.export(
        document(button("containerColor" to ColorTokenValueV1("not a role"))),
        records,
        projections,
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(reasons.any { "containerColor" in it && "node `go`" in it }, reasons.toString())
  }

  @Test
  fun `the allowlist widens only to owners in the catalog's own packages`() {
    val rogue =
      ExportProjection(
        listOf(
          ExportProjection.Target(
            "wear/androidx.wear.compose.material3.ButtonKt.Button",
            mapOf(
              "colors" to
                ExportProjection.Value.Call(
                  "java.lang.Runtime.getRuntime",
                  BUTTON_COLORS,
                  mapOf(
                    "x" to ExportProjection.Value.Property("enabled", ExportProjection.Codec.Bool)
                  ),
                )
            ),
          )
        )
      )
    val owners =
      ScreenExportGate.projectedOwners(
        projections + ("rogue" to rogue),
        records,
      )
    assertEquals(
      setOf(
        "androidx.wear.compose.material3.ButtonDefaults",
        "androidx.wear.compose.material3.MaterialTheme",
      ),
      owners,
    )
  }

  @Test
  fun `a projected slot keeps the receiver its record declares`() {
    // `weight` is `RowScope.weight`: legal only because `label` is a `RowScope` slot.
    val source = emitted(document(button(), mapOf("weight" to DecimalValueV1(1.0))))
    assertContains(source, ".weight(")
  }

  @Test
  fun `projections are read from the published component policies`() {
    val semantics = buildJsonObject {
      putJsonObject("components") {
        putJsonObject("wear-m3/button") { put("exportProjection", buttonProjection) }
        putJsonObject("wear-m3/text") { put("exportProjection", JsonNull) }
        putJsonObject("wear-m3/broken") { put("exportProjection", JsonPrimitive("nonsense")) }
      }
    }
    assertEquals(
      mapOf("wear-m3/button" to projections.getValue("wear-m3/button")),
      CatalogExportProjections.from(semantics),
    )
  }

  @Test
  fun `a catalog without projections exports exactly as before`() {
    val document = document(button())
    assertEquals(
      ScreenExportGate.export(document, records),
      ScreenExportGate.export(document, records, emptyMap()),
    )
  }

  private fun patterned(): Map<String, ExportProjection> =
    projections +
      ("wear-m3/button" to ExportProjectionJson.decode(patternedButtonProjection).getOrThrow())

  /** A button whose content is [content], over [extra] nodes beside the `Go` label. */
  private fun withContent(content: List<String>, vararg extra: DesignNodeV1): DesignDocumentV1 {
    val base = document(button().copy(slots = mapOf("content" to content)))
    return base.copy(nodes = base.nodes + extra.associateBy { it.id })
  }

  private fun text(id: String, text: String) =
    DesignNodeV1(id, "wear-m3/text", properties = mapOf("text" to StringValueV1(text)))

  @Test
  fun `a column of two texts becomes the label and secondary label, and the column is not written`() {
    val column =
      DesignNodeV1(
        "lines",
        "layout/column",
        slots = mapOf("children" to listOf("label", "later")),
      )
    val outcome =
      ScreenExportGate.export(
        withContent(listOf("lines"), column, text("later", "Later")),
        records,
        patterned(),
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertContains(source, "label = {")
    assertContains(source, "secondaryLabel = {")
    assertContains(source, "Text(text = \"Later\")")
    assertFalse("Column" in source, source)
  }

  @Test
  fun `a single text is the label alone`() {
    val source =
      ScreenExportGate.export(document(button()), records, patterned()).let {
        assertIs<ScreenExportGate.Outcome.Emitted>(it, it.toString()).source
      }
    assertContains(source, "Text(text = \"Go\")")
    assertFalse("secondaryLabel" in source, source)
  }

  @Test
  fun `children no pattern matches are refused with the slot and what they were`() {
    val arranged =
      DesignNodeV1(
        "lines",
        "layout/column",
        properties = mapOf("verticalArrangement" to EnumValueV1("center")),
        slots = mapOf("children" to listOf("label")),
      )
    val outcome =
      ScreenExportGate.export(withContent(listOf("lines"), arranged), records, patterned())
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(
      reasons.any { "node `go`.`content`" in it && "[layout/column]" in it },
      reasons.toString(),
    )
  }

  @Test
  fun `a column whose arrangement is bound or unknown is not read as lines`() {
    for (arrangement in listOf<UiValueV1>(StateValueV1("arrangement"), EnumValueV1("sideways"))) {
      val column =
        DesignNodeV1(
          "lines",
          "layout/column",
          properties = mapOf("verticalArrangement" to arrangement),
          slots = mapOf("children" to listOf("label")),
        )
      val outcome =
        ScreenExportGate.export(withContent(listOf("lines"), column), records, patterned())
      val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
      assertTrue(reasons.any { "[layout/column]" in it }, "$arrangement: $reasons")
    }
  }

  @Test
  fun `a column stating its default arrangement and alignment is still read as lines`() {
    val column =
      DesignNodeV1(
        "lines",
        "layout/column",
        properties =
          mapOf(
            "verticalArrangement" to EnumValueV1("top"),
            "horizontalAlignment" to EnumValueV1("start"),
          ),
        slots = mapOf("children" to listOf("label")),
      )
    val outcome =
      ScreenExportGate.export(withContent(listOf("lines"), column), records, patterned())
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertFalse("Column" in source, source)
  }

  @Test
  fun `a property written as a child exports through that child's own projection`() {
    val header =
      DesignNodeV1(
        "header",
        "wear-m3/list-header",
        properties = mapOf("text" to StringValueV1("Settings")),
      )
    val base = document(button())
    val outcome =
      ScreenExportGate.export(
        base.copy(roots = listOf("header"), nodes = mapOf("header" to header)),
        records,
        projections +
          ("wear-m3/list-header" to ExportProjectionJson.decode(listHeaderProjection).getOrThrow()),
      )
    val source = assertIs<ScreenExportGate.Outcome.Emitted>(outcome, outcome.toString()).source
    assertContains(source, "ListHeader {")
    assertContains(source, "Text(text = \"Settings\")")
  }

  @Test
  fun `a synthesized child of a component with no projection is refused by name`() {
    val header =
      DesignNodeV1(
        "header",
        "wear-m3/list-header",
        properties = mapOf("text" to StringValueV1("Settings")),
      )
    val outcome =
      ScreenExportGate.export(
        document(button()).copy(roots = listOf("header"), nodes = mapOf("header" to header)),
        records,
        mapOf(
          "wear-m3/list-header" to ExportProjectionJson.decode(listHeaderProjection).getOrThrow()
        ),
      )
    val reasons = assertIs<ScreenExportGate.Outcome.Refused>(outcome).reasons
    assertTrue(
      reasons.any { "`content` is written as a `wear-m3/text`" in it },
      reasons.toString(),
    )
  }

  private companion object {
    const val BUTTON_COLORS = "androidx.wear.compose.material3.ButtonColors"
  }
}
