package ee.schimke.composeai.uibuilder.mcpapp

import ee.schimke.composeai.uibuilder.UidDesignFiles
import ee.schimke.composeai.uibuilder.export.production.ProductionUidFiles
import kotlin.test.*
import kotlinx.serialization.json.*

class ProductionUidRoundTripTest {
  private val fixture =
    checkNotNull(javaClass.classLoader.getResource("production-editor.uid")).readText()
  private val file = McpAppFile("EpisodeCard.uid", "host-resource://production")

  @Test
  fun `visual edits preserve every production declaration and stabilize after reload`() {
    val opened = UidDesignFiles.open(fixture)
    val edited = opened.document.copy(title = "Edited layout", revision = 1)
    val text = opened.encode(edited)
    val reloaded = UidDesignFiles.open(text)
    assertEquals(edited, reloaded.document)
    assertEquals(opened.production!!.copy(design = edited), reloaded.production)
    assertEquals(text, reloaded.encode())
    // The legacy document-only API must never silently unwrap a production contract.
    assertFails { UidDesignFiles.decode(fixture) }
  }

  @Test
  fun `MCP save reload and external metadata edits retain the production API`() {
    val host = FakeMcpAppHost(file.resourceUri, fixture)
    val session = McpAppDesignSession(host, file)
    runImmediate { session.open() }
    assertNull(session.state.failure)
    runImmediate { session.save() }
    assertTrue(host.writes.isEmpty())
    val original = ProductionUidFiles.decode(fixture)
    val edited = session.state.document!!.copy(title = "Edited", revision = 1)
    session.edited(edited)
    runImmediate { session.save() }
    assertNull(session.state.notice)
    assertEquals(original.copy(design = edited), ProductionUidFiles.decode(host.text))
    runImmediate { session.reload() }
    assertEquals(edited, session.state.document)

    val changedApi =
      ProductionUidFiles.decode(host.text).let {
        it.copy(entryPoint = it.entryPoint!!.copy(kotlinFunction = "example.ui.RenamedCard"))
      }
    host.externalWrite(ProductionUidFiles.encode(changedApi))
    runImmediate { session.resourceUpdated(file.resourceUri) }
    session.edited(session.state.document!!.copy(title = "After external API edit"))
    runImmediate { session.save() }
    assertEquals(changedApi.entryPoint, ProductionUidFiles.decode(host.text).entryPoint)
  }

  @Test
  fun `metadata-only external edits are detected even without host etags`() {
    val host = FakeMcpAppHost(file.resourceUri, fixture)
    val bridge =
      object : McpAppBridge by host {
        override suspend fun read(uri: String): McpAppFileContents =
          host.read(uri).copy(etag = null)
      }
    val session = McpAppDesignSession(bridge, file)
    runImmediate { session.open() }
    host.externalWrite(
      fixture.replace("example.ui.components.EpisodeCard", "example.ui.components.Renamed")
    )
    runImmediate { session.resourceUpdated(file.resourceUri) }
    assertEquals(2, session.state.generation)
    session.edited(session.state.document!!.copy(title = "Keep the new API"))
    runImmediate { session.save() }
    assertEquals(
      "example.ui.components.Renamed",
      ProductionUidFiles.decode(host.text).entryPoint!!.kotlinFunction,
    )
  }

  @Test
  fun `invalid visual edits report a save error without touching the original file`() {
    val host = FakeMcpAppHost(file.resourceUri, fixture)
    val session = McpAppDesignSession(host, file)
    runImmediate { session.open() }
    session.edited(session.state.document!!.copy(nodes = emptyMap()))
    runImmediate { session.save() }
    assertIs<McpAppNotice.Error>(session.state.notice)
    assertTrue(session.state.dirty)
    assertTrue(host.writes.isEmpty())
    assertEquals(fixture, host.text)
  }

  @Test
  fun `unknown contracts and model-only files do not enter a visual editing session`() {
    assertFails { UidDesignFiles.open(fixture.replace("production/v1", "production/v99")) }
    assertFails { UidDesignFiles.open(fixture.replace("document/v1-candidate", "document/v99")) }
    val raw = Json.parseToJsonElement(fixture).jsonObject
    assertFails {
      UidDesignFiles.open(JsonObject(raw + ("futureApi" to JsonPrimitive(true))).toString())
    }
    assertFails { UidDesignFiles.open(JsonObject(raw - "entryPoint" - "design").toString()) }
    // The pre-protocol candidate remains readable without being silently upgraded.
    val candidate = fixture.replace("production/v1", "production/v1-candidate")
    assertEquals(ProductionUidFiles.SCHEMA, UidDesignFiles.open(candidate).production!!.schema)
  }
}
