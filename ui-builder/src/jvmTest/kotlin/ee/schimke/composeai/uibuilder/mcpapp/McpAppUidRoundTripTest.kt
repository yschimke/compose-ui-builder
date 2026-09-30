package ee.schimke.composeai.uibuilder.mcpapp

import ee.schimke.composeai.uibuilder.UidDesignFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

/**
 * The editor opens a checked-in `.uid` and, untouched, gives back the same design.
 *
 * The fixture is the one the export golden (`state-actions.kt.txt`) is generated from, so it is a
 * design with state, bindings and events rather than a single node — the fields a lossy decode
 * would drop first.
 */
class McpAppUidRoundTripTest {
  private val fixture: String =
    checkNotNull(javaClass.classLoader.getResource("state-actions.uid")).readText()

  @Test
  fun `decoding and re-encoding the fixture loses nothing`() {
    val encoded = UidDesignFiles.encode(UidDesignFiles.decode(fixture))

    // The same JSON, whatever the fixture's indentation: every field, every value.
    assertEquals(Json.parseToJsonElement(fixture), Json.parseToJsonElement(encoded))
    // And a fixed point: a file this editor wrote is written back byte for byte.
    assertEquals(encoded, UidDesignFiles.encode(UidDesignFiles.decode(encoded)))
  }

  @Test
  fun `opening the fixture in the MCP App host and saving without an edit writes nothing`() {
    val uri = "host-resource://state-actions"
    val host = FakeMcpAppHost(uri, fixture)
    val session = McpAppDesignSession(host, McpAppFile("state-actions.uid", uri))
    runImmediate { session.open() }

    // What the editor reports on its first frame: the document it was given.
    session.edited(session.state.document!!)
    // A re-decode of the same text is an equal document, not the same instance.
    session.edited(UidDesignFiles.decode(fixture))
    runImmediate { session.save() }

    assertFalse(session.state.dirty)
    assertEquals(emptyList(), host.writes)
    assertEquals(fixture, host.text)
  }
}
