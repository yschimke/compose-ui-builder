@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.local.BrowserStorageEstimate
import ee.schimke.composeai.uibuilder.local.LocalDesignStorage
import ee.schimke.composeai.uibuilder.local.LocalDesignStorageException
import ee.schimke.composeai.uibuilder.local.LocalDesignStore
import ee.schimke.composeai.uibuilder.local.MirroredLocalDesignStorage
import kotlin.coroutines.resume
import kotlin.js.Promise
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * This browser's storage, as the local design mode's store: IndexedDB where the browser has it,
 * `localStorage` where it does not (~5 MB per origin, shared).
 *
 * IndexedDB is async while [LocalDesignStorage] is synchronous, so the seam sits at two edges:
 * * **Open.** [BrowserLocalStorageBackend.hydrate] runs before the editor composes: opens the
 *   database, migrates `localStorage` once, and loads every `ui-builder.local.*` entry into memory,
 *   so reads are map lookups.
 * * **Write.** Writes land in memory immediately (so `Stored` / `Compacted` / `Refused` still
 *   answer synchronously) and are written behind to IndexedDB in order. Later refusals (quota, full
 *   disk) go to [BrowserLocalStorageBackend.onWriteFailure].
 *
 * Schema: database `ui-builder-local` v1, store `entries` (out-of-line keys; same keys and text
 * values `localStorage` held) and store `meta`, whose `migratedFromLocalStorage` records the
 * one-time copy. Copied `localStorage` keys are then removed so there's one source of truth.
 *
 * Every write and delete is announced on the `ui-builder.local` `BroadcastChannel`; other tabs
 * re-read the key and notify [BrowserLocalStorageBackend.onExternalChange] (see
 * `LocalDesignSession.changedElsewhere`). All instances share one backend.
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
  /** Every `ui-builder.local.*` entry, once IndexedDB has been opened; null on `localStorage`. */
  private var mirror: MirroredLocalDesignStorage? = null
  private var hooksInstalled = false
  private var persistenceRequested = false
  private val failureListeners = mutableListOf<(String) -> Unit>()
  private val externalListeners = mutableListOf<(String) -> Unit>()

  /** Whether designs are kept in IndexedDB (true) or `localStorage` (false). */
  val indexedDb: Boolean
    get() = mirror != null

  /**
   * Why IndexedDB could not be opened when the browser has it, or null. Designs migrated into it
   * earlier are then not visible.
   */
  var unavailableReason: String? = null
    private set

  /**
   * Opens IndexedDB, migrates, and reads everything into memory. Idempotent; without IndexedDB, or
   * if it doesn't answer within a few seconds, stays on `localStorage`.
   */
  suspend fun hydrate() {
    installHooks()
    if (mirror != null) return
    val answer =
      withTimeoutOrNull(HYDRATE_TIMEOUT_MILLIS) { awaitString(openLocalDatabase(PREFIX)) }
        ?: run {
          unavailableReason = "the browser's database did not answer"
          return
        }
    val parsed = runCatching { Json.parseToJsonElement(answer) as JsonObject }.getOrNull()
    val error = (parsed?.get("error") as? JsonPrimitive)?.contentOrNull
    val entries = parsed?.get("entries") as? JsonObject
    when {
      parsed == null -> unavailableReason = "the browser's database answered something unreadable"
      error == "unsupported" -> Unit
      error != null -> unavailableReason = error
      entries != null ->
        mirror =
          MirroredLocalDesignStorage(
            entries.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() },
            IndexedDbSink,
          )
    }
  }

  fun read(key: String): String? {
    mirror?.let {
      return it.read(key)
    }
    return readLocalStorage(key, MISSING).takeIf { it != MISSING }
  }

  fun write(key: String, value: String) {
    val designWrite = key.startsWith(LocalDesignStore.DESIGN_KEY_PREFIX)
    val held = mirror
    if (held != null) {
      held.write(key, value)
    } else {
      val failure = writeLocalStorage(key, value)
      if (failure.isNotEmpty()) throw LocalDesignStorageException(failure)
      announceLocalChange(key)
    }
    if (designWrite) requestPersistenceOnce()
  }

  fun remove(key: String) {
    val held = mirror
    if (held != null) {
      held.remove(key)
    } else {
      removeLocalStorage(key)
      announceLocalChange(key)
    }
  }

  fun keys(prefix: String): List<String> =
    mirror?.keys()?.filter { it.startsWith(prefix) }
      ?: localStorageKeys(prefix).split('\n').filter { it.isNotEmpty() }

  /** Called with a sentence when IndexedDB refuses a write that memory already holds. */
  fun onWriteFailure(listener: (String) -> Unit): () -> Unit {
    installHooks()
    failureListeners += listener
    return { failureListeners -= listener }
  }

  /** Called with the key when another tab wrote or removed it; memory already holds its value. */
  fun onExternalChange(listener: (String) -> Unit): () -> Unit {
    installHooks()
    externalListeners += listener
    return { externalListeners -= listener }
  }

  /** What `navigator.storage.estimate()` says, or null where the browser does not say. */
  suspend fun estimate(): BrowserStorageEstimate? {
    val parts =
      runCatchingCancellable { awaitString(storageEstimate()) }.getOrNull()?.split(',')
        ?: return null
    if (parts.size < 3) return null
    val usage = parts[0].toDoubleOrNull() ?: return null
    val quota = parts[1].toDoubleOrNull() ?: return null
    if (quota <= 0) return null
    return BrowserStorageEstimate(
      usageBytes = usage.toLong(),
      quotaBytes = quota.toLong(),
      persisted = parts[2] == "true",
      indexedDb = indexedDb,
    )
  }

  /**
   * Asks the browser to persist this origin's storage once a design is first saved; otherwise it is
   * best-effort and may be cleared under pressure (or by Safari after a week).
   */
  private fun requestPersistenceOnce() {
    if (persistenceRequested) return
    persistenceRequested = true
    requestPersistentStorage()
  }

  private fun installHooks() {
    if (hooksInstalled) return
    hooksInstalled = true
    installLocalStorageHooks(
      onFailure = { message -> failureListeners.toList().forEach { it(message) } },
      onExternal = { key, value, removed ->
        mirror?.applyExternal(key, if (removed) null else value)
        externalListeners.toList().forEach { it(key) }
      },
    )
  }

  /** Written behind, in order; a refusal comes back through [onWriteFailure]. */
  private object IndexedDbSink : MirroredLocalDesignStorage.Sink {
    override fun put(key: String, value: String) = writeLocalDatabase(key, value)

    override fun delete(key: String) = removeLocalDatabase(key)
  }

  private const val PREFIX = "ui-builder.local."
  private const val HYDRATE_TIMEOUT_MILLIS = 4_000L
}

