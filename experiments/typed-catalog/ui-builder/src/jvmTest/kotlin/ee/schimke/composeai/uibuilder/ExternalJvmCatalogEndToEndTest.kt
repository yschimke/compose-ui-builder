package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.renderer.sdk.loadJvmCanvasAdapterRegistry
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.io.File
import java.net.URLClassLoader
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.serialization.json.*

/** Compiles without Meshcore; only the separately packaged bundle supplies its implementation. */
@OptIn(ExperimentalTestApi::class)
class ExternalJvmCatalogEndToEndTest {
  @Test
  fun `installed catalog renders a saved design and supports inspector edits state and Send`() {
    val bundle = File(requireNotNull(System.getProperty("typedCatalogJvmBundle")))
    val evidence = File(requireNotNull(System.getProperty("typedCatalogJvmEvidence")))
    evidence.mkdirs()
    val records =
      Json.decodeFromString<ComponentRecordFile>(bundle.resolve("components.json").readText())
    val published =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(
          bundle.resolve("ui-builder.json").readText(),
          records,
          ExportCapabilitiesV1.Builder().build(),
        )
      )
    val hostLoader = javaClass.classLoader
    for (record in records.components) {
      assertNull(hostLoader.getResource(record.symbol.jvmOwner.replace('.', '/') + ".class"))
    }
    val jars = requireNotNull(bundle.resolve("lib").listFiles()).sortedBy { it.name }
    assertTrue(jars.isNotEmpty())
    assertTrue(jars.all { it.isFile && it.extension == "jar" }, "bundle must contain packaged JARs")
    URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), hostLoader).use { loader ->
      for (record in records.components) {
        val implementation =
          assertNotNull(loader.getResource(record.symbol.jvmOwner.replace('.', '/') + ".class"))
        assertEquals(
          "jar",
          implementation.protocol,
          "live components must come from the installed artifact",
        )
      }
      assertFailsWith<IllegalArgumentException> {
        loadJvmCanvasAdapterRegistry("missing-catalog", records, loader, emptySet())
      }
      val adapters =
        published.catalog.components.associate { it.componentId to requireNotNull(it.wasm.canvas) }
      assertFailsWith<IllegalArgumentException> {
        loadJvmCanvasAdapterRegistry("meshcore-mobile", records, loader, setOf("missing-adapter"))
      }
      val registry =
        loadJvmCanvasAdapterRegistry("meshcore-mobile", records, loader, adapters.values.toSet())
      adapters.values.forEach { assertNotNull(registry[it]) }
      val imported =
        CapabilityCatalogParser.parse(
          Json.encodeToString(CatalogCapabilityV1.serializer(), published.catalog)
        )
      // Structural layout and mirror labels belong to the host's existing vocabulary, not Meshcore.
      val builtins =
        CapabilityCatalogParser.parse(
            requireNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
          )
          .components
          .filter { it.componentId in setOf("layout/column", "m3/text") }
      assertEquals(2, builtins.size)
      val catalog = imported.copy(components = imported.components + builtins)
      val reducer = UiBuilderEditorReducer(catalog)
      val saved =
        Json.decodeFromString<UiBuilderDocument>(
          bundle.resolve("designs/chat-input.uid").readText()
        )
      var editor by mutableStateOf(reducer.initial(saved, "input"))
      var opened by mutableStateOf(0)
      fun edit(property: String, draft: String) {
        editor =
          reducer.reduce(editor, UiBuilderEditorEvent.CommitProperty("input", property, draft))
        assertIs<CommandOutcome.Accepted>(editor.lastOutcome, editor.lastOutcome.toString())
      }
      runDesktopComposeUiTest(width = 500, height = 260) {
        setContent {
          Box(Modifier.background(Color.White)) {
            key(opened) {
              UiBuilderSurface(
                editor.document,
                catalogComponentIds = adapters.keys + setOf("layout/column", "m3/text"),
                canvasAdapterIds = adapters,
                canvasAdapterRegistry = registry,
              )
            }
          }
        }
        onAllNodesWithText("Draft").assertCountEquals(2)
        onNodeWithContentDescription("Send").assertIsEnabled()
        ImageIO.write(
          onRoot().captureToImage().toAwtImage(),
          "png",
          evidence.resolve("installed-chat-input.png"),
        )
        runOnIdle { edit("enabled", "false") }
        onNodeWithContentDescription("Send").assertIsNotEnabled()
        runOnIdle {
          edit("enabled", "true")
          edit("placeholder", "Type a message")
        }
        onNode(hasSetTextAction()).performTextReplacement("")
        onNodeWithText("Type a message").assertExists()
        onNode(hasSetTextAction()).performTextReplacement("Hello from external builder")
        onAllNodesWithText("Hello from external builder").assertCountEquals(2)
        onNodeWithContentDescription("Send").performClick()
        onNodeWithText("Sent from live adapter").assertExists()
        ImageIO.write(
          onRoot().captureToImage().toAwtImage(),
          "png",
          evidence.resolve("edited-and-sent.png"),
        )
        runOnIdle {
          assertEquals(3, editor.document.revision)
          val editedFile = evidence.resolve("edited-chat-input.uid")
          editedFile.writeText(Json.encodeToString(UiBuilderDocument.serializer(), editor.document))
          assertEquals(
            editor.document,
            Json.decodeFromString<UiBuilderDocument>(editedFile.readText()),
          )
          assertEquals(
            "Draft",
            editor.document.stateVariables
              .getValue("message")
              .jsonObject
              .getValue("initialValue")
              .jsonPrimitive
              .content,
          )
          editor =
            reducer.initial(
              Json.decodeFromString<UiBuilderDocument>(editedFile.readText()),
              "input",
            )
          opened++
        }
        onAllNodesWithText("Draft").assertCountEquals(2)
        onNodeWithText("Not sent").assertExists()
        onNodeWithContentDescription("Send").assertIsEnabled()
        onNode(hasSetTextAction()).performTextReplacement("")
        onNodeWithText("Type a message").assertExists()
      }
    }
  }
}
