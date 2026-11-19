package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.WATCH_FACE_VALIDATION_DISABLED
import ee.schimke.composeai.uibuilder.editor.WATCH_FACE_VALIDATION_DISABLED_REASON
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationAnswer
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationHost
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationJson
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationReport
import ee.schimke.composeai.uibuilder.editor.WatchInstalls
import ee.schimke.composeai.uibuilder.editor.WatchInstallsAnswer
import kotlinx.serialization.Serializable

/**
 * The Validate section over the design host's routes: `POST`/`GET
 * /api/ui-builder/v1/designs/{id}/watchface-validation?revision=` and the owner's `GET
 * .../watch-installs`. Same-origin and cookie-bearing, as the watch-face preview's `dwf-preview`
 * routes are; a refusal comes back as the host's own words.
 */
internal class BrowserWatchFaceValidationHost(
  private val designId: String,
  override val unavailableReason: String?,
) : WatchFaceValidationHost {
  private val base = "/api/ui-builder/v1/designs/${encodeUriComponent(designId)}"

  override suspend fun latest(revision: Long): WatchFaceValidationAnswer {
    val response = request("GET", "$base/watchface-validation?revision=$revision")
    return when (response.status) {
      in 200..299 -> WatchFaceValidationAnswer.Ran(report(response.body))
      404 -> WatchFaceValidationAnswer.NeverRun
      else ->
        disabled(response)
          ?: WatchFaceValidationAnswer.Refused(refusal(response, "a validation read"))
    }
  }

  override suspend fun run(revision: Long): WatchFaceValidationAnswer {
    val response = request("POST", "$base/watchface-validation?revision=$revision", body = "{}")
    return when (response.status) {
      in 200..299 -> WatchFaceValidationAnswer.Ran(report(response.body))
      else ->
        disabled(response)
          ?: WatchFaceValidationAnswer.Refused(refusal(response, "a validation run"))
    }
  }

  override suspend fun installs(): WatchInstallsAnswer {
    val response = request("GET", "$base/watch-installs")
    return when (response.status) {
      in 200..299 ->
        WatchInstallsAnswer.Listed(
          WatchFaceValidationJson.decodeFromString(WatchInstalls.serializer(), response.body)
            .installs,
        )
      401,
      403 -> WatchInstallsAnswer.Refused("Only the design's owner sees which watches it is on.")
      404 -> WatchInstallsAnswer.Listed(emptyList())
      else -> WatchInstallsAnswer.Refused(refusal(response, "a watch installs read"))
    }
  }

  /** A 503 naming [WATCH_FACE_VALIDATION_DISABLED] in its body, whichever field carries it. */
  private fun disabled(response: FetchedResponse): WatchFaceValidationAnswer.Disabled? =
    WatchFaceValidationAnswer.Disabled(WATCH_FACE_VALIDATION_DISABLED_REASON).takeIf {
      response.status == 503 && WATCH_FACE_VALIDATION_DISABLED in response.body
    }

  private fun report(body: String): WatchFaceValidationReport =
    WatchFaceValidationJson.decodeFromString(WatchFaceValidationReport.serializer(), body)

  private fun refusal(response: FetchedResponse, what: String): String =
    when (response.status) {
      401,
      403 -> "Sign in with an account that can edit this design to validate it."
      else -> runCatching {
          WatchFaceValidationJson.decodeFromString(ValidationRefusal.serializer(), response.body)
            .let { it.reasons.joinToString(" ").ifBlank { it.error.orEmpty() } }
        }
          .getOrNull()
          ?.takeIf { it.isNotBlank() } ?: "the host answered ${response.status} to $what"
    }

  private suspend fun request(method: String, path: String, body: String? = null): FetchedResponse =
    WatchFaceValidationJson.decodeFromString(
      FetchedResponse.serializer(),
      awaitCommentString(
        commentFetch(method, sameOriginRequestUrl(path), body.orEmpty(), body != null),
      ),
    )
}

@Serializable private class FetchedResponse(val status: Int, val body: String = "")

@Serializable
private class ValidationRefusal(val reasons: List<String> = emptyList(), val error: String? = null)
