package ee.schimke.composeai.uibuilder.editor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * "Notify me about replies": Web Push for the comment board, offered beside it.
 *
 * compose-preview-server sends a push when somebody replies on a thread a person has written in, or
 * `@mentions` them (`docs/serve/NOTIFICATIONS.md` over there). Its Settings page has always been
 * able to turn that on; this is the same switch where the conversation actually is. It mirrors the
 * server's own client (`serve-web/src/push/settings.ts`) step for step, because the server's rules
 * are written against that flow:
 * - **Permission is asked from the click and nowhere else.**
 *   [CommentNotificationsController.toggle] calls [CommentNotificationHost.requestPermission]
 *   before it suspends on anything else, so the browser sees the gesture, and
 *   [CommentNotificationsController.load] never asks.
 * - **An existing subscription is re-posted before it is shown as on.** A subscription belongs to
 *   the browser's origin, not to whoever is signed in; the `POST` without `kinds` rebinds it to the
 *   person signed in now. A 4xx means the server will not take it for this person, so the browser
 *   drops it too; a 5xx or no answer only means it could not be confirmed, and it is kept.
 * - **The toggle is two kinds, not all of them.** It turns on [REPLY_KINDS] and keeps whatever else
 *   the person chose in Settings (`reviews`), read from the server first.
 *
 * Everything unportable — the `Notification` API, the service worker, `fetch` — is behind
 * [CommentNotificationHost]; this class is the whole state machine and runs in a JVM test.
 */
sealed interface CommentNotificationsState {
  /**
   * Nothing is drawn: not hosted by compose-preview-server, not a secure context, no `PushManager`,
   * push turned off on the server (the key route 404s), signed out (401), or not yet known.
   */
  data object Hidden : CommentNotificationsState

  /** The switch. [busy] while a click is being carried out; [message] is a sentence under it. */
  data class Toggle(val on: Boolean, val busy: Boolean = false, val message: String? = null) :
    CommentNotificationsState

  /** The browser's permission is `denied`: the switch is shown off, with how to undo that. */
  data class Blocked(val message: String = BLOCKED_MESSAGE) : CommentNotificationsState

  /**
   * An iPhone or iPad in an ordinary Safari tab. Safari offers push only to a site added to the
   * Home Screen and opened from there, so the honest answer is how to get there, not "unsupported".
   */
  data object InstallFirst : CommentNotificationsState
}

/** The kinds this switch is about: a reply on my thread, and `@me`. */
val REPLY_KINDS: Set<String> = setOf("replies", "mentions")

internal const val BLOCKED_MESSAGE =
  "Notifications are blocked for this site. Allow them in your browser's site settings, then " +
    "try again."

internal const val INSTALL_FIRST_TITLE = "Add to Home Screen to get notifications"
internal const val INSTALL_FIRST_HINT =
  "Tap Share, then Add to Home Screen, and open the editor from there."

/** `Notification.permission`. */
enum class PushPermission {
  Default,
  Granted,
  Denied,
}

/**
 * What this page and this browser can do about Web Push, read once.
 *
 * The four probes are `serve-web/src/push/support.ts`'s; [hostedByServer] is this editor's own: the
 * switch is about compose-preview-server's routes, so it is false for a design kept in this browser
 * (`?storage=local`), for a page inside a frame (an IDE webview, an MCP App, somebody's embed), and
 * for every host that is not the live session at all.
 */
data class PushEnvironment(
  val hostedByServer: Boolean,
  val isSecureContext: Boolean,
  val hasServiceWorker: Boolean,
  val hasPushManager: Boolean,
  val hasNotification: Boolean,
  val userAgent: String = "",
  val maxTouchPoints: Int = 0,
  /** Opened as an installed app: `display-mode: standalone`, or iOS's `navigator.standalone`. */
  val standalone: Boolean = false,
)

enum class PushAvailability {
  /** Not a page compose-preview-server serves as itself; see [PushEnvironment.hostedByServer]. */
  NotHosted,
  /** Browsers allow push only on HTTPS or localhost. */
  Insecure,
  /** No service worker, `PushManager` or `Notification`, and nothing the person can do about it. */
  Unsupported,
  /** iOS or iPadOS in a tab: [CommentNotificationsState.InstallFirst]. */
  InstallFirst,
  Ready,
}

/** `support.ts`'s `pushAvailability`, behind the editor's own [PushEnvironment.hostedByServer]. */
fun pushAvailability(env: PushEnvironment): PushAvailability {
  if (!env.hostedByServer) return PushAvailability.NotHosted
  if (!env.isSecureContext) return PushAvailability.Insecure
  if (env.hasServiceWorker && env.hasPushManager && env.hasNotification) {
    return PushAvailability.Ready
  }
  if (isIosDevice(env.userAgent, env.maxTouchPoints) && !env.standalone) {
    return PushAvailability.InstallFirst
  }
  return PushAvailability.Unsupported
}

