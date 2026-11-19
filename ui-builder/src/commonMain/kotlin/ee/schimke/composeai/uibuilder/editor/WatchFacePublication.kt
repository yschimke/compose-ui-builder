package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Where a watch-face design is on watches: `GET`/`PUT`/`DELETE
 * /api/ui-builder/v1/designs/{id}/watch-face-publication` (compose-preview-server's
 * `compose-preview/watchface-push-publication/v1`). A publication pins [revision]: edits reach
 * watches only when the owner publishes again.
 */
@Serializable
data class WatchFacePublication(
  val designId: String = "",
  val published: Boolean = false,
  /** The APK `versionCode`; moves only when the face itself changes. */
  val version: Long? = null,
  val revision: Long? = null,
  /** Who it reaches now: `public`, `private`, or null when nobody. */
  val visibility: String? = null,
  /** The owner's own choice, which [visibility] can fall back from. */
  val requestedVisibility: String? = null,
  /** A public design listed on watches without an explicit publish. */
  val automatic: Boolean = false,
  val faceName: String? = null,
  val packageName: String? = null,
  val apk: WatchFacePublicationApk? = null,
  /** Why this host cannot build APKs, when [WatchFacePublicationApk.status] is `unavailable`. */
  val packaging: WatchFacePackaging? = null,
  /** The packager's lines for the latest attempt it refused, beside what is still on watches. */
  val refusal: List<String> = emptyList(),
)

@Serializable
data class WatchFacePublicationApk(
  /** `ready`, `pending`, `unavailable` or `failed`. */
  val status: String = "pending",
  val sha256: String? = null,
  val sizeBytes: Long? = null,
)

@Serializable
data class WatchFacePackaging(
  val available: Boolean = false,
  val code: String? = null,
  val reason: String? = null,
)

/**
 * `watchFacePush` on the `dwf-preview/status` the editor already reads: present only when this host
 * can build push APKs for this design's catalog; [canPublish] is whether this caller may.
 */
@Serializable
data class WatchFacePushCapability(
  val publicationPath: String = "",
  val canPublish: Boolean = false,
)

const val WATCH_FACE_VISIBILITY_PUBLIC = "public"
const val WATCH_FACE_VISIBILITY_PRIVATE = "private"

/** The Faces app, the marketplace that installs published faces on a Wear OS 6 watch. */
const val FACES_APP_NAME = "Faces"
const val FACES_APP_PACKAGE = "ee.schimke.watchface.push"
const val FACES_APP_PLAY_URL = "https://play.google.com/store/apps/details?id=$FACES_APP_PACKAGE"

/** Where a person approves the code a watch's Faces app shows, and lists their linked watches. */
const val WATCH_LINK_PATH = "/link"

/** What a host answered for one publication read or change. */
sealed interface WatchFacePublicationAnswer {
  data class Ok(val publication: WatchFacePublication) : WatchFacePublicationAnswer

  /** `422 packaging_failed`: the packager or Google's validator refused the face. */
  data class PackagingFailed(val message: String, val diagnostics: List<String>) :
    WatchFacePublicationAnswer

  /** A refusal the person can do nothing about from here, as a sentence. */
  data class Refused(val reason: String) : WatchFacePublicationAnswer
}

/** The server half of "Test on your watch". */
interface WatchFacePublishHost {
  /** Why this design cannot be published from here at all (kept in this browser), or null. */
  val unavailableReason: String?
    get() = null

  /** The status route's `watchFacePush`, or null when this host cannot publish this design. */
  suspend fun capability(): WatchFacePushCapability?

  suspend fun publication(): WatchFacePublicationAnswer

  suspend fun publish(visibility: String): WatchFacePublicationAnswer

  suspend fun unpublish(): WatchFacePublicationAnswer
}

/** "Test on your watch", as it draws. */
sealed interface WatchFacePublishView {
  /** No `watchFacePush` capability: the host cannot build push APKs, so nothing is offered. */
  data object Hidden : WatchFacePublishView

