package ee.schimke.composeai.uibuilder.mcpapp

/**
 * Which editor the MCP App shows: the canvas alone, or the full desktop editor.
 *
 * The host chooses the first one through the shell (`composeUiBuilderMcpApp.layout`, which
 * compose-preview-server fills from its `uiBuilderMcpAppLayout` setting); the person can switch
 * either way from the file bar, live, without losing the design, the selection or the undo history.
 */
enum class McpAppLayout(val wireValue: String) {
  Focused("focused"),
  Full("full");

  fun toggled(): McpAppLayout = if (this == Focused) Full else Focused

  companion object {
    /**
     * The shell's value. Anything but `full` — an unfilled placeholder from a server that predates
     * the setting, a blank, a typo — is [Focused], the layout a chat panel is for.
     */
    fun parse(value: String?): McpAppLayout =
      if (value?.trim()?.lowercase() == Full.wireValue) Full else Focused
  }
}
