package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Opt-in external-consumer test: this classpath contains neither app implementation. */
class ExternalTypedCatalogSmokeTest {
  @Test
  fun `an app exported catalog loads through the standard external host reader`() {
    val directory = Path.of(requireNotNull(System.getProperty("typedCatalogSmokeDirectory")))
    val records =
      Json.decodeFromString(
        ComponentRecordFile.serializer(),
        Files.readString(directory.resolve("components.json")),
      )
    for (record in records.components) {
      assertNull(
        javaClass.classLoader.getResource(record.symbol.jvmOwner.replace('.', '/') + ".class"),
        "external host must not contain application implementation ${record.symbol.jvmOwner}",
      )
    }
    val published = Files.readString(directory.resolve("ui-builder.json"))
    val result =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(
          published,
          records,
          ExportCapabilitiesV1.Builder().build(),
        )
      )
    assertEquals(records.components.size, result.catalog.components.size)
    for (component in result.catalog.components) {
      assertEquals(component.componentId, component.wasm.canvas)
      assertEquals(JsonPrimitive(false), component.wasm.platformSupported)
      assertTrue(result.records.containsKey(component.componentId))
    }
  }
}
