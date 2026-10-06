package ee.schimke.composeai.uibuilder.mcpapp

import ee.schimke.composeai.uibuilder.UidDesignCollection
import ee.schimke.composeai.uibuilder.UidDesignFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A file of several designs in the MCP App: which one is open, and switching to another. */
class McpAppDesignCollectionTest {
  private val home =
    UidDesignFiles.decode(
      checkNotNull(javaClass.classLoader.getResource("state-actions.uid")).readText()
    )
  private val detail = home.copy(id = "detail", title = "Detail")
  private val text =
    UidDesignFiles.encodeCollection(UidDesignCollection(home.id, listOf(home, detail)))
  private val uri = "host-resource://screens"

  @Test
  fun `switching writes the file with the new design active, keeping an unsaved edit`() {
    val host = FakeMcpAppHost(uri, text)
    val session = McpAppDesignSession(host, McpAppFile("screens.uid", uri))
    runImmediate { session.open() }
    assertEquals(listOf(home.id, "detail"), session.state.designs?.designs?.map { it.id })
    val generation = session.state.generation

    val edited = home.copy(title = "Home, edited")
    session.edited(edited)
    runImmediate { session.selectDesign("detail") }

    val saved = UidDesignFiles.decodeCollection(host.text)
    assertEquals("detail", saved.active)
    assertEquals(listOf(edited, detail), saved.designs)
    assertEquals(detail, session.state.document)
    assertTrue(session.state.generation > generation, "a switch opens a fresh editor")
    assertEquals(false, session.state.dirty)
  }

  @Test
  fun `a read-only file lists its designs and does not switch`() {
    val host = FakeMcpAppHost(uri, text, writable = false)
    val session = McpAppDesignSession(host, McpAppFile("screens.uid", uri))
    runImmediate { session.open() }

    runImmediate { session.selectDesign("detail") }

    assertEquals(home, session.state.document)
    assertEquals(emptyList(), host.writes)
    assertEquals(McpAppNotice.ReadOnly, session.state.notice)
  }

  @Test
  fun `an edit made while the switch is being written is kept, not thrown away`() {
    lateinit var session: McpAppDesignSession
    val late = home.copy(title = "Edited during the write")
    var edits = 0
    val host = FakeMcpAppHost(uri, text, onWrite = { if (edits++ == 0) session.edited(late) })
    session = McpAppDesignSession(host, McpAppFile("screens.uid", uri))
    runImmediate { session.open() }

    runImmediate { session.selectDesign("detail") }

    val saved = UidDesignFiles.decodeCollection(host.text)
    assertEquals("detail", saved.active)
    assertEquals(listOf(late, detail), saved.designs)
    assertEquals(detail, session.state.document)
    assertEquals(2, host.writes.size, "the late edit is written into the design it was made to")
  }

  @Test
  fun `overwriting after a conflict finishes the switch that met it`() {
    val host = FakeMcpAppHost(uri, text)
    val session = McpAppDesignSession(host, McpAppFile("screens.uid", uri))
    runImmediate { session.open() }
    host.externalWrite(text.replace("\"Detail\"", "\"Detail, theirs\""))

    runImmediate { session.selectDesign("detail") }
    assertTrue(session.state.notice is McpAppNotice.Conflict, "${session.state.notice}")
    assertEquals(home, session.state.document)

    runImmediate { session.overwrite() }

    assertEquals("detail", UidDesignFiles.decodeCollection(host.text).active)
    assertEquals(detail, session.state.document)
    assertEquals(null, session.state.notice)
  }
}
