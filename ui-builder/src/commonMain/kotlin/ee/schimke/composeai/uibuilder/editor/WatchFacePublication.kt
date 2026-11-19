package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Where a watch-face design is on watches: `GET`/`PUT`/`DELETE
 * /api/ui-builder/v1/designs/{id}/watch-face-publication` (compose-preview-server's
 * `compose-preview/watchface-push-publication/v1`). A publication pins [revision]: edits reach
 * watches only when the owner publishes again.
 */
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
  /** The background build a publish (or an automatic listing) started; null when none is known. */
  val build: WatchFacePublicationBuild? = null,
)

/**
 * A publish answers `202` at once and builds in the background: where that build is. Every field
 * but [state] is optional, and a state this editor does not know is shown as sent.
 */
data class WatchFacePublicationBuild(
  /** `pending`, `queued`, `building`, `built`, `refused` or `failed`. */
  val state: String,
  /** Why it is in this state, in the server's words, where it says. */
  val reason: String? = null,
  /** `pending`: when the debounced build is due. */
  val scheduledAtEpochMillis: Long? = null,
  /** `queued`: builds ahead of this one in the server's one-at-a-time packager queue. */
  val queueAhead: Int? = null,
  /** `building`: what the packager is doing now, in its words. */
  val stage: String? = null,
  val startedAtEpochMillis: Long? = null,
  val elapsedMillis: Long? = null,
  /** `built`: the version now on watches. */
  val version: Long? = null,
  /** `refused`: the packager's lines. */
  val diagnostics: List<String> = emptyList(),
  /** `failed`: a failure around the packager, tried again at this time (automatic builds). */
  val nextRetryAtEpochMillis: Long? = null,
  /** A build of the automatic listing of a public design, rather than of an owner's publish. */
  val automatic: Boolean = false,
)

const val BUILD_PENDING = "pending"
const val BUILD_QUEUED = "queued"
const val BUILD_BUILDING = "building"
const val BUILD_BUILT = "built"
const val BUILD_REFUSED = "refused"
const val BUILD_FAILED = "failed"

/**
 * How often a build that is queued or building is read again: gentle (builds take minutes), and
 * well under the server's 30 requests a minute per account.
 */
const val BUILD_POLL_MILLIS = 5_000L

/** The longest a scheduled re-read waits, so a far-off build is still noticed when it starts. */
private const val BUILD_WAKE_MAX_MILLIS = 10 * 60_000L

/**
 * The status chip's words for [build], against [nowMillis]: "Builds in 8 min", "Queued, 1 ahead",
 * "Packaging…", "On your watch: v7", "Refused: <first diagnostic>". Null when there is no build to
 * talk about.
 */
fun buildChipText(
  build: WatchFacePublicationBuild?,
  nowMillis: Long,
  publishedVersion: Long?,
): String? {
  build ?: return null
  return when (build.state) {
    BUILD_PENDING ->
      build.scheduledAtEpochMillis
        ?.let { minutesUntil(it, nowMillis) }
        ?.let { if (it <= 0) "Builds in under a minute" else "Builds in $it min" }
        ?: "Build scheduled"
    BUILD_QUEUED ->
      when (val ahead = build.queueAhead) {
        null -> "Queued"
        0 -> "Queued, next"
        else -> "Queued, $ahead ahead"
      }
    BUILD_BUILDING ->
      buildString {
        append(build.stage?.replaceFirstChar { it.uppercase() } ?: "Packaging")
        append("…")
        build.elapsedMillis?.let { append(" ${it / 1000} s") }
      }
    BUILD_BUILT -> (build.version ?: publishedVersion)?.let { "On your watch: v$it" } ?: "Built"
    BUILD_REFUSED ->
      build.diagnostics.firstOrNull()?.let { "Refused: ${it.take(CHIP_DIAGNOSTIC_CHARS)}" }
        ?: "Refused"
    BUILD_FAILED ->
      build.nextRetryAtEpochMillis
        ?.let { minutesUntil(it, nowMillis) }
        ?.let { if (it <= 0) "Build failed, retrying" else "Build failed, retrying in $it min" }
        ?: "Build failed"
    else -> build.state
  }
}

/** Whether the chip is reporting a problem. */
fun WatchFacePublicationBuild.isTrouble(): Boolean = state == BUILD_REFUSED || state == BUILD_FAILED

/** Whether the server is working on it now (the only states that are polled). */
fun WatchFacePublicationBuild.isActive(): Boolean = state == BUILD_QUEUED || state == BUILD_BUILDING

private const val CHIP_DIAGNOSTIC_CHARS = 60

private fun minutesUntil(atMillis: Long, nowMillis: Long): Long =
  ((atMillis - nowMillis).coerceAtLeast(0) + 59_999) / 60_000

data class WatchFacePublicationApk(
  /** `ready`, `pending`, `unavailable` or `failed`. */
  val status: String = "pending",
  val sha256: String? = null,
  val sizeBytes: Long? = null,
  /** `unavailable` or `failed`: why, as a code and a sentence, and the packager's lines. */
  val code: String? = null,
  val reason: String? = null,
  val diagnostics: List<String> = emptyList(),
)

data class WatchFacePackaging(
  val available: Boolean = false,
  val code: String? = null,
  val reason: String? = null,
)

/**
 * `watchFacePush` on the `dwf-preview/status` the editor already reads: present only when this host
 * can build push APKs for this design's catalog; [canPublish] is whether this caller may.
 */
