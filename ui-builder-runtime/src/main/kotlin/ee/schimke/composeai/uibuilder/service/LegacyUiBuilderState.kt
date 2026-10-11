package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The single-file state the per-design store replaced, still readable for the one-shot migration
 * in [FileUiBuilderDesignStore] and for [LegacyStateStorageDesignStore] (in-memory/test hosts). No
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
    // The checksum covers the payload as stored, never a re-encode: re-encoding would miss
    // corruption that still decodes, and would fail on intact files whenever the model gains a
    // defaulted field (`encodeDefaults = true` emits it). New fields are absent from the stored
    // tree and defaulted on decode.
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
 * The single-file store behind the per-design port. Every commit re-encodes every design, so it is
 * only for hosts without disk state (in-memory, tests, pre-migration reads); production uses
 * [UiBuilderDesignStateStore].
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

  /**
   * One write for the whole batch: each [commit] rewrites the entire state, so the default
   * per-design loop would make a startup re-pin O(n) full re-encodes.
   */
  override fun commitAll(changed: Map<String, Pair<PersistedDesignV1?, PersistedDesignV1>>) {
    if (changed.isEmpty()) return
    val candidate =
      value.copy(designs = value.designs + changed.mapValues { (_, values) -> values.second })
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
    // Not gated on every design being servable: this rewrites the envelope, not documents, and an
    // unservable design must not block an operator's recovery path. What matters is the bytes: the
    // preflight round trip, durable readback, and rollback on disagreement.
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