/**
 * iPhone, iPod or iPad — including the iPad that, since iPadOS 13, reports itself as a Mac and
 * gives itself away only by having a touchscreen. `support.ts`'s `isIos`.
 */
fun isIosDevice(userAgent: String, maxTouchPoints: Int): Boolean {
  if (IOS_DEVICE.containsMatchIn(userAgent)) return true
  return MACINTOSH.containsMatchIn(userAgent) && maxTouchPoints > 1
}

private val IOS_DEVICE = Regex("\\b(iPhone|iPad|iPod)\\b")
private val MACINTOSH = Regex("\\bMacintosh\\b")

/** `PushSubscription.toJSON()`, the part the server stores. Never shown, never logged. */
data class PushSubscriptionInfo(val endpoint: String, val p256dh: String, val auth: String)

/** One answer from the host's push routes. Status 0 is "no answer": a network error. */
data class PushHttpResponse(val status: Int, val body: String = "")

/** The browser half; see [CommentNotificationsController]. */
interface CommentNotificationHost {
  val environment: PushEnvironment

  fun permission(): PushPermission

  /**
   * `Notification.requestPermission()`. Must make the browser call before it first suspends: the
   * controller calls it straight from the click, and Safari shows its prompt only for a gesture.
   */
  suspend fun requestPermission(): PushPermission

  /** This origin's push subscription, from the `/push-sw.js` registration at scope `/`. */
  suspend fun existingSubscription(): PushSubscriptionInfo?

  /**
   * Registers `/push-sw.js` at scope `/`, waits for it to activate, and subscribes with
   * `userVisibleOnly` and [publicKey] — replacing a subscription made under a different key, which
   * would never deliver. Throws with a sentence when the browser refuses.
   */
  suspend fun subscribe(publicKey: String): PushSubscriptionInfo

  /** Drops this browser's subscription, if it has one. Never throws. */
  suspend fun unsubscribe()

  /** Unregisters the push worker, if it is registered. Never throws. */
  suspend fun unregisterWorker()

  /** A same-origin JSON request to the host's push routes. Never throws: status 0 instead. */
  suspend fun request(method: String, path: String, body: String? = null): PushHttpResponse
}

/** For every host that is not compose-preview-server's live page: the JVM app, previews, tests. */
object UnavailableCommentNotificationHost : CommentNotificationHost {
  override val environment =
    PushEnvironment(
      hostedByServer = false,
      isSecureContext = false,
      hasServiceWorker = false,
      hasPushManager = false,
      hasNotification = false,
    )

  override fun permission() = PushPermission.Denied

  override suspend fun requestPermission() = PushPermission.Denied

  override suspend fun existingSubscription(): PushSubscriptionInfo? = null

  override suspend fun subscribe(publicKey: String): PushSubscriptionInfo =
    error("push is not available here")

  override suspend fun unsubscribe() {}

  override suspend fun unregisterWorker() {}

  override suspend fun request(method: String, path: String, body: String?) = PushHttpResponse(0)
}

/** The server's push routes (`ServePushRoutes.kt`). */
const val PUSH_KEY_PATH: String = "/api/push/key"
const val PUSH_SUBSCRIBE_PATH: String = "/api/push/subscribe"
const val PUSH_PREFERENCES_PATH: String = "/api/push/preferences"

/** See [CommentNotificationsState] for the rules this follows. */
class CommentNotificationsController(private val host: CommentNotificationHost) {
  private val mutableState =
    MutableStateFlow<CommentNotificationsState>(CommentNotificationsState.Hidden)
  val state: StateFlow<CommentNotificationsState> = mutableState.asStateFlow()

  private var publicKey: String? = null

  /** The kinds the server last said this person wants, on every device. */
  private var kinds: Set<String> = emptySet()

  /** Whether the server has confirmed this browser's subscription as this person's. */
  private var bound = false
  private var busy = false

  /**
   * Decide what to show. Asks the server, never the person: no permission prompt, no worker
   * registration, no new subscription.
   */
  suspend fun load() {
    mutableState.value = settle { loadState() }
  }

