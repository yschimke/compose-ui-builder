package ee.schimke.composeai.uibuilder.intellij

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteAgentPromptTest {
  private val origin = URI("https://preview.example")
  private val mcp = URI("https://preview.example/mcp")

  @Test
  fun namesAWellFormedDesign() {
    val prompt = remoteAgentPrompt(origin, mcp, "settings-v2.draft")
    assertTrue(prompt.contains("design `settings-v2.draft` at https://preview.example"), prompt)
    assertTrue(prompt.contains("connect to https://preview.example/mcp"), prompt)
  }

  @Test
  fun leavesOutAnIdOutsideTheDesignIdRule() {
    listOf("", "-leading", "has space", "a`b", "line\nbreak", "x".repeat(65)).forEach { id ->
      val prompt = remoteAgentPrompt(origin, mcp, id)
      if (id.isNotEmpty()) assertFalse(prompt.contains(id), prompt)
      assertTrue(prompt.contains("ask which design to open"), prompt)
      assertTrue(prompt.contains("Do not ask for or reuse the IDE's bearer token."), prompt)
    }
  }
}
