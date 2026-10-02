@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResult
import ee.schimke.composeai.uibuilder.client.UiBuilderProtocolHttpClient
import ee.schimke.composeai.uibuilder.client.toRendererDocument
import ee.schimke.composeai.uibuilder.editor.DesignSuggestion
import ee.schimke.composeai.uibuilder.editor.DesignSuggestionCodec
import ee.schimke.composeai.uibuilder.editor.DesignSuggestionOutcome
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import kotlinx.serialization.json.Json

/**
 * The browser half of suggestion mode: list what is proposed for this design, fetch each proposal's
 * document to draw, and accept or reject one.
 *
 * A suggestion is a branch of the design (`docs/design/UI_BUILDER_BRANCHES.md` → Suggestions), so
 * its document is opened through the ordinary protocol request by the suggestion's own id — the
 * branch inherits the design's access, and no new document route is needed. Listing and deciding go
 * through the host's suggestion routes ([DesignSuggestionCodec] lists them); a host without them
 * answers the list with something that is not one, and the editor then draws no section at all.
 */
internal class BrowserSuggestionHost(
  private val designId: String,
  private val http: UiBuilderProtocolHttpClient,
) {
  /** The open suggestions, or null when the host keeps none — no route, a refusal. */
  suspend fun list(): List<DesignSuggestion>? {
    val response = request("GET", suggestionsPath, null) ?: return null
    if (response.status != 200) return null
    return DesignSuggestionCodec.decodeList(response.body)
  }

  /** The design as [suggestionId] would leave it, or null when it could not be opened. */
  suspend fun document(suggestionId: String): UiBuilderDocument? =
    try {
      ((http.execute(OpenDesignRequestV1(suggestionId)) as? UiBuilderHttpResult.Response)?.response
          as? SnapshotResponseV1)
        ?.snapshot
        ?.state
        ?.document
        ?.toRendererDocument()
    } catch (_: Exception) {
      null
    }

  /** Accept [suggestion]: the host replays it onto the design. */
  suspend fun accept(
    suggestion: DesignSuggestion,
    acceptOperationIds: List<String>? = null,
  ): Result {
    val response =
      request(
        "POST",
        "$suggestionsPath/${encodeUriComponent(suggestion.suggestionId)}/accept",
        DesignSuggestionCodec.encodeAccept(acceptOperationIds),
      ) ?: return Result.Failed("The host could not be reached while accepting that suggestion.")
    // A refused merge is a report too (`merged: false`), whatever status the host gave it.
    DesignSuggestionCodec.decodeAcceptOutcome(response.body, suggestion)?.let {
      return Result.Decided(it)
    }
    return Result.Failed(
      errorReason(response)
        ?: "The host answered ${response.status} while accepting that suggestion."
    )
  }

  /** Reject [suggestion]: the host archives it, and the design never sees it. */
  suspend fun reject(suggestion: DesignSuggestion): Result {
    val response =
      request(
        "POST",
        "$suggestionsPath/${encodeUriComponent(suggestion.suggestionId)}/reject",
        "{}",
      ) ?: return Result.Failed("The host could not be reached while rejecting that suggestion.")
    if (response.status in 200..299) {
      return Result.Decided(
        DesignSuggestionOutcome.Rejected(suggestion.suggestionId, suggestion.summary)
      )
    }
    return Result.Failed(
      errorReason(response)
        ?: "The host answered ${response.status} while rejecting that suggestion."
    )
  }

  sealed interface Result {
    data class Decided(val outcome: DesignSuggestionOutcome) : Result

    data class Failed(val reason: String) : Result
  }

  private val suggestionsPath =
    "/api/ui-builder/v1/designs/${encodeUriComponent(designId)}/suggestions"

  private fun errorReason(response: SuggestionHttpResponse): String? =
    try {
        suggestionJson.decodeFromString(SuggestionErrorWire.serializer(), response.body).let {
          it.error ?: it.message
        }
      } catch (_: Exception) {
        null
      }
      ?.takeIf { it.isNotBlank() }

  private suspend fun request(method: String, url: String, body: String?): SuggestionHttpResponse? {
    val encoded =
      try {
        awaitCommentString(
          commentFetch(method, sameOriginRequestUrl(url), body ?: "", body != null)
        )
      } catch (_: Exception) {
        return null
      }
    return try {
      suggestionJson.decodeFromString(SuggestionHttpResponse.serializer(), encoded)
    } catch (_: Exception) {
      null
    }
  }
}

private val suggestionJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

@kotlinx.serialization.Serializable
private data class SuggestionHttpResponse(val status: Int = 0, val body: String = "")

@kotlinx.serialization.Serializable
private data class SuggestionErrorWire(val error: String? = null, val message: String? = null)