data class WatchFacePushCapability(
  val publicationPath: String = "",
  val canPublish: Boolean = false,
)

/**
 * The publication as `watch-face-publication` answers it, read by hand like the validation report
 * (see [parseWatchFaceValidationReport]) and as leniently.
 */
fun parseWatchFacePublication(text: String): WatchFacePublication =
  Json.parseToJsonElement(text).jsonObject.let { o ->
    WatchFacePublication(
      designId = o.fieldText("designId").orEmpty(),
      published = o.fieldBool("published") ?: false,
      version = o.fieldLong("version"),
      revision = o.fieldLong("revision"),
      visibility = o.fieldText("visibility"),
      requestedVisibility = o.fieldText("requestedVisibility"),
      automatic = o.fieldBool("automatic") ?: false,
      faceName = o.fieldText("faceName"),
      packageName = o.fieldText("packageName"),
      apk =
        (o["apk"] as? JsonObject)?.let { a ->
          WatchFacePublicationApk(
            status = a.fieldText("status") ?: "pending",
            sha256 = a.fieldText("sha256"),
            sizeBytes = a.fieldLong("sizeBytes"),
            code = a.fieldText("code"),
            reason = a.fieldText("reason"),
            diagnostics = a.fieldStrings("diagnostics"),
          )
        },
      packaging =
        (o["packaging"] as? JsonObject)?.let { p ->
          WatchFacePackaging(
            available = p.fieldBool("available") ?: false,
            code = p.fieldText("code"),
            reason = p.fieldText("reason"),
          )
        },
      refusal = o.fieldStrings("refusal"),
      build =
        (o["build"] as? JsonObject)?.let { b ->
          WatchFacePublicationBuild(
            state = b.fieldText("state").orEmpty(),
            reason = b.fieldText("reason"),
            scheduledAtEpochMillis = b.fieldLong("scheduledAtEpochMillis"),
            queueAhead = b.fieldInt("queueAhead"),
            stage = b.fieldText("stage"),
            startedAtEpochMillis = b.fieldLong("startedAtEpochMillis"),
            elapsedMillis = b.fieldLong("elapsedMillis"),
            version = b.fieldLong("version"),
            diagnostics = b.fieldStrings("diagnostics"),
            nextRetryAtEpochMillis = b.fieldLong("nextRetryAtEpochMillis"),
            automatic = b.fieldBool("automatic") ?: false,
          )
        },
    )
  }

/** The `watchFacePush` of a `dwf-preview/status` answer, or null when the status names none. */
fun parseWatchFacePushCapability(statusText: String): WatchFacePushCapability? =
  (Json.parseToJsonElement(statusText).jsonObject["watchFacePush"] as? JsonObject)?.let {
    WatchFacePushCapability(
      publicationPath = it.fieldText("publicationPath").orEmpty(),
      canPublish = it.fieldBool("canPublish") ?: false,
    )
  }

internal fun JsonObject.fieldStrings(key: String): List<String> =
  (get(key) as? JsonArray)?.mapNotNull { it.fieldText() }.orEmpty()

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
  /** Epoch millis; a parameter so tests can hold time still. */
  private val now: () -> Long = ::epochMillisNow,
) {
  /** The one scheduled re-read of a background build; replaced by every new answer. */
  private var follow: Job? = null

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
          onSuccess = { it.toView(previous = null).also(::followBuild) },
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
            onSuccess = { it.toView(previous = ready.publication).also(::followBuild) },
            onFailure = {
              ready.copy(
                failure = WatchFacePublishFailure(it.message ?: "the request did not complete"),
              )
            },
          )
    }
  }

  /**
   * Re-reads the publication without disturbing the editor: when the page regains focus, say. A
   * change in flight, or a step the caller may not use, is left alone.
   */
  fun refresh() {
    val ready = _view.value as? WatchFacePublishView.Ready ?: return
    if (ready.busy) return
    scope.launch { reread() }
  }

  private suspend fun reread() {
    val answer =
      attempt { host.publication() }.getOrNull() as? WatchFacePublicationAnswer.Ok ?: return
    val ready = _view.value as? WatchFacePublishView.Ready ?: return
    // A publish that started while this read was out answers for itself.
    if (ready.busy) return
    val next = ready.copy(publication = answer.publication)
    _view.value = next
    followBuild(next)
  }

  /**
   * Schedules the next read of a background build: every [BUILD_POLL_MILLIS] while it is queued or
   * building, once when a pending build is due or a failed one retries, and never otherwise.
   */
  private fun followBuild(view: WatchFacePublishView) {
    follow?.cancel()
    val build = (view as? WatchFacePublishView.Ready)?.publication?.build ?: return
    val wait =
      when {
        build.isActive() -> BUILD_POLL_MILLIS
        build.state == BUILD_PENDING -> build.scheduledAtEpochMillis?.let(::untilDue) ?: return
        build.state == BUILD_FAILED -> build.nextRetryAtEpochMillis?.let(::untilDue) ?: return
        else -> return
      }
    follow = scope.launch {
      delay(wait)
      reread()
    }
  }

  private fun untilDue(atMillis: Long): Long =
    (atMillis - now() + BUILD_POLL_MILLIS).coerceIn(BUILD_POLL_MILLIS, BUILD_WAKE_MAX_MILLIS)

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

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun epochMillisNow(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
