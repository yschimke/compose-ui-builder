@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.local.BrowserStorageEstimate
import ee.schimke.composeai.uibuilder.local.LocalDesignStorage
import ee.schimke.composeai.uibuilder.local.LocalDesignStorageException
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * `localStorage`, as the local design mode's store.
 *
 * Three browser facts shape this, and each is handled here rather than being allowed upward:
 * * **Storage can be absent.** A private window, an origin the reader has blocked, an embedded
 *   context — `globalThis.localStorage` throws on *access*, not only on use. Every call is wrapped,
 *   and a read that cannot happen answers "nothing stored" the way an empty store does.
 * * **A write can fail.** The origin's quota is a few megabytes, shared with everything else this
 *   host keeps here. A refusal is a [LocalDesignStorageException], which the session answers by
 *   compacting the design's history and trying once more.
 * * **Keys are shared.** This origin also serves the preview pages, whose theme and tab keys live
 *   beside these. Enumeration is therefore filtered in the page to the builder's own prefixes, so
 *   listing the designs in this browser can never be confused by somebody else's key.
 *
 * Every write and delete is announced on the `ui-builder.local` `BroadcastChannel`, so a tab
 * editing the same design learns it should stop writing (see `LocalDesignSession.changedElsewhere`)
 * rather than replacing the other tab's record without either tab saying so. The first design
 * written asks the browser to keep this origin's storage (`navigator.storage.persist()`).
 *
 * Every instance shares the one backend, so the many `BrowserLocalDesignStorage()` call sites in
 * the page all announce on the same channel.
 */
class BrowserLocalDesignStorage : LocalDesignStorage {
  override fun read(key: String): String? = BrowserLocalStorageBackend.read(key)

  override fun write(key: String, value: String) = BrowserLocalStorageBackend.write(key, value)

  override fun remove(key: String) = BrowserLocalStorageBackend.remove(key)

  override fun keys(): List<String> =
    BrowserLocalStorageBackend.keys(LocalDesignStore.DESIGN_KEY_PREFIX)
}

/** The page's one storage backend. See [BrowserLocalDesignStorage]. */
internal object BrowserLocalStorageBackend {
  private var hooksInstalled = false
  private var persistenceRequested = false
  private val externalListeners = mutableListOf<(String) -> Unit>()

  fun read(key: String): String? = readLocalStorage(key, MISSING).takeIf { it != MISSING }

  fun write(key: String, value: String) {
    val failure = writeLocalStorage(key, value)
    if (failure.isNotEmpty()) throw LocalDesignStorageException(failure)
    announceLocalChange(key)
    if (key.startsWith(LocalDesignStore.DESIGN_KEY_PREFIX)) requestPersistenceOnce()
  }

  fun remove(key: String) {
    removeLocalStorage(key)
    announceLocalChange(key)
  }

  fun keys(prefix: String): List<String> =
    localStorageKeys(prefix).split('\n').filter { it.isNotEmpty() }

  /** Called with the key when another tab of this browser wrote or removed it. */
  fun onExternalChange(listener: (String) -> Unit): () -> Unit {
    installHooks()
    externalListeners += listener
    return { externalListeners -= listener }
  }

  /** What `navigator.storage.estimate()` says, or null where the browser does not say. */
  suspend fun estimate(): BrowserStorageEstimate? {
    val parts =
      runCatching { awaitString(storageEstimate()) }.getOrNull()?.split(',') ?: return null
    if (parts.size < 3) return null
    val usage = parts[0].toDoubleOrNull() ?: return null
    val quota = parts[1].toDoubleOrNull() ?: return null
    if (quota <= 0) return null
    return BrowserStorageEstimate(
      usageBytes = usage.toLong(),
      quotaBytes = quota.toLong(),
      persisted = parts[2] == "true",
      indexedDb = false,
    )
  }

  /**
   * Asks the browser to keep this origin's storage when it is short of space, once a design is
   * first saved. Without it, designs kept here are "best effort" and a browser under storage
   * pressure — or Safari after a week without a visit — may clear them.
   */
  private fun requestPersistenceOnce() {
    if (persistenceRequested) return
    persistenceRequested = true
    requestPersistentStorage()
  }

  fun installHooks() {
    if (hooksInstalled) return
    hooksInstalled = true
    installLocalStorageHooks { key -> externalListeners.toList().forEach { it(key) } }
  }
}

