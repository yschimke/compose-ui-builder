package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.AdapterValueCodecs
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.discovery.TypedAdapterCatalog
import ee.schimke.composeai.discovery.TypedComponentAdapter
import ee.schimke.composeai.discovery.UI_BUILDER_POLICY_SCHEMA
import ee.schimke.composeai.discovery.UiBuilderCatalogFile
import ee.schimke.composeai.discovery.UiBuilderCatalogs
import ee.schimke.composeai.discovery.UiBuilderPolicyFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** A host that has no Acme implementation can load the portable metadata pair unchanged. */
class TypedCatalogPublicationTest {
  private data class Props(
    val label: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
  )

  private class Adapter(record: ComponentRecord) :
    TypedComponentAdapter<Props>("acme/toggle", record) {
    val label = property(Props::label, AdapterValueCodecs.String, "Toggle")
    val checked = property(Props::checked, AdapterValueCodecs.Boolean, false)
    val change = stateChange(Props::onCheckedChange, checked)
    val content = slot("content", required = true)
  }

  @Test
  fun `published typed catalog composes in an external builder without an app dependency`() {
    val record =
      ComponentRecord.Builder(
          ":app/acme.ToggleKt.Toggle",
          ComponentSymbol.Builder("acme.ToggleKt", "acme.Toggle", "Toggle", ComponentOrigin.PROJECT)
            .build(),
        )
        .also { b ->
          b.signatureKnown = true
          b.parameters =
            listOf(
              TargetParameter.Builder("label", "String")
                .also { it.typeFqn = "kotlin.String" }
                .build(),
              TargetParameter.Builder("checked", "Boolean")
                .also { it.typeFqn = "kotlin.Boolean" }
                .build(),
              TargetParameter.Builder("onCheckedChange", "(Boolean) -> Unit")
                .also {
                  it.typeFqn = "kotlin.Function1"
                  it.lambdaReturnTypeFqn = "kotlin.Unit"
                }
                .build(),
              TargetParameter.Builder("content", "() -> Unit")
                .also { it.composableSlot = true }
                .build(),
            )
        }
        .build()
    val generated =
      TypedAdapterCatalog.generate(
        ComponentRecordFile.Builder(":app", "desktop", listOf(record)).build(),
        UiBuilderCatalogs.CoverSheet("acme", "Acme SDK"),
        UiBuilderPolicyFile.Builder(UI_BUILDER_POLICY_SCHEMA, "mobile").build(),
        listOf(Adapter(record)),
      )
    val json = Json { encodeDefaults = true }
    // Serialize across the actual delivery boundary before asking the normal host reader.
    val published = json.encodeToString(UiBuilderCatalogFile.serializer(), generated.catalog)
    val portableRecord =
      json.decodeFromString(
        ComponentRecordFile.serializer(),
        json.encodeToString(ComponentRecordFile.serializer(), generated.record),
      )
    val result =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(
          published,
          portableRecord,
          ExportCapabilitiesV1.Builder().build(),
        )
      )
    val toggle = result.catalog.components.single()
    assertEquals("acme/toggle", toggle.componentId)
    assertEquals("acme/toggle", toggle.wasm.canvas)
    assertEquals(setOf("label", "checked"), toggle.properties.map { it.name }.toSet())
    assertEquals(
      JsonPrimitive("object"),
      (toggle.properties.single { it.name == "checked" }.jsonType
        as kotlinx.serialization.json.JsonArray)[1],
    )
    assertEquals(1, toggle.slots.single { it.name == "content" }.cardinality.min)
    assertEquals(
      "checked:boolean",
      generated.record.components.single().builder!!.stateCallbacks.single().value,
    )
    assertEquals(record.canonicalId, result.records.getValue("acme/toggle").canonicalId)
  }
}
