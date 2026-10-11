package ee.schimke.composeai.uibuilder.mcpapp

/**
 * Which editor the MCP App shows: the canvas alone, or the full desktop editor. The host picks the
 * initial one via the shell (`composeUiBuilderMcpApp.layout`, filled from compose-preview-server's
 * `uiBuilderMcpAppLayout`); the person can switch live from the file bar, keeping design, selection
 * and undo.
 */
enum class McpAppLayout(val wireValue: String) {
  Focused("focused"),
  Full("full");

  fun toggled(): McpAppLayout = if (this == Focused) Full else Focused

  companion object {
    /**
     * The shell's value. Anything but `full` (an unfilled placeholder, a blank, a typo) is
     * [Focused].
     */
    fun parse(value: String?): McpAppLayout =
      if (value?.trim()?.lowercase() == Full.wireValue) Full else Focused
  }
}
