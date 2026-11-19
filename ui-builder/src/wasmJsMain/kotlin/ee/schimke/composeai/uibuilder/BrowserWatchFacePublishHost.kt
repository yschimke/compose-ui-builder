package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.WATCH_FACE_PUBLISH_OWNER_ONLY
import ee.schimke.composeai.uibuilder.editor.WatchFacePublication
import ee.schimke.composeai.uibuilder.editor.WatchFacePublicationAnswer
import ee.schimke.composeai.uibuilder.editor.WatchFacePublishHost
import ee.schimke.composeai.uibuilder.editor.WatchFacePushCapability
import ee.schimke.composeai.uibuilder.editor.WatchFaceValidationJson
import kotlinx.serialization.Serializable

/**
 * "Test on your watch" over the design host's push routes: the capability rides on the
 * `dwf-preview/status` the watch-face preview already reads, and the publication is
 * `GET`/`PUT`/`DELETE …/watch-face-publication` (owner only, in their own session).
 */
internal class BrowserWatchFacePublishHost(
  private val designId: String,
  override val unavailableReason: String?,
) : WatchFacePublishHost {
  private val base = "/api/ui-builder/v1/designs/${encodeUriComponent(designId)}"
  private var publicationPath = "$base/watch-face-publication"

  override suspend fun capability(): WatchFacePushCapability? {
    val response = request("GET", "$base/dwf-preview/status")
    if (response.status !in 200..299) return null
    val capability =
      WatchFaceValidationJson.decodeFromString(PreviewStatus.serializer(), response.body)
        .watchFacePush ?: return null
    // The server names its own route; keep to same-origin API paths.
    capability.publicationPath.takeIf { it.startsWith("/api/") }?.let { publicationPath = it }
    return capability
  }

  override suspend fun publication() = answer(request("GET", publicationPath))

  override suspend fun publish(visibility: String) =
    answer(
      request(
        "PUT",
        publicationPath,
        WatchFaceValidationJson.encodeToString(
          VisibilityBody.serializer(),
          VisibilityBody(visibility),
        ),
      ),
    )

  override suspend fun unpublish() = answer(request("DELETE", publicationPath))

  private fun answer(response: FetchedPublishResponse): WatchFacePublicationAnswer {
    if (response.status in 200..299) {
      return WatchFacePublicationAnswer.Ok(
        WatchFaceValidationJson.decodeFromString(WatchFacePublication.serializer(), response.body),
      )
    }
    val refusal =
      runCatching {
        WatchFaceValidationJson.decodeFromString(PublishRefusal.serializer(), response.body)
      }
        .getOrNull() ?: PublishRefusal()
    val code = refusal.error ?: refusal.code
    return when {
      code == "packaging_failed" ->
        WatchFacePublicationAnswer.PackagingFailed(
          refusal.message ?: "The packager refused this face.",
          refusal.diagnostics,
        )
      code == "owner_session_required" || code == "owner_required" ->
        WatchFacePublicationAnswer.Refused(WATCH_FACE_PUBLISH_OWNER_ONLY)
      code == "design_not_public" ->
        WatchFacePublicationAnswer.Refused(
          "Only a public design can go on every watch. Make the design public, or publish it to " +
            "your own watches.",
        )
      response.status == 401 ->
        WatchFacePublicationAnswer.Refused("Sign in as the design's owner to put it on a watch.")
      response.status == 429 ->
        WatchFacePublicationAnswer.Refused("Too many publishes just now; try again in a minute.")
      else ->
        WatchFacePublicationAnswer.Refused(
          refusal.message
            ?: refusal.reasons.joinToString(" ").takeIf { it.isNotBlank() }
            ?: "the host answered ${response.status} to a watch-face publication request",
        )
    }
  }

  private suspend fun request(
    method: String,
    path: String,
    body: String? = null,
  ): FetchedPublishResponse =
    WatchFaceValidationJson.decodeFromString(
      FetchedPublishResponse.serializer(),
      awaitCommentString(
        commentFetch(method, sameOriginRequestUrl(path), body.orEmpty(), body != null),
      ),
    )
}

@Serializable private class FetchedPublishResponse(val status: Int, val body: String = "")

@Serializable private class PreviewStatus(val watchFacePush: WatchFacePushCapability? = null)

@Serializable private class VisibilityBody(val visibility: String)

@Serializable
private class PublishRefusal(
  val error: String? = null,
  val code: String? = null,
  val message: String? = null,
  val diagnostics: List<String> = emptyList(),
  val reasons: List<String> = emptyList(),
)
