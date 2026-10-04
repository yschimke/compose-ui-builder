package ee.schimke.composeai.uibuilder.editor

/** Browser-local preferences. Document instructions override the general instructions when set. */
data class UiBuilderAgentPreferences(
  val connectedBefore: Boolean = false,
  val hintDismissed: Boolean = false,
  val generalInstructions: String = "",
  val documentInstructions: String = "",
) {
  fun instructions(): String = documentInstructions.ifBlank { generalInstructions }
}

/** Origin and clipboard belong to the host; the editor owns the invitation and prompt panel. */
interface UiBuilderAgentHost {
  val preferences: UiBuilderAgentPreferences
  /** Null means this host has not supplied presence, rather than an empty room. */
  val agents: List<UiBuilderCollaborator>?
  val viewers: List<UiBuilderCollaborator>
    get() = emptyList()

  fun prompt(includeSetup: Boolean, instructions: String): String

  fun save(preferences: UiBuilderAgentPreferences): String?

  suspend fun copy(text: String): String

  fun openSetup()

  fun connectVsCode()
}