  data object Loading : WatchFacePublishView

  /** Offered, but not to this caller (not the owner, or not in their own session). */
  data class NotAllowed(val reason: String) : WatchFacePublishView

  data class Ready(
    val publication: WatchFacePublication,
    /** A publish or unpublish in flight. */
    val busy: Boolean = false,
    /** The last change's refusal, kept until the next one. */
    val failure: WatchFacePublishFailure? = null,
  ) : WatchFacePublishView
}

data class WatchFacePublishFailure(val message: String, val diagnostics: List<String> = emptyList())

/** Why a caller who is not the owner, in their own session, gets no publish action. */
const val WATCH_FACE_PUBLISH_OWNER_ONLY =
  "Only the design's owner can put it on watches, signed in as themselves. An agent or a shared " +
    "session cannot, because a published face outlives the grant."

/** Holds "Test on your watch" for one open design; changes run in the design's [scope]. */
class WatchFacePublishController(
  private val host: WatchFacePublishHost,
  private val scope: CoroutineScope,
) {
  private val _view = MutableStateFlow<WatchFacePublishView>(WatchFacePublishView.Loading)
  val view: StateFlow<WatchFacePublishView> = _view.asStateFlow()

  /** Reads the capability and, where it may, the publication. */
  suspend fun load() {
    host.unavailableReason?.let {
      _view.value = WatchFacePublishView.NotAllowed(it)
      return
    }
    val capability =
      attempt { host.capability() }.getOrNull()
        ?: run {
          _view.value = WatchFacePublishView.Hidden
          return
        }
    if (!capability.canPublish) {
      _view.value = WatchFacePublishView.NotAllowed(WATCH_FACE_PUBLISH_OWNER_ONLY)
      return
    }
    // A change that started while this read was out wins.
    if ((_view.value as? WatchFacePublishView.Ready)?.busy == true) return
    _view.value =
      attempt { host.publication() }
        .fold(
          onSuccess = { it.toView(previous = null) },
          onFailure = {
            WatchFacePublishView.Ready(
              WatchFacePublication(),
              failure = WatchFacePublishFailure(it.message ?: "the publication could not be read"),
            )
          },
        )
  }

  fun publish(visibility: String) = change { host.publish(visibility) }

  fun unpublish() = change { host.unpublish() }

  private fun change(request: suspend () -> WatchFacePublicationAnswer) {
    val ready = _view.value as? WatchFacePublishView.Ready ?: return
    if (ready.busy) return
    _view.value = ready.copy(busy = true, failure = null)
    scope.launch {
      _view.value =
        attempt { request() }
          .fold(
            onSuccess = { it.toView(previous = ready.publication) },
            onFailure = {
              ready.copy(
                failure = WatchFacePublishFailure(it.message ?: "the request did not complete"),
              )
            },
          )
    }
  }

  private fun WatchFacePublicationAnswer.toView(
    previous: WatchFacePublication?,
  ): WatchFacePublishView =
    when (this) {
      is WatchFacePublicationAnswer.Ok -> WatchFacePublishView.Ready(publication)
      is WatchFacePublicationAnswer.PackagingFailed ->
        WatchFacePublishView.Ready(
          previous ?: WatchFacePublication(),
          failure = WatchFacePublishFailure(message, diagnostics),
        )
      is WatchFacePublicationAnswer.Refused ->
        if (previous == null && reason == WATCH_FACE_PUBLISH_OWNER_ONLY)
          WatchFacePublishView.NotAllowed(reason)
        else
          WatchFacePublishView.Ready(
            previous ?: WatchFacePublication(),
            failure = WatchFacePublishFailure(reason),
          )
    }

  private inline fun <T> attempt(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      Result.failure(failure)
    }
}

/**
 * "Test on your watch", where the host offers it: the browser app provides one for a server
 * watch-face design; null elsewhere, where the Issues panel leaves the step out.
 */
val LocalWatchFacePublish = staticCompositionLocalOf<WatchFacePublishController?> { null }