/** Whether this page was asked for a design kept in the browser rather than on the server. */
@JsFun("""() => new URLSearchParams(globalThis.location.search).get('storage') === 'local'""")
external fun localDesignStorageRequested(): Boolean

/**
 * A sentinel rather than `null`, because a `null` crossing the wasmJs boundary as a `String?` and
 * an empty stored value are the same answer here, and an empty value is one this store writes.
 *
 * Written as the `\u0000` escape rather than the byte itself: a raw NUL in a source file makes git
 * read the whole file as binary, so it stops being reviewable in a diff.
 */
private const val MISSING = "\u0000ui-builder-missing"

private suspend fun awaitString(promise: Promise<JsString>): String =
  suspendCancellableCoroutine { continuation ->
    promise
      .then { value ->
        if (continuation.isActive) continuation.resume(value.toString())
        null
      }
      .catch { error ->
        if (continuation.isActive) {
          continuation.resume("")
        }
        null
      }
  }

/** The tab's id and the channel, kept on `globalThis.__uiBuilderLocalStore`. */
@JsFun(
  """(onExternal) => {
    const state = globalThis.__uiBuilderLocalStore || (globalThis.__uiBuilderLocalStore = {});
    state.tab = state.tab || (Math.random().toString(36).slice(2) + Date.now().toString(36));
    try {
      if (typeof BroadcastChannel !== 'function') return;
      const channel = state.channel || (state.channel = new BroadcastChannel('ui-builder.local'));
      channel.onmessage = (event) => {
        const message = event.data || {};
        if (message.tab === state.tab || typeof message.key !== 'string') return;
        onExternal(message.key);
      };
    } catch (e) {}
  }"""
)
private external fun installLocalStorageHooks(onExternal: (String) -> Unit)

/** Tells other tabs a key changed. */
@JsFun(
  """(key) => {
    const state = globalThis.__uiBuilderLocalStore || {};
    try { state.channel && state.channel.postMessage({ type: 'write', key, tab: state.tab }); } catch (e) {}
  }"""
)
private external fun announceLocalChange(key: String)

@JsFun(
  """() => {
    try {
      const storage = navigator.storage;
      if (!storage || typeof storage.persist !== 'function') return;
      storage.persisted().then((already) => already || storage.persist()).catch(() => {});
    } catch (e) {}
  }"""
)
private external fun requestPersistentStorage()

/** `usage,quota,persisted`, or an empty answer where the browser has no estimate. */
@JsFun(
  """() => {
    try {
      const storage = navigator.storage;
      if (!storage || typeof storage.estimate !== 'function') return Promise.resolve('');
      const persisted = typeof storage.persisted === 'function' ? storage.persisted() : Promise.resolve(false);
      return Promise.all([storage.estimate(), persisted]).then(
        ([estimate, kept]) => (estimate.usage || 0) + ',' + (estimate.quota || 0) + ',' + !!kept,
        () => '',
      );
    } catch (e) {
      return Promise.resolve('');
    }
  }"""
)
private external fun storageEstimate(): Promise<JsString>

@JsFun(
  """(key, missing) => {
    try {
      const value = globalThis.localStorage.getItem(key);
      return value === null ? missing : value;
    } catch (e) {
      return missing;
    }
  }"""
)
private external fun readLocalStorage(key: String, missing: String): String

/** Returns the refusal, or an empty string when the write landed. */
@JsFun(
  """(key, value) => {
    try {
      globalThis.localStorage.setItem(key, value);
      return '';
    } catch (e) {
      return 'this browser refused to store ' + key + ': ' + (e && e.name ? e.name : 'unknown error');
    }
  }"""
)
private external fun writeLocalStorage(key: String, value: String): String

@JsFun(
  """(key) => {
    try {
      globalThis.localStorage.removeItem(key);
    } catch (e) {}
  }"""
)
private external fun removeLocalStorage(key: String)

@JsFun(
  """(prefix) => {
    try {
      const storage = globalThis.localStorage;
      const keys = [];
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index);
        if (key && key.indexOf(prefix) === 0) keys.push(key);
      }
      return keys.join('\n');
    } catch (e) {
      return '';
    }
  }"""
)
private external fun localStorageKeys(prefix: String): String
