package ee.schimke.composeai.uibuilder.mcpapp

import kotlin.test.Test
import kotlin.test.assertEquals

/** The shell's `layout` value, as compose-preview-server fills it in or leaves it. */
class McpAppLayoutTest {
  @Test
  fun `only full opens the full editor, and anything else is the focused canvas`() {
    assertEquals(McpAppLayout.Full, McpAppLayout.parse("full"))
    assertEquals(McpAppLayout.Full, McpAppLayout.parse(" Full "))
    assertEquals(McpAppLayout.Focused, McpAppLayout.parse("focused"))
    // A server that predates the setting leaves the placeholder as it is.
    assertEquals(McpAppLayout.Focused, McpAppLayout.parse("__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__"))
    assertEquals(McpAppLayout.Focused, McpAppLayout.parse(""))
    assertEquals(McpAppLayout.Focused, McpAppLayout.parse(null))
    assertEquals(McpAppLayout.Focused, McpAppLayout.parse("fullscreen"))
  }

  @Test
  fun `the toggle goes each way and back`() {
    assertEquals(McpAppLayout.Full, McpAppLayout.Focused.toggled())
    assertEquals(McpAppLayout.Focused, McpAppLayout.Focused.toggled().toggled())
  }
}
