package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The single-file state the per-design store replaced, still readable.
 *
 * It is kept for two reasons and no others: the one-shot migration in [FileUiBuilderDesignStore]
 * reads it, and [LegacyStateStorageDesignStore] is what an in-memory or test host still uses. No
 * new deployment writes this format.
 */
internal object LegacyUiBuilderState {
  enum class Format(val wire: String) {
    V1("compose-preview-ui-builder-service/v1"),
    V2("compose-preview-ui-builder-service/v2"),
  }

  data class Loaded(val value: PersistedServiceV1, val format: Format) {
    val designs: Map<String, PersistedDesignV1>
      get() = value.designs
  }

  fun decode(bytes: ByteArray): Loaded {
    val encoded =
      try {
        bytes.decodeToString()
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException("invalid UI-builder persistence UTF-8", failure)
      }
    val root =
      try {
        json.parseToJsonElement(encoded).jsonObject
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException("invalid UI-builder persistence JSON", failure)
      }
    val format =
      (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw UiBuilderPersistenceException("UI-builder persistence format is missing")
    // The checksum covers the payload AS STORED — this parsed tree — and never a re-encode of the
    // decoded value. Re-encoding checksums the CURRENT model rather than the bytes on disk, which
    // gets both halves of the job wrong. It cannot see corruption that still decodes (the whole
    // point of the checksum), and it fails on a file that is perfectly intact whenever the model
    // has merely grown: `encodeDefaults = true` emits every field a data class declares, so one new
    // property with a default re-encodes to a JSON tree the stored checksum was never taken over.
    // That is not hypothetical — `DesignEnvironmentV1.typeface` (compose-ai-contracts 2.8.0, server
    // 3.4.0) turned every existing deployment's state file into an unreadable one, and the server
    // exits at construction when persistence fails to load, so the release crash-looped instead of
    // starting. The stored tree, by contrast, is exactly what the writer checksummed: a field the
    // model gained since is simply absent from it, and the decode fills the default in afterwards.
    val storedPayload =
      root["payload"]
        ?: throw UiBuilderPersistenceException("UI-builder persistence payload is missing")
    return when (format) {
      Format.V1.wire -> {
        val envelope = decodeEnvelope<PersistenceEnvelopeV1>(encoded)
        verifyChecksum(envelope.checksumSha256, storedPayload)
        Loaded(envelope.payload, Format.V1)
      }
      Format.V2.wire -> {
        val envelope = decodeEnvelope<PersistenceEnvelopeV2>(encoded)
        verifyChecksum(envelope.checksumSha256, storedPayload)
        val expectedPins = catalogPins(envelope.payload.service)
        if (envelope.payload.catalogPins != expectedPins) {
          throw UiBuilderPersistenceException(
            "UI-builder persistence catalog pin manifest mismatch"
          )
        }
        Loaded(envelope.payload.service, Format.V2)
      }
      else ->
        throw UiBuilderPersistenceException("unsupported UI-builder persistence format $format")
    }
  }

  // The write side of the pairing [decode] documents: the tree checksummed here is the tree
  // serialized on the next line, so the number on disk always describes the bytes beside it.
  fun encode(value: PersistedServiceV1, format: Format): ByteArray {
    val encoded =
      when (format) {
        Format.V1 -> {
          val checksum = sha256(canonicalJson(json.encodeToJsonElement(value)).encodeToByteArray())
          json.encodeToString(
            PersistenceEnvelopeV1.serializer(),
            PersistenceEnvelopeV1(format.wire, checksum, value),
          )
        }
        Format.V2 -> {
          val payload = PersistencePayloadV2(value, catalogPins(value))
          val checksum =
            sha256(canonicalJson(json.encodeToJsonElement(payload)).encodeToByteArray())
          json.encodeToString(
            PersistenceEnvelopeV2.serializer(),
            PersistenceEnvelopeV2(format.wire, checksum, payload),
          )
        }
      }
    return encoded.encodeToByteArray()
  }

  fun catalogPins(value: PersistedServiceV1): Map<String, CatalogReferenceV1> =
    value.designs.mapValues { (_, design) -> design.document.catalogPin }

  private inline fun <reified T> decodeEnvelope(encoded: String): T =
    try {
      json.decodeFromString<T>(encoded)
    } catch (failure: Exception) {
      throw UiBuilderPersistenceException("invalid UI-builder persistence JSON", failure)
    }

  private fun verifyChecksum(expected: String, payload: JsonElement) {
    val actual = sha256(canonicalJson(payload).encodeToByteArray())
    if (actual != expected) {
      throw UiBuilderPersistenceException("UI-builder persistence checksum mismatch")
    }
  }

  private val json = Json { encodeDefaults = true }
}

@Serializable
internal data class PersistenceEnvelopeV1(
  val format: String,
  val checksumSha256: String,
  val payload: PersistedServiceV1,
)

@Serializable
internal data class PersistenceEnvelopeV2(
  val format: String,
  val checksumSha256: String,
  val payload: PersistencePayloadV2,
)

@Serializable
internal data class PersistencePayloadV2(
  val service: PersistedServiceV1,
  /** Redundant by design: startup fails if a design pin and the envelope manifest ever diverge. */
  val catalogPins: Map<String, CatalogReferenceV1>,
)

/**
 * The single-file store, behind the per-design port.
 *
 * Every commit re-encodes every design, which is the cost the v3 store exists to remove — so this
 * is for the hosts that keep nothing on disk: an in-memory storage, a test, and the process of
 * reading a v2 file before it is migrated. A production host opens [UiBuilderDesignStateStore]
 * instead.
 */
internal class LegacyStateStorageDesignStore(private val storage: UiBuilderStateStorage) :
  UiBuilderDesignStore {
  private var value = PersistedServiceV1()
  private var format = LegacyUiBuilderState.Format.V2

  override fun load(): StoredDesigns {
    val bytes = storage.load()
    if (bytes != null) {
      val loaded = LegacyUiBuilderState.decode(bytes)
      value = loaded.value
      format = loaded.format
    }
    return StoredDesigns(value.designs)
  }

  override fun commit(designId: String, previous: PersistedDesignV1?, next: PersistedDesignV1) {
    val candidate = value.copy(designs = value.designs + (designId to next))
    storage.replace(LegacyUiBuilderState.encode(candidate, format))
    value = candidate
  }

  override fun remove(designId: String) {
    val candidate = value.copy(designs = value.designs - designId)
    storage.replace(LegacyUiBuilderState.encode(candidate, format))
    value = candidate
  }

  override fun usage(): UiBuilderStorageUsage? = storage.usage()

  /**
   * Upgrades a validated v1 envelope to v2 with an envelope-level catalog-pin manifest.
   *
   * The storage must retain the exact v1 generation and support explicit restore; a failed durable
   * readback is rolled back before this fails.
   */
  fun migrateToLatest(): UiBuilderPersistenceMigrationResult {
    if (format == LegacyUiBuilderState.Format.V2) {
      return UiBuilderPersistenceMigrationResult(
        migrated = false,
        fromFormat = LegacyUiBuilderState.Format.V2.wire,
        toFormat = LegacyUiBuilderState.Format.V2.wire,
        persistedBytes = LegacyUiBuilderState.encode(value, LegacyUiBuilderState.Format.V2).size,
      )
    }
    val migrationStorage =
      storage as? RecoverableUiBuilderMigrationStorage
        ?: throw UiBuilderPersistenceException(
          "persistence migration requires recoverable migration storage"
        )
    // Deliberately not gated on every stored design being servable. This rewrites the envelope
    // format and does not reinterpret a single document, so a design the current catalog cannot
    // serve is no reason to refuse — and refusing would put back, in an operator's one recovery
    // path, exactly the trap that moving validation off startup removed: one design pinned to a
    // withdrawn catalog and the migration can never be run. What this step does need is proved
    // below and is about the bytes: the preflight round trip, the durable readback, and the
    // rollback if either disagrees.
    val migratedBytes = LegacyUiBuilderState.encode(value, LegacyUiBuilderState.Format.V2)
    val preflight = LegacyUiBuilderState.decode(migratedBytes)
    check(preflight.format == LegacyUiBuilderState.Format.V2 && preflight.value == value) {
      "v2 persistence migration preflight did not round trip"
    }
    migrationStorage.replaceForMigration(migratedBytes)
    try {
      val durable = storage.load()?.let { LegacyUiBuilderState.decode(it) }
      if (
        durable == null ||
          durable.format != LegacyUiBuilderState.Format.V2 ||
          durable.value != value
      ) {
        throw UiBuilderPersistenceException("migrated persistence readback mismatch")
      }
    } catch (failure: Throwable) {
      val restored =
        try {
          migrationStorage.restoreMigrationBackup() &&
            storage
              .load()
              ?.let { LegacyUiBuilderState.decode(it) }
              .let {
                it != null && it.format == LegacyUiBuilderState.Format.V1 && it.value == value
              }
        } catch (rollbackFailure: Throwable) {
          failure.addSuppressed(rollbackFailure)
          false
        }
      if (!restored) {
        throw UiBuilderPersistenceException(
          "persistence migration failed and rollback could not be confirmed",
          failure,
        )
      }
      throw UiBuilderPersistenceException(
        "persistence migration failed; the v1 backup was restored",
        failure,
      )
    }
    format = LegacyUiBuilderState.Format.V2
    return UiBuilderPersistenceMigrationResult(
      migrated = true,
      fromFormat = LegacyUiBuilderState.Format.V1.wire,
      toFormat = LegacyUiBuilderState.Format.V2.wire,
      persistedBytes = migratedBytes.size,
    )
  }
}
