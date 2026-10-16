package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.LovelaceConfigExporter
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import java.io.File
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Opt-in interoperability check of the owning host's generated publication, without vendoring it.
 */
class LovelacePublishedCatalogIntegrationTest {
  @Test
  fun `harc publication composes and its dashboard fixture round trips`() {
    val path = System.getenv("LOVELACE_PUBLICATION_DIR") ?: return
    val fixture = File(checkNotNull(System.getenv("LOVELACE_DASHBOARD_FIXTURE"))).readText()
    val composed =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
          PublishedUiBuilderCatalog.compose(
            File(path, "ui-builder.json").readText(),
            null,
            ExportCapabilitiesV1.Builder().build(),
          )
        )
        .catalog
    assertEquals("lovelace", composed.benchmark.catalogSystemId)
    val imported =
      LovelaceConfigExporter.import(
        Json.parseToJsonElement(fixture).jsonObject,
        "interop",
        buildJsonObject { put("systemId", "lovelace") },
        cardComponentIds = composed.components.map { it.componentId }.toSet(),
      )
    imported.nodes.values.forEach { node ->
      assertTrue(composed.components.any { it.componentId == node.componentId }, node.componentId)
    }
    assertEquals(
      Json.parseToJsonElement(fixture),
      assertIs<LovelaceConfigExporter.Result.Emitted>(LovelaceConfigExporter.export(imported))
        .config,
    )
    System.getenv("LOVELACE_CAPABILITIES_OUTPUT")?.let {
      File(it).writeText(Json.encodeToString(composed))
    }
  }
}
