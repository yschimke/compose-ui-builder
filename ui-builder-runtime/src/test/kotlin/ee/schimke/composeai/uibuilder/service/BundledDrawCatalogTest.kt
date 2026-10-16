package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.UiDrawing
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The `remote-m3` catalog the web archive bundles (`docs/design/fixtures/ui-builder/`) is what an
 * MCP App host without a published catalog reads (`McpAppCatalogs`), so its drawing vocabulary must
 * be the one the runtime declares: a property added to [UiDrawing] and missing here has no control
 * there, and a design that sets it is reported as undeclared.
 *
 * Regenerate the drawing entries by running this test with `WRITE_DRAW_CATALOG=<file>`, which
 * writes the runtime's entries by component id for merging into the fixture.
 */
class BundledDrawCatalogTest {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val bundled: CatalogCapabilityV1 =
    json.decodeFromString(
      File("../docs/design/fixtures/ui-builder/remote-m3-capabilities-v1.json").readText()
    )

  @Test
  fun `the bundled remote-m3 catalog declares the drawing vocabulary the runtime does`() {
    val declared = bundled.components.associateBy { it.componentId }
    val live =
      UiDrawing.COMPONENT_IDS.associateWith { id ->
        json.encodeToJsonElement(
          ComponentCapabilityV1.serializer(),
          checkNotNull(remoteDrawComponent(id, declared)) { "$id did not derive" },
        )
      }
    System.getenv("WRITE_DRAW_CATALOG")?.let { File(it).writeText(JsonObject(live).toString()) }

    val stale = live.filter { (id, component) ->
      declared[id]?.let { json.encodeToJsonElement(ComponentCapabilityV1.serializer(), it) } !=
        component
    }
    assertEquals(
      emptySet(),
      stale.keys,
      "these drawing components in the bundled remote-m3 catalog differ from the runtime's; " +
        "regenerate them with WRITE_DRAW_CATALOG",
    )
  }
}
