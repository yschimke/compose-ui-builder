@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.CommentNotificationHost
import ee.schimke.composeai.uibuilder.editor.PushEnvironment
import ee.schimke.composeai.uibuilder.editor.PushHttpResponse
import ee.schimke.composeai.uibuilder.editor.PushPermission
import ee.schimke.composeai.uibuilder.editor.PushSubscriptionInfo
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The browser half of "Notify me about replies": the `Notification` API, the push service worker
 * and the host's `/api/push/…` routes.
 *
 * Every probe and call mirrors compose-preview-server's own client (`serve-web/src/push/`), because
 * the worker this registers is that server's — `/push-sw.js` at scope `/` — and the subscription
 * belongs to the origin, shared with Settings → Notifications on every other page of the site. The
 * worker has no `fetch` handler, so although its scope is the whole origin it cannot change how the
 * editor loads, and this page's own `/ui-builder/` worker keeps controlling it.
 *
 * Each JS function guards its probes: a sandboxed frame throws on merely reading
 * `navigator.serviceWorker`, and a notification switch must never be what breaks the editor.
 */
internal class BrowserCommentNotificationHost(
  /** False for a design kept in this browser (`?storage=local`): nobody to notify about. */
  hostedByServer: Boolean
) : CommentNotificationHost {
  override val environment: PushEnvironment = runCatching {
    pushJson.decodeFromString(PushEnvironmentWire.serializer(), pushEnvironment())
  }
    .getOrNull()
    .let { wire ->
      PushEnvironment(
        // Inside a frame — an IDE webview, an MCP App, somebody's embed — the page is not the
        // server's own, and the server's sign-in cookie may not even reach it.
        hostedByServer = hostedByServer && wire != null && !wire.framed,
        isSecureContext = wire?.isSecureContext == true,
        hasServiceWorker = wire?.hasServiceWorker == true,
        hasPushManager = wire?.hasPushManager == true,
        hasNotification = wire?.hasNotification == true,
        userAgent = wire?.userAgent.orEmpty(),
        maxTouchPoints = wire?.maxTouchPoints ?: 0,
        standalone = wire?.standalone == true,
      )
    }

  override fun permission(): PushPermission = permissionOf(pushPermission())

  override suspend fun requestPermission(): PushPermission =
    permissionOf(awaitCommentString(pushRequestPermission()))

  override suspend fun existingSubscription(): PushSubscriptionInfo? =
    subscriptionOf(awaitCommentString(pushExistingSubscription()))

  override suspend fun subscribe(publicKey: String): PushSubscriptionInfo =
    subscriptionOf(awaitCommentString(pushSubscribe(PUSH_WORKER_PATH, publicKey)))
      ?: error("the browser did not return a subscription")

  override suspend fun unsubscribe() {
    quietly { awaitCommentString(pushUnsubscribe()) }
  }

  override suspend fun unregisterWorker() {
    quietly { awaitCommentString(pushUnregister()) }
  }

  override suspend fun request(method: String, path: String, body: String?): PushHttpResponse {
    val encoded =
      try {
        awaitCommentString(
          commentFetch(method, sameOriginRequestUrl(path), body ?: "", body != null)
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        return PushHttpResponse(0)
      }
    return runCatching { pushJson.decodeFromString(PushFetchWire.serializer(), encoded) }
      .map { PushHttpResponse(it.status, it.body) }
      .getOrElse { PushHttpResponse(0) }
  }

  private fun permissionOf(value: String): PushPermission =
    when (value) {
      "granted" -> PushPermission.Granted
      "denied" -> PushPermission.Denied
      else -> PushPermission.Default
    }

  private fun subscriptionOf(json: String): PushSubscriptionInfo? {
    if (json.isEmpty()) return null
    val wire =
      runCatching { pushJson.decodeFromString(PushSubscriptionWire.serializer(), json) }.getOrNull()
        ?: return null
    if (wire.endpoint.isBlank()) return null
    return PushSubscriptionInfo(wire.endpoint, wire.keys.p256dh, wire.keys.auth)
  }

  private suspend fun quietly(block: suspend () -> Unit) {
    try {
      block()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Throwable) {}
  }
}

/** Where compose-preview-server serves its push worker: the root, so its scope can be `/`. */
private const val PUSH_WORKER_PATH = "/push-sw.js"

private val pushJson = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

@Serializable
private data class PushEnvironmentWire(
  val isSecureContext: Boolean = false,
  val hasServiceWorker: Boolean = false,
  val hasPushManager: Boolean = false,
  val hasNotification: Boolean = false,
  val userAgent: String = "",
  val maxTouchPoints: Int = 0,
  val standalone: Boolean = false,
  val framed: Boolean = true,
)

@Serializable private data class PushFetchWire(val status: Int = 0, val body: String = "")

@Serializable
private data class PushSubscriptionWire(
  val endpoint: String = "",
  val keys: PushSubscriptionKeysWire = PushSubscriptionKeysWire(),
)

@Serializable
private data class PushSubscriptionKeysWire(val p256dh: String = "", val auth: String = "")

/**
 * `support.ts`'s `browserEnvironment`, plus whether this page is inside a frame. A frame whose
 * parent is another origin throws on reading `window.top`'s identity, which counts as framed.
 */
@JsFun(
  """() => {
    const probe = (read) => { try { return read(); } catch (e) { return false; } };
    return JSON.stringify({
      isSecureContext: probe(() => window.isSecureContext === true),
      hasServiceWorker: probe(() => 'serviceWorker' in navigator),
      hasPushManager: probe(() => 'PushManager' in window),
      hasNotification: probe(() => 'Notification' in window),
      userAgent: probe(() => navigator.userAgent || ''),
      maxTouchPoints: probe(() => navigator.maxTouchPoints || 0) || 0,
      standalone: probe(() =>
        (window.matchMedia && window.matchMedia('(display-mode: standalone)').matches === true) ||
        navigator.standalone === true),
      framed: (() => { try { return window.self !== window.top; } catch (e) { return true; } })(),
    });
  }"""
)
private external fun pushEnvironment(): String

@JsFun(
  """() => { try { return String(Notification.permission); } catch (e) { return 'denied'; } }"""
)
private external fun pushPermission(): String

/**
 * Called synchronously from the click, so the browser sees the gesture. The callback form is for a
 * Safari old enough to return nothing from `requestPermission`.
 */
@JsFun(
  """() => new Promise((resolve) => {
    const done = (value) => resolve(String(value));
    const answer = Notification.requestPermission(done);
    if (answer && typeof answer.then === 'function') answer.then(done, () => done('default'));
  })"""
)
private external fun pushRequestPermission(): Promise<JsString>

/**
 * The subscription held by the root-scoped registration, as `toJSON()`, or `''`. A registration for
 * `/` that has no `pushManager` is some other worker and is not ours to read.
 */
@JsFun(
  """() => navigator.serviceWorker.getRegistration('/').then((registration) => {
    if (!registration || !registration.pushManager) return '';
    return registration.pushManager.getSubscription().then((subscription) =>
      subscription ? JSON.stringify(subscription.toJSON()) : '');
  })"""
)
private external fun pushExistingSubscription(): Promise<JsString>

/**
 * `settings.ts`'s `turnOn` after the permission and the key: register, wait for an active worker
 * (`subscribe` refuses before that), drop a subscription made under a key this server no longer
 * signs with, and subscribe with `userVisibleOnly`.
 */
@JsFun(
  """(workerUrl, publicKey) => {
    const padded = publicKey.replace(/-/g, '+').replace(/_/g, '/')
      .padEnd(Math.ceil(publicKey.length / 4) * 4, '=');
    const binary = atob(padded);
    const key = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) key[i] = binary.charCodeAt(i);
    const activated = (registration) => {
      if (registration.active) return Promise.resolve(registration);
      const worker = registration.installing || registration.waiting;
      if (!worker) return Promise.resolve(registration);
      return new Promise((resolve) => {
        const check = () => {
          if (worker.state === 'activated' || worker.state === 'redundant') {
            worker.removeEventListener('statechange', check);
            resolve(registration);
          }
        };
        worker.addEventListener('statechange', check);
        check();
      });
    };
    const sameKey = (subscription) => {
      const current = subscription.options && subscription.options.applicationServerKey;
      if (!current) return true;
      const bytes = new Uint8Array(current);
      return bytes.length === key.length && bytes.every((b, i) => b === key[i]);
    };
    return navigator.serviceWorker.register(workerUrl, { scope: '/' })
      .then(activated)
      .then((registration) => registration.pushManager.getSubscription().then((existing) => {
        if (existing && sameKey(existing)) return existing;
        const fresh = () => registration.pushManager.subscribe({
          userVisibleOnly: true,
          applicationServerKey: key,
        });
        return existing ? existing.unsubscribe().then(fresh) : fresh();
      }))
      .then((subscription) => JSON.stringify(subscription.toJSON()));
  }"""
)
private external fun pushSubscribe(workerUrl: String, publicKey: String): Promise<JsString>

@JsFun(
  """() => navigator.serviceWorker.getRegistration('/').then((registration) => {
    if (!registration || !registration.pushManager) return '';
    return registration.pushManager.getSubscription().then((subscription) =>
      subscription ? subscription.unsubscribe().then(() => '') : '');
  })"""
)
private external fun pushUnsubscribe(): Promise<JsString>

/** Only the root-scoped registration, which is the push worker's; the editor's own is untouched. */
@JsFun(
  """() => navigator.serviceWorker.getRegistration('/').then((registration) => {
    if (!registration || registration.scope !== new URL('/', window.location.href).href) return '';
    return registration.unregister().then(() => '');
  })"""
)
private external fun pushUnregister(): Promise<JsString>

/**
 * What the switch is showing, on the root element, for a browser harness: one of `hidden`, `off`,
 * `on`, `blocked`, `install-first`.
 */
@JsFun("""(state) => { document.documentElement.dataset.uiBuilderCommentNotifications = state; }""")
internal external fun publishCommentNotificationsState(state: String)
