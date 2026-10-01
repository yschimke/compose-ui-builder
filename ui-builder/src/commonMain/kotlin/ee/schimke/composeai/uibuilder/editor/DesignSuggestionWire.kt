package ee.schimke.composeai.uibuilder.editor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The suggestion routes' payloads, as the editor reads them.
 *
 * The routes are the host's (compose-preview-server, over `UiBuilderBranchPort`; see
 * `docs/design/UI_BUILDER_BRANCHES.md` → Suggestions → What the server wires):
 * - `GET /api/ui-builder/v1/designs/{id}/suggestions` → [DesignSuggestionListWire]
 * - `POST /api/ui-builder/v1/designs/{id}/suggestions/{suggestionId}/accept`, body
 *   [DesignSuggestionAcceptWire] → [DesignSuggestionMergeWire] (a refused merge is a report with
 *   `merged: false`, not an error)
 * - `POST /api/ui-builder/v1/designs/{id}/suggestions/{suggestionId}/reject` → the suggestion.
 *
 * Kept in common code rather than beside the browser's `fetch` so the decoding and the words a
 * merge report turns into are tested on the JVM. Tolerant on the way in, as the comment payload is.
 */
object DesignSuggestionCodec {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
  }

  /** The open suggestions, or null when the body is not a suggestion list. */
  fun decodeList(body: String): List<DesignSuggestion>? =
    try {
      json.decodeFromString(DesignSuggestionListWire.serializer(), body).suggestions.map {
        it.toSuggestion()
      }
    } catch (_: Exception) {
      null
    }

  fun encodeAccept(acceptOperationIds: List<String>?): String =
    json.encodeToString(
      DesignSuggestionAcceptWire.serializer(),
      DesignSuggestionAcceptWire(acceptOperationIds),
    )

  /** What an accept did, in words — or null when the body is not a merge report. */
  fun decodeAcceptOutcome(body: String, suggestion: DesignSuggestion): DesignSuggestionOutcome? =
    try {
      json.decodeFromString(DesignSuggestionMergeWire.serializer(), body).toOutcome(suggestion)
    } catch (_: Exception) {
      null
    }
}

@Serializable
data class DesignSuggestionListWire(val suggestions: List<DesignSuggestionWire> = emptyList())

@Serializable
data class DesignSuggestionWire(
  val suggestionId: String,
  val summary: String = "",
  val proposedBy: String = "",
  val proposerKind: String = "agent",
  val displayName: String? = null,
  val createdAtEpochMillis: Long = 0,
  val forkRevision: Long = 0,
  val operationIds: List<String> = emptyList(),
) {
  fun toSuggestion(): DesignSuggestion =
    DesignSuggestion(
      suggestionId = suggestionId,
      summary = summary.ifBlank { suggestionId },
      proposedBy = proposedBy,
      displayName = displayName,
      kind = DesignCommentAuthorKind.ofWire(proposerKind),
      createdAtEpochMillis = createdAtEpochMillis,
      forkRevision = forkRevision,
      operationIds = operationIds,
    )
}

@Serializable data class DesignSuggestionAcceptWire(val acceptOperationIds: List<String>? = null)

/** `UiBuilderBranchMergeReport`, as the host serialises it. */
@Serializable
data class DesignSuggestionMergeWire(
  val merged: Boolean = false,
  val parentRevisionAfter: Long? = null,
  val commands: List<DesignSuggestionMergeCommandWire> = emptyList(),
  val remaining: Int = 0,
  val skippedOperationIds: List<String> = emptyList(),
) {
  fun toOutcome(suggestion: DesignSuggestion): DesignSuggestionOutcome {
    if (merged) {
      return DesignSuggestionOutcome.Accepted(
        suggestionId = suggestion.suggestionId,
        summary = suggestion.summary,
        landed = commands.count { it.status == "APPLIED" },
        atRevision = parentRevisionAfter,
        overwrites = commands.flatMap { it.conflicts }.map { it.describe() },
        skipped = skippedOperationIds.size,
      )
    }
    val stop = commands.lastOrNull { it.status == "REFUSED" }
    return DesignSuggestionOutcome.Refused(
      suggestionId = suggestion.suggestionId,
      summary = suggestion.summary,
      reason = stop?.describeRefusal() ?: "the host refused it.",
      operationId = stop?.operationId,
    )
  }
}

@Serializable
data class DesignSuggestionMergeCommandWire(
  val operationId: String = "",
  val actorId: String = "",
  val status: String = "",
  val conflicts: List<DesignSuggestionConflictWire> = emptyList(),
  val code: String? = null,
  val message: String? = null,
  val nodeId: String? = null,
  val field: String? = null,
) {
  /** Why the run stopped, as a sentence a person can act on. */
  fun describeRefusal(): String {
    val what =
      when (code) {
        "DELETED_NODE",
        "UNKNOWN_NODE" ->
          "edit $operationId changes ${nodeId ?: "a node"}, which is no longer in the design."
        "REVISION_NOT_RETAINED" ->
          "the design was replaced since the suggestion was made, so it cannot be applied."
        "OPERATION_ID_REUSED" -> "edit $operationId is already in the design."
        else ->
          "edit $operationId was refused" +
            (code?.let { " ($it)" } ?: "") +
            (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") +
            "."
      }
    return what
  }
}

@Serializable
data class DesignSuggestionConflictWire(
  val code: String = "",
  val nodeId: String? = null,
  val field: String? = null,
  val overwrittenRevision: Long? = null,
) {
  /** A last-writer-wins notice in words: what the accept overwrote. */
  fun describe(): String {
    val at = overwrittenRevision?.let { " (set at r$it)" }.orEmpty()
    return when (code) {
      "STALE_MOVE" -> "the position of ${nodeId ?: "a node"}$at"
      "STALE_ENVIRONMENT_WRITE" -> "the screen's ${field ?: "settings"}$at"
      else -> "${field ?: "a property"} of ${nodeId ?: "a node"}$at"
    }
  }
}
