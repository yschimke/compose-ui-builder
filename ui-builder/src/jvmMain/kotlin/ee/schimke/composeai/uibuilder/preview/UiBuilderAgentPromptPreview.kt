package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.AgentPromptContents
import ee.schimke.composeai.uibuilder.editor.AgentToolbarAction
import ee.schimke.composeai.uibuilder.editor.UiBuilderAgentHost
import ee.schimke.composeai.uibuilder.editor.UiBuilderAgentPreferences
import ee.schimke.composeai.uibuilder.editor.UiBuilderCollaborator
import ee.schimke.composeai.uibuilder.editor.UiBuilderParticipantKind

@Preview(widthDp = 440, heightDp = 740)
@Preview(name = "Mobile", widthDp = 320, heightDp = 680)
@Composable
fun UiBuilderAgentPromptPreview() {
  val host =
    object : UiBuilderAgentHost {
      override val preferences = UiBuilderAgentPreferences(connectedBefore = true)
      override val agents =
        listOf(
          UiBuilderCollaborator(
            "agent",
            "Codex",
            "#FF6750A4",
            emptyList(),
            UiBuilderParticipantKind.Agent,
            "Reported model",
          )
        )

      override fun prompt(includeSetup: Boolean, instructions: String) =
        "Work with this Compose UI Builder design:\nhttps://preview.coo.ee/ui-builder/podcast-library\n\nUse compose-preview-catalog and follow the compose-ui-builder skill. Read the design and comments before editing, then verify the Compose export."

      override fun save(preferences: UiBuilderAgentPreferences): String? = null

      override suspend fun copy(text: String) = "Preview"

      override fun openSetup() {}

      override fun connectVsCode() {}
    }
  Surface(color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.padding(20.dp)) {
      AgentToolbarAction(host) {}
      AgentPromptContents(host) {}
    }
  }
}