/** Whether this page was asked for a design kept in the browser rather than on the server. */
@JsFun("""() => new URLSearchParams(globalThis.location.search).get('storage') === 'local'""")
external fun localDesignStorageRequested(): Boolean

/**
 * A sentinel rather than `null`, since null and an empty stored value are indistinguishable across
 * the wasmJs boundary. Written as `\u0000` because a raw NUL makes git treat the file as binary.
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
          continuation.resume(
            JsonObject(mapOf("error" to JsonPrimitive(error.toString()))).toString()
          )
        }
        null
      }
  }

/**
 * The page-wide JavaScript half: the database handle, the channel, and the tab's id, kept on
 * `globalThis.__uiBuilderLocalStore` so every function below shares them.
 */
@JsFun(
  """(onFailure, onExternal) => {
    const state = globalThis.__uiBuilderLocalStore || (globalThis.__uiBuilderLocalStore = {});
    state.tab = state.tab || (Math.random().toString(36).slice(2) + Date.now().toString(36));
    state.onFailure = onFailure;
    try {
      if (typeof BroadcastChannel !== 'function') return;
      const channel = state.channel || (state.channel = new BroadcastChannel('ui-builder.local'));
      channel.onmessage = (event) => {
        const message = event.data || {};
        if (message.tab === state.tab || typeof message.key !== 'string') return;
        if (!state.db) { onExternal(message.key, '', message.type === 'remove'); return; }
        // Re-read rather than trust the message: the record can be a megabyte, and the database is
        // the one place that knows which of two racing writes landed last.
        try {
          const request = state.db.transaction('entries').objectStore('entries').get(message.key);
          request.onsuccess = () => {
            const value = request.result;
            onExternal(message.key, typeof value === 'string' ? value : '', typeof value !== 'string');
          };
          request.onerror = () => onExternal(message.key, '', message.type === 'remove');
        } catch (e) {
          onExternal(message.key, '', message.type === 'remove');
        }
      };
    } catch (e) {}
  }"""
)
private external fun installLocalStorageHooks(
  onFailure: (String) -> Unit,
  onExternal: (String, String, Boolean) -> Unit,
)

