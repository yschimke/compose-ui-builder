package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlinx.serialization.json.Json

/** One line of the "designs in this browser" list, read without replaying the design's log. */
data class LocalDesignSummary(
  val designId: String,
  val catalogSystemId: String,
  val title: String,
  val updatedAtEpochMillis: Long,
  val storedBytes: Int,
  /**
   * True when the record under this id would not parse. Still listed (keyed by its storage key) so
   * [LocalDesignStore.delete] can free its quota.
   */
  val corrupted: Boolean = false,
  /** The server design this one is a browser copy of, when it is one. */
  val copiedFrom: LocalDesignCopyV1? = null,
)

/**
 * Every locally stored design in this origin, and the rules for keeping them inside its quota.
 * Reads, writes, lists and deletes [LocalDesignRecordV1] values; replaying is
 * [LocalDesignSession]'s job, so listing needs no catalog.
 *
 * One key per design (`ui-builder.local.design.<designId>`): `localStorage` has no partial write,
 * so one shared key would re-encode everything on each edit and one oversized design would break
 * all of them.
 */
class LocalDesignStore(
  private val storage: LocalDesignStorage,
  private val json: Json = localDesignJson,
) {
  /**
   * The designs this browser holds, newest first. Unparseable records are listed as
   * [LocalDesignSummary.corrupted] (they hold quota under this prefix and only a delete frees
   * them); keys with an empty remainder are skipped.
   */
  fun list(): List<LocalDesignSummary> =
    storage
      .keys()
      .filter { it.startsWith(DESIGN_KEY_PREFIX) }
      .mapNotNull { key ->
        val encoded = storage.read(key) ?: return@mapNotNull null
        val record = decode(encoded)
        if (record == null) {
          val designId = key.removePrefix(DESIGN_KEY_PREFIX)
          if (designId.isEmpty()) return@mapNotNull null
          LocalDesignSummary(
            designId = designId,
            catalogSystemId = "",
            title = "(unreadable)",
            updatedAtEpochMillis = 0L,
            storedBytes = encoded.length,
            corrupted = true,
          )
        } else {
          LocalDesignSummary(
            designId = record.designId,
            catalogSystemId = record.catalogSystemId,
            title = record.seed.title,
            updatedAtEpochMillis = record.updatedAtEpochMillis,
            storedBytes = encoded.length,
            copiedFrom = record.copiedFrom,
          )
        }
      }
      .sortedWith(
        compareByDescending<LocalDesignSummary> { it.updatedAtEpochMillis }.thenBy { it.designId }
      )

  fun read(designId: String): LocalDesignRecordV1? =
    storage.read(designKey(designId))?.let(::decode)?.takeIf { it.designId == designId }

  /**
   * The record under [designId] exactly as stored, for a download: the whole design (seed, log,
   * provenance). Unreadable records come back too, so they can be saved before deleting.
   */
  fun storedText(designId: String): String? = storage.read("$DESIGN_KEY_PREFIX$designId")

  /** @throws LocalDesignStorageException when this browser refuses the write. */
  fun write(record: LocalDesignRecordV1) {
    storage.write(
      designKey(record.designId),
      json.encodeToString(LocalDesignRecordV1.serializer(), record),
    )
  }

  /**
   * How many bytes [record] would occupy, which is what the compaction rule is measured against.
   */
  fun encodedSize(record: LocalDesignRecordV1): Int =
    json.encodeToString(LocalDesignRecordV1.serializer(), record).length

  /**
   * Removes the record stored under [designId], without [designKey]'s shape check: the id may come
   * from a corrupted record's key, and the prefix keeps the delete within this store.
   */
  fun delete(designId: String) {
    storage.remove("$DESIGN_KEY_PREFIX$designId")
  }

  private fun decode(encoded: String): LocalDesignRecordV1? =
    try {
      json.decodeFromString(LocalDesignRecordV1.serializer(), encoded).takeIf {
        it.schema == LOCAL_DESIGN_SCHEMA
      }
    } catch (_: Exception) {
      null
    }

  companion object {
    const val DESIGN_KEY_PREFIX: String = "ui-builder.local.design."

    /** A design id is part of a storage key and a URL, so it is held to the same shape in both. */
    private val SAFE_DESIGN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    fun designKey(designId: String): String {
      require(SAFE_DESIGN_ID.matches(designId)) { "a locally stored design id must be path-safe" }
      return "$DESIGN_KEY_PREFIX$designId"
    }
  }
}

/**
 * The record [document] starts life as in this browser, at its current revision with no history.
 * Used by "New design" and by compaction.
 */
fun localDesignRecord(
  document: UiBuilderDocument,
  catalogSystemId: String,
  sequence: Long,
  nowEpochMillis: Long,
): LocalDesignRecordV1 =
  LocalDesignRecordV1(
    designId = document.id,
    catalogSystemId = catalogSystemId,
    seed = document,
    seedSequence = sequence,
    log = emptyList(),
    updatedAtEpochMillis = nowEpochMillis,
    createdAtEpochMillis = nowEpochMillis,
  )

/**
 * Tolerant on read (`ignoreUnknownKeys`, so newer builders' records still open), exact on write
 * (`encodeDefaults` off to save quota).
 */
internal val localDesignJson: Json = Json {
  classDiscriminator = "type"
  encodeDefaults = false
  explicitNulls = false
  ignoreUnknownKeys = true
}
