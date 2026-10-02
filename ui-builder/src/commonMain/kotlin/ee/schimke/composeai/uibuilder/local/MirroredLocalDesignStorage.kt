package ee.schimke.composeai.uibuilder.local

/**
 * A synchronous [LocalDesignStorage] in front of an asynchronous store: every entry held in memory,
 * every change also handed to [sink] to be written behind.
 *
 * This is the whole of the async seam between the reducer, which answers an edit in the same call
 * that made it, and IndexedDB, which answers later. The page reads every entry once before the
 * editor starts ([initial]); from then on a read is a map lookup and a write lands here at once, so
 * `LocalDesignSession` still gets — and reports — its `Stored`, `Compacted` or `Refused` in the
 * same call. What the sink does later (and whether it fails) is the sink's to report.
 *
 * [sink] is called in the order the changes were made, and must apply them in that order; IndexedDB
 * does, because read-write transactions on one store commit in the order they were opened.
 */
class MirroredLocalDesignStorage(initial: Map<String, String>, private val sink: Sink) :
  LocalDesignStorage {
  /** Where changes go after they have landed in memory. */
  interface Sink {
    fun put(key: String, value: String)

    fun delete(key: String)
  }

  private val memory: MutableMap<String, String> = initial.toMutableMap()

  override fun read(key: String): String? = memory[key]

  override fun write(key: String, value: String) {
    memory[key] = value
    sink.put(key, value)
  }

  override fun remove(key: String) {
    memory.remove(key)
    sink.delete(key)
  }

  override fun keys(): List<String> = memory.keys.toList()

  /**
   * Another tab changed [key] to [value] (null: removed it). Taken into memory without being handed
   * to the sink — it came *from* the store, and writing it back would only race the other tab.
   */
  fun applyExternal(key: String, value: String?) {
    if (value == null) memory.remove(key) else memory[key] = value
  }
}