  private suspend fun loadState(): CommentNotificationsState {
    val availability = pushAvailability(host.environment)
    if (availability != PushAvailability.Ready && availability != PushAvailability.InstallFirst) {
      return CommentNotificationsState.Hidden
    }
    // A server with push turned off has no key route at all; a static host answers 404 for it too.
    val key = host.request("GET", PUSH_KEY_PATH)
    publicKey =
      key
        .takeIf { it.status == 200 }
        ?.let { decodeOrNull(PushKeyWire.serializer(), it.body)?.publicKey }
        ?.takeIf { it.isNotBlank() } ?: return CommentNotificationsState.Hidden

    if (availability == PushAvailability.InstallFirst) {
      // Only worth saying to somebody who could then turn it on: signed in, on a server with push.
      val preferences = host.request("GET", PUSH_PREFERENCES_PATH)
      return if (preferences.status in HIDDEN_STATUSES) CommentNotificationsState.Hidden
      else CommentNotificationsState.InstallFirst
    }

    val permission = host.permission()
    val subscription =
      if (permission == PushPermission.Granted) host.existingSubscription() else null
    if (subscription != null) return rebind(subscription)

    val preferences = host.request("GET", PUSH_PREFERENCES_PATH)
    if (preferences.status in HIDDEN_STATUSES) return CommentNotificationsState.Hidden
    decodeOrNull(PushPreferencesWire.serializer(), preferences.body)?.let {
      kinds = it.kinds.toSet()
    }
    bound = false
    return if (permission == PushPermission.Denied) CommentNotificationsState.Blocked()
    else CommentNotificationsState.Toggle(on = false)
  }

  /**
   * Post this browser's existing subscription again, without `kinds`, so the person's own choice
   * stands and the endpoint is bound to whoever is signed in now. `refresh` in `settings.ts`.
   */
  private suspend fun rebind(subscription: PushSubscriptionInfo): CommentNotificationsState {
    val response =
      host.request("POST", PUSH_SUBSCRIBE_PATH, subscribeBody(subscription, kinds = null))
    if (response.status in 200..299) {
      bound = true
      kinds =
        decodeOrNull(PushPreferencesWire.serializer(), response.body)?.kinds?.toSet().orEmpty()
      return CommentNotificationsState.Toggle(on = kinds.any { it in REPLY_KINDS })
    }
    bound = false
    if (response.status in 400..499) {
      // Refused for this person. It must not keep delivering to whoever it belonged to before.
      host.unsubscribe()
      if (response.status == 401) return CommentNotificationsState.Hidden
      return CommentNotificationsState.Toggle(
        on = false,
        message =
          "Notifications were turned off for this browser: ${errorOf(response, "the server refused it")}",
      )
    }
    // A server error or an outage proves nothing either way: report it, keep the subscription, and
    // let the next load try again.
    return CommentNotificationsState.Toggle(
      on = false,
      message =
        "Could not confirm notifications for this browser with the server. Reload to try again.",
    )
  }

  /**
   * The click. The first thing it does when turning on is ask for permission, before any other
   * suspension, so a caller that starts it undispatched from the click handler keeps the gesture.
   */
  suspend fun toggle() {
    val current = mutableState.value
    if (busy) return
    val on =
      when (current) {
        is CommentNotificationsState.Toggle -> current.on
        is CommentNotificationsState.Blocked -> false
        else -> return
      }
    busy = true
    try {
      mutableState.value = settle { if (on) turnOff() else turnOn() }
    } finally {
      busy = false
    }
  }

  private suspend fun turnOn(): CommentNotificationsState {
    when (host.requestPermission()) {
      PushPermission.Denied -> return CommentNotificationsState.Blocked()
      PushPermission.Default ->
        return CommentNotificationsState.Toggle(
          on = false,
          message = "Notifications were not turned on.",
        )
      PushPermission.Granted -> Unit
    }
    mutableState.value = CommentNotificationsState.Toggle(on = false, busy = true)

    if (bound && host.existingSubscription() != null) {
      // Already this person's subscription, for other kinds only: add these two to them.
      return putKinds(kinds + REPLY_KINDS, on = true)
    }

    // Read what the person already chose, so turning this on does not turn `reviews` off — and
    // does not turn it on either: with no device subscribed the server answers its default, every
    // kind, which is not a choice anybody made.
    val preferences = host.request("GET", PUSH_PREFERENCES_PATH)
    if (preferences.status == 401) return CommentNotificationsState.Hidden
    val prior =
      preferences
        .takeIf { it.status in 200..299 }
        ?.let { decodeOrNull(PushPreferencesWire.serializer(), it.body) }
        ?: return CommentNotificationsState.Toggle(
          on = false,
          message =
            "Could not turn notifications on: ${errorOf(preferences, "your notification settings could not be read")}",
        )
    val desired = (if (prior.devices > 0) prior.kinds.toSet() else emptySet()) + REPLY_KINDS

    val key = publicKey ?: return CommentNotificationsState.Hidden
    val subscription = host.subscribe(key)
    val response =
      host.request(
        "POST",
        PUSH_SUBSCRIBE_PATH,
        subscribeBody(subscription, kinds = desired.sorted()),
      )
    if (response.status !in 200..299) {
      host.unsubscribe()
      bound = false
      if (response.status == 401) return CommentNotificationsState.Hidden
      return CommentNotificationsState.Toggle(
        on = false,
        message =
          "Could not turn notifications on: ${errorOf(response, "the server did not take it")}",
      )
    }
    bound = true
    kinds = decodeOrNull(PushPreferencesWire.serializer(), response.body)?.kinds?.toSet() ?: desired
    return CommentNotificationsState.Toggle(on = kinds.any { it in REPLY_KINDS })
  }

