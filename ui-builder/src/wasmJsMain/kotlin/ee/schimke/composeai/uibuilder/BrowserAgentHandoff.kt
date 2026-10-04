@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.UiBuilderAgentPreferences
import ee.schimke.composeai.uibuilder.editor.UiBuilderCollaborator
import ee.schimke.composeai.uibuilder.editor.UiBuilderParticipantKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val AGENT_SETUP_URL = "https://yschimke.github.io/compose-ui-builder/#get-started"

@Serializable
internal data class BrowserAgentPreferences(
  val connectedBefore: Boolean = false,
  val hintDismissed: Boolean = false,
  val generalInstructions: String = "",
)

private val agentJson = Json { ignoreUnknownKeys = true }

internal fun readAgentPreferences(designId: String): UiBuilderAgentPreferences {
  val general = runCatching {
    agentJson.decodeFromString<BrowserAgentPreferences>(
      readAgentPreference("ui-builder.agent.preferences")
    )
  }
    .getOrDefault(BrowserAgentPreferences())
  return UiBuilderAgentPreferences(
    general.connectedBefore,
    general.hintDismissed,
    general.generalInstructions,
    readAgentPreference("ui-builder.agent.document.$designId"),
  )
}

internal fun saveAgentPreferences(
  designId: String,
  preferences: UiBuilderAgentPreferences,
): String? {
  val general =
    agentJson.encodeToString(
      BrowserAgentPreferences.serializer(),
      BrowserAgentPreferences(
        preferences.connectedBefore,
        preferences.hintDismissed,
        preferences.generalInstructions,
      ),
    )
  return writeAgentPreferences(designId, general, preferences.documentInstructions).takeIf {
    it.isNotEmpty()
  }
}

@JsFun("(key) => { try { return localStorage.getItem(key) || ''; } catch (_) { return ''; } }")
private external fun readAgentPreference(key: String): String

@JsFun(
  """(designId, general, instructions) => {
  try {
    localStorage.setItem('ui-builder.agent.preferences', general);
    localStorage.setItem('ui-builder.agent.document.' + designId, instructions);
    return '';
  } catch (_) { return 'Could not save prompt preferences in this browser'; }
}"""
)
private external fun writeAgentPreferences(
  designId: String,
  general: String,
  instructions: String,
): String

@Serializable
internal data class AgentPresencePayload(val agents: List<AgentPresenceParticipant> = emptyList())

@Serializable
internal data class AgentPresenceParticipant(
  val id: String,
  val name: String,
  val model: String? = null,
) {
  fun collaborator(): UiBuilderCollaborator =
    UiBuilderCollaborator(
      actorId = id,
      displayName = name,
      colorArgbHex = "#FF6750A4",
      selectedNodeIds = emptyList(),
      kind = UiBuilderParticipantKind.Agent,
      modelName = model,
    )
}

internal suspend fun fetchAgentPresence(designId: String): List<UiBuilderCollaborator> =
  agentJson
    .decodeFromString<AgentPresencePayload>(
      fetchText("/api/ui-builder/v1/designs/${encodeUriComponent(designId)}/agents")
    )
    .agents
    .map { it.collaborator() }

/** A user gesture opens the documented MCP install link, with only the public endpoint. */
@JsFun(
  """(endpoint, name) => {
  const config = {name, type: 'http', url: endpoint};
  globalThis.location.href = 'vscode:mcp/install?' + encodeURIComponent(JSON.stringify(config));
}"""
)
internal external fun connectAgentVsCode(endpoint: String, name: String)

@JsFun("(url) => { globalThis.open(url, '_blank', 'noopener,noreferrer'); }")
internal external fun openAgentSetup(url: String)

@JsFun(
  """(designId, endpoint, guide, prompt) => {
  globalThis.__uiBuilderAgent = {designId, mcpEndpoint: endpoint, serverName: 'compose-preview-catalog', setupUrl: guide, prompt};
  document.querySelector('meta[name="ui-builder-mcp-endpoint"]')?.setAttribute('content', endpoint);
}"""
)
internal external fun publishAgentHandoff(
  designId: String,
  endpoint: String,
  guide: String,
  prompt: String,
)
