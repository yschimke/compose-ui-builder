package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.UiBuilderDocument
import kotlinx.serialization.json.Json

/** One line of the "designs in this browser" list, read without replaying the design's log. */
data class LocalDesignSummary(
  val designId: String,
  val catalogSystemId: String,
  val title: String,
  val updatedAtEpochMillis: Long,
  val storedBytes: Int,
)

/**
 * Every locally stored design in this origin, and the rules for keeping them inside its quota.
 *
 * The store is deliberately dumb about *what* a design means: it reads and writes
 * [LocalDesignRecordV1] values, lists them, and deletes them. Replaying a record into a document is
 * [LocalDesignSession]'s job, because that needs the reducer and the catalog's validators, and a
 * store that needed them could not answer the chooser's "what is in this browser?" question without
 * loading a catalog first.
 *
 * Keys are `ui-builder.local.design.<designId>`, one per design, rather than one key holding every
 * design: `localStorage` has no partial write, so a single key would mean re-encoding every design
 * in the browser on each keystroke-sized edit, and one design over the quota would take all of them
 * down with it.
 */
class LocalDesignStore(
  private val storage: LocalDesignStorage,
  private val json: Json = localDesignJson,
) {
  /**
   * The designs this browser holds, newest first.
   *
   * A record that will not parse is skipped rather than thrown on. It is somebody else's key in
   * this origin's namespace, or one written by a builder newer than this page, and neither is a
   * reason to refuse to list the designs that do parse.
   */
  fun list(): List<LocalDesignSummary> =
    storage
      .keys()
      .filter { it.startsWith(DESIGN_KEY_PREFIX) }
      .mapNotNull { key ->
        val encoded = storage.read(key) ?: return@mapNotNull null
        val record = decode(encoded) ?: return@mapNotNull null
        LocalDesignSummary(
          designId = record.designId,
          catalogSystemId = record.catalogSystemId,
          title = record.seed.title,
          updatedAtEpochMillis = record.updatedAtEpochMillis,
          storedBytes = encoded.length,
        )
      }
      .sortedWith(
        compareByDescending<LocalDesignSummary> { it.updatedAtEpochMillis }.thenBy { it.designId }
      )

  fun read(designId: String): LocalDesignRecordV1? =
    storage.read(designKey(designId))?.let(::decode)?.takeIf { it.designId == designId }

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

  fun delete(designId: String) {
    storage.remove(designKey(designId))
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

    /**
     * A design id is part of a storage key and of a URL, so it is held to the same shape both
     * places rather than to a looser one here.
     */
    private val SAFE_DESIGN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    fun designKey(designId: String): String {
      require(SAFE_DESIGN_ID.matches(designId)) { "a locally stored design id must be path-safe" }
      return "$DESIGN_KEY_PREFIX$designId"
    }
  }
}

/**
 * The record [document] starts life as in this browser, at the revision it already carries.
 *
 * Used both by "New design" and by the compaction below, which is the same statement twice: this
 * document, no history before it.
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
  )

/**
 * Tolerant on read, exact on write.
 *
 * `ignoreUnknownKeys` because a record written by a newer builder that learned a field should still
 * open rather than reading as a design this browser does not have; `encodeDefaults` off to keep the
 * bytes — which are charged against a few-megabyte quota — to what actually differs from a default.
 */
internal val localDesignJson: Json = Json {
  classDiscriminator = "type"
  encodeDefaults = false
  explicitNulls = false
  ignoreUnknownKeys = true
}
