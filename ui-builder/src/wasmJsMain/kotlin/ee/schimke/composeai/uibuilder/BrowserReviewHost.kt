@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.DesignCommentAuthorKind
import ee.schimke.composeai.uibuilder.editor.DesignReview
import ee.schimke.composeai.uibuilder.editor.DesignReviewDecision
import ee.schimke.composeai.uibuilder.editor.DesignReviewVerdict
import kotlinx.serialization.json.Json

/**
 * The browser half of the review section: read who approved what, and record this person's verdict.
 *
 * The host's review routes (compose-preview-server#1255) take the decider from the credential, so a
 * verdict recorded from this page is a person's — the one an agent waiting with
 * `ui_builder_await_decision` is waiting for. There is no feed: a verdict is rare, the page reloads
 * the record after recording one and whenever the revision on screen changes, and an agent's
 * verdict reaches a person through the comment it is expected to leave beside it.
 */
internal class BrowserReviewHost(private val designId: String) {
  /** The review record, or null when the host would not say — no review routes, a refusal. */
  suspend fun load(): DesignReview? {
    val response = request("GET", "$designPath/review", null) ?: return null
    if (response.status != 200) return null
    return try {
      reviewJson.decodeFromString(ReviewWire.serializer(), response.body).toReview()
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Record [verdict] on [revision]. Returns the review now in force, or a sentence for the panel.
   *
   * `decisionId` is generated per click, so a double-click records two verdicts rather than one
   * replayed — each is a person pressing a button — while a retried request inside one click would
   * carry the same id.
   */
  suspend fun decide(
    revision: Long,
    verdict: DesignReviewVerdict,
    note: String?,
    displayName: String,
  ): Result {
    val response =
      request(
        "POST",
        "$designPath/decisions",
        reviewJson.encodeToString(
          DecisionWire.serializer(),
          DecisionWire(
            revision = revision,
            verdict = verdict.wire,
            note = note,
            displayName = displayName.ifBlank { null },
          ),
        ),
      ) ?: return Result.Refused("The host could not be reached while recording that verdict.")
    if (response.status in 200..299) {
      return try {
        Result.Recorded(
          reviewJson.decodeFromString(ReviewWire.serializer(), response.body).toReview()
        )
      } catch (_: Exception) {
        Result.Refused("The host recorded the verdict but answered something unreadable.")
      }
    }
    val reason =
      try {
        reviewJson.decodeFromString(ReviewErrorWire.serializer(), response.body).let {
          it.error ?: it.message
        }
      } catch (_: Exception) {
        null
      }
    return Result.Refused(
      reason?.takeIf { it.isNotBlank() }
        ?: "The host answered ${response.status} while recording that verdict."
    )
  }

  sealed interface Result {
    data class Recorded(val review: DesignReview) : Result

    data class Refused(val reason: String) : Result
  }

  private val designPath = "/api/ui-builder/v1/designs/$designId"

  private suspend fun request(method: String, url: String, body: String?): ReviewHttpResponse? {
    val encoded =
      try {
        awaitCommentString(
          commentFetch(method, sameOriginRequestUrl(url), body ?: "", body != null)
        )
      } catch (_: Exception) {
        return null
      }
    return try {
      reviewJson.decodeFromString(ReviewHttpResponse.serializer(), encoded)
    } catch (_: Exception) {
      null
    }
  }
}

private fun ReviewWire.toReview() =
  DesignReview(
    decisions =
      decisions.mapNotNull { wire ->
        DesignReviewDecision(
          revision = wire.revision,
          verdict = DesignReviewVerdict.ofWire(wire.verdict) ?: return@mapNotNull null,
          decidedBy = wire.decidedBy,
          displayName = wire.displayName,
          kind = DesignCommentAuthorKind.ofWire(wire.deciderKind),
          note = wire.note,
          decidedAtEpochMillis = wire.decidedAtEpochMillis,
        )
      }
  )

/** Tolerant on the way in, as the comment payload is. */
private val reviewJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = false
  explicitNulls = false
}

@kotlinx.serialization.Serializable
private data class ReviewHttpResponse(val status: Int = 0, val body: String = "")

@kotlinx.serialization.Serializable
private data class ReviewErrorWire(val error: String? = null, val message: String? = null)

@kotlinx.serialization.Serializable
private data class ReviewWire(val decisions: List<DecisionRecordWire> = emptyList())

@kotlinx.serialization.Serializable
private data class DecisionRecordWire(
  val revision: Long = 0,
  val verdict: String = "",
  val decidedBy: String = "",
  val deciderKind: String = "human",
  val displayName: String? = null,
  val note: String? = null,
  val decidedAtEpochMillis: Long = 0,
)

@kotlinx.serialization.Serializable
private data class DecisionWire(
  val revision: Long,
  val verdict: String,
  val note: String? = null,
  val displayName: String? = null,
)