/**
 * Opens `ui-builder-local`, copies `localStorage` into it the first time, and answers `{"entries":
 * {key: value}}` with everything under [prefix], or `{"error": …}`.
 */
@JsFun(
  """(prefix) => new Promise((resolve) => {
    const state = globalThis.__uiBuilderLocalStore || (globalThis.__uiBuilderLocalStore = {});
    const fail = (reason) => resolve(JSON.stringify({ error: String(reason) }));
    let factory;
    try {
      factory = globalThis.indexedDB;
    } catch (e) {
      factory = null;
    }
    if (!factory) { resolve(JSON.stringify({ error: 'unsupported' })); return; }
    let request;
    try {
      request = factory.open('ui-builder-local', 1);
    } catch (e) {
      fail(e && e.name ? e.name : e);
      return;
    }
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains('entries')) db.createObjectStore('entries');
      if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta');
    };
    request.onerror = () => fail(request.error ? request.error.name + ': ' + request.error.message : 'open failed');
    request.onblocked = () => fail('another tab is holding an older version of the design database open');
    request.onsuccess = () => {
      const db = request.result;
      state.db = db;
      // A newer editor in another tab wants to upgrade the schema: step aside rather than block it.
      db.onversionchange = () => { db.close(); state.db = null; };
      const migrated = [];
      let tx;
      try {
        tx = db.transaction(['entries', 'meta'], 'readwrite');
      } catch (e) {
        fail(e && e.name ? e.name : e);
        return;
      }
      const entries = tx.objectStore('entries');
      const meta = tx.objectStore('meta');
      const all = {};
      // Read everything back only after the copy has been queued, in the same transaction, so the
      // cursor runs after the puts and sees what was just migrated.
      const readAll = () => {
        const cursor = entries.openCursor();
        cursor.onsuccess = () => {
          const at = cursor.result;
          if (!at) return;
          if (typeof at.key === 'string' && at.key.indexOf(prefix) === 0 && typeof at.value === 'string') {
            all[at.key] = at.value;
          }
          at.continue();
        };
      };
      const marker = meta.get('migratedFromLocalStorage');
      marker.onsuccess = () => {
        if (marker.result) { readAll(); return; }
        let storage = null;
        try { storage = globalThis.localStorage; } catch (e) {}
        const keys = [];
        try {
          for (let index = 0; storage && index < storage.length; index += 1) {
            const key = storage.key(index);
            if (key && key.indexOf(prefix) === 0) keys.push(key);
          }
        } catch (e) {}
        for (const key of keys) {
          const value = storage.getItem(key);
          if (value === null) continue;
          entries.put(value, key);
          migrated.push(key);
        }
        meta.put({ at: Date.now(), keys: migrated.length }, 'migratedFromLocalStorage');
        readAll();
      };
      tx.oncomplete = () => {
        // Only once the copy has committed: until then `localStorage` is the only copy.
        try { migrated.forEach((key) => globalThis.localStorage.removeItem(key)); } catch (e) {}
        resolve(JSON.stringify({ entries: all }));
      };
      tx.onabort = () => fail(tx.error ? tx.error.name + ': ' + tx.error.message : 'the migration was aborted');
    };
  })"""
)
private external fun openLocalDatabase(prefix: String): Promise<JsString>

@JsFun(
  """(key, value) => {
    const state = globalThis.__uiBuilderLocalStore || {};
    const report = (error) => state.onFailure && state.onFailure(
      'this browser refused to store ' + key + ': ' + (error && error.name ? error.name : 'unknown error'));
    try {
      const tx = state.db.transaction('entries', 'readwrite');
      tx.objectStore('entries').put(value, key);
      tx.oncomplete = () => { try { state.channel && state.channel.postMessage({ type: 'write', key, tab: state.tab }); } catch (e) {} };
      // An error aborts the transaction, so this hears every refusal exactly once.
      tx.onabort = () => report(tx.error);
    } catch (e) {
      report(e);
    }
  }"""
)
private external fun writeLocalDatabase(key: String, value: String)

@JsFun(
  """(key) => {
    const state = globalThis.__uiBuilderLocalStore || {};
    try {
      const tx = state.db.transaction('entries', 'readwrite');
      tx.objectStore('entries').delete(key);
      tx.oncomplete = () => { try { state.channel && state.channel.postMessage({ type: 'remove', key, tab: state.tab }); } catch (e) {} };
    } catch (e) {}
  }"""
)
private external fun removeLocalDatabase(key: String)

/** Tells other tabs a `localStorage` key changed; IndexedDB writes announce on commit instead. */
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
