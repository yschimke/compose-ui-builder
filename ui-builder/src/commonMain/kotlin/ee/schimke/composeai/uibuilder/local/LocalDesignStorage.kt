package ee.schimke.composeai.uibuilder.local

/**
 * The browser key/value store the local design mode writes to, as a seam rather than a call.
 *
 * `localStorage` is the implementation in the page ([the wasmJs
 * binding][ee.schimke.composeai.uibuilder.local]), but nothing above this interface may know that:
 * the store's rules — a design is replayed from a seed and a log, a log is compacted before it
 * outgrows the origin's quota — are the part worth testing, and they are testable on the JVM only
 * if the bytes can go somewhere that is not a browser.
 *
 * Three properties the browser forces on every caller, and this interface therefore states:
 * * a read of something never written answers null rather than throwing;
 * * a write can **fail** — the origin's quota is a few megabytes and a private window can refuse
 *   storage outright — and it fails by throwing [LocalDesignStorageException], which callers handle
 *   rather than propagate, because a design that cannot be saved is still a design being edited;
 * * [keys] is an enumeration of this origin's keys, which is how the library lists what a browser
 *   holds without a server to ask.
 */
interface LocalDesignStorage {
  fun read(key: String): String?

  /** @throws LocalDesignStorageException when the origin refuses or has no room for [value]. */
  fun write(key: String, value: String)

  fun remove(key: String)

  /** Every key this origin holds, in unspecified order. */
  fun keys(): List<String>
}

/**
 * A store that refused a write, or a page whose storage is unavailable altogether.
 *
 * One type for both because the caller does the same thing with them: say so in the editor's status
 * line and keep the in-memory document, which is the only copy left.
 */
class LocalDesignStorageException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

/**
 * The store used by tests, and by a page whose browser will not give the builder any storage.
 *
 * [quotaBytes] exists so the compaction rule can be proven rather than described: the browser's own
 * limit is around five megabytes per origin and cannot be reached in a unit test in reasonable
 * time.
 */
class InMemoryLocalDesignStorage(private val quotaBytes: Int = Int.MAX_VALUE) : LocalDesignStorage {
  private val entries = mutableMapOf<String, String>()

  override fun read(key: String): String? = entries[key]

  override fun write(key: String, value: String) {
    val projected =
      entries.entries.filter { it.key != key }.sumOf { it.key.length + it.value.length }
    if (projected + key.length + value.length > quotaBytes) {
      throw LocalDesignStorageException("this browser has no room left for $key")
    }
    entries[key] = value
  }

  override fun remove(key: String) {
    entries.remove(key)
  }

  override fun keys(): List<String> = entries.keys.toList()
}
