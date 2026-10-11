package ee.schimke.composeai.uibuilder.local

/**
 * The browser key/value store the local design mode writes to, as a seam so the store's rules
 * (seed-plus-log replay, compaction under quota) are testable on the JVM. `localStorage` is the
 * page implementation ([the wasmJs binding][ee.schimke.composeai.uibuilder.local]).
 *
 * Guarantees every implementation states:
 * * a read of something never written answers null rather than throwing;
 * * a write can **fail** (quota of a few MB, private windows), by throwing
 *   [LocalDesignStorageException], which callers handle so editing continues;
 * * [keys] enumerates this origin's keys, which is how the library lists designs without a server.
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
 * A store that refused a write, or a page whose storage is unavailable altogether. One type, since
 * the caller reports it in the status line and keeps the in-memory document either way.
 */
class LocalDesignStorageException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

/**
 * The store used by tests, and by a page whose browser gives no storage. [quotaBytes] lets tests
 * prove the compaction rule without filling a real ~5 MB quota.
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