  private suspend fun turnOff(): CommentNotificationsState {
    mutableState.value = CommentNotificationsState.Toggle(on = true, busy = true)
    val others = kinds - REPLY_KINDS
    if (others.isNotEmpty()) {
      // Settings has this browser on for something else as well: keep that, drop these two.
      return putKinds(others, on = false)
    }
    val subscription = host.existingSubscription()
    if (subscription != null) {
      val response =
        host.request(
          "DELETE",
          PUSH_SUBSCRIBE_PATH,
          pushJson.encodeToString(
            PushUnsubscribeWire.serializer(),
            PushUnsubscribeWire(subscription.endpoint),
          ),
        )
      if (response.status == 401) {
        host.unsubscribe()
        host.unregisterWorker()
        bound = false
        return CommentNotificationsState.Hidden
      }
      // 404 is "not subscribed", which is where this is going anyway.
      host.unsubscribe()
    }
    host.unregisterWorker()
    bound = false
    kinds = emptySet()
    return CommentNotificationsState.Toggle(on = false)
  }

  private suspend fun putKinds(next: Set<String>, on: Boolean): CommentNotificationsState {
    val response =
      host.request(
        "PUT",
        PUSH_PREFERENCES_PATH,
        pushJson.encodeToString(PushKindsWire.serializer(), PushKindsWire(next.sorted())),
      )
    if (response.status == 401) return CommentNotificationsState.Hidden
    if (response.status !in 200..299) {
      return CommentNotificationsState.Toggle(
        on = !on,
        message = "Not saved: ${errorOf(response, "the server did not take it")}",
      )
    }
    kinds = decodeOrNull(PushPreferencesWire.serializer(), response.body)?.kinds?.toSet() ?: next
    return CommentNotificationsState.Toggle(on = kinds.any { it in REPLY_KINDS })
  }

  /** What went wrong, as a state rather than a crash: the browser refusing, a host that threw. */
  private suspend fun settle(
    block: suspend () -> CommentNotificationsState
  ): CommentNotificationsState =
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      CommentNotificationsState.Toggle(
        on = false,
        message = "Could not change notifications: ${failure.message ?: "the browser refused"}",
      )
    }

  private fun subscribeBody(subscription: PushSubscriptionInfo, kinds: List<String>?): String =
    pushJson.encodeToString(
      PushSubscribeWire.serializer(),
      PushSubscribeWire(
        endpoint = subscription.endpoint,
        keys = PushKeysWire(subscription.p256dh, subscription.auth),
        kinds = kinds,
      ),
    )

  private fun errorOf(response: PushHttpResponse, fallback: String): String =
    decodeOrNull(PushErrorWire.serializer(), response.body)?.error?.takeIf { it.isNotBlank() }
      ?: if (response.status == 0) "the server could not be reached" else fallback

  private fun <T> decodeOrNull(
    serializer: kotlinx.serialization.KSerializer<T>,
    body: String,
  ): T? = runCatching { pushJson.decodeFromString(serializer, body) }.getOrNull()

  private companion object {
    /**
     * Answers that mean "do not offer this here": signed out (the routes want a GitHub sign-in) and
     * push turned off on the server.
     */
    val HIDDEN_STATUSES = setOf(401, 404)
  }
}

private val pushJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  encodeDefaults = true
}

@Serializable private data class PushKeyWire(val publicKey: String = "")

@Serializable private data class PushKeysWire(val p256dh: String, val auth: String)

@Serializable
private data class PushSubscribeWire(
  val endpoint: String,
  val keys: PushKeysWire,
  val kinds: List<String>? = null,
)

@Serializable private data class PushUnsubscribeWire(val endpoint: String)

@Serializable private data class PushKindsWire(val kinds: List<String>)

@Serializable
private data class PushPreferencesWire(val kinds: List<String> = emptyList(), val devices: Int = 0)

@Serializable private data class PushErrorWire(val error: String = "")
