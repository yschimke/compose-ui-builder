package ee.schimke.composeai.uibuilder.local

/**
 * A synchronous [LocalDesignStorage] in front of an asynchronous store: every entry held in memory,
 * every change also handed to [sink] to be written behind. This is the async seam between the
 * reducer (answers in the same call) and IndexedDB: the page reads every entry once ([initial]),
 * then reads are map lookups and `LocalDesignSession` gets its `Stored`/`Compacted`/`Refused`
 * synchronously; later sink failures are the sink's to report.
 *
 * [sink] must apply changes in call order (IndexedDB readwrite transactions on one store commit in
 * the order opened).
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
   * Another tab changed [key] to [value] (null: removed). Taken into memory without going to the
   * sink, which would only race the other tab.
   */
  fun applyExternal(key: String, value: String?) {
    if (value == null) memory.remove(key) else memory[key] = value
  }
}
