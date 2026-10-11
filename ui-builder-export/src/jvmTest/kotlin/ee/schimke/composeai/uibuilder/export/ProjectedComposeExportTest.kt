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

  private companion object {
    const val BUTTON_COLORS = "androidx.wear.compose.material3.ButtonColors"
  }
}
