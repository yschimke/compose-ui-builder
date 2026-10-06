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
}
