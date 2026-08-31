package ee.schimke.composeai.uibuilder

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class DesignStoreCorruptionException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

class DesignStoreLimitException(message: String) : IllegalStateException(message)

data class DesignStoreLimits(
  val maxSnapshotBytes: Long = 8L * 1024 * 1024,
  val maxEventBytes: Long = 1L * 1024 * 1024,
  val maxEventLogBytes: Long = 64L * 1024 * 1024,
) {
  init {
    require(maxSnapshotBytes > 0 && maxEventBytes > 0 && maxEventLogBytes > 0)
    require(maxEventBytes <= maxEventLogBytes)
  }
}

data class StoredDesignRecovery(
  val initialDocument: UiBuilderDocument,
  /** Accepted-commit cursor represented by [initialDocument], before replaying [events]. */
  val snapshotLastSequence: Long,
  val events: List<CollaborationEvent>,
  val ignoredPartialTailBytes: Int = 0,
)

interface DesignStore {
  fun create(initialDocument: UiBuilderDocument)

  fun append(designId: String, event: CollaborationEvent)

  fun load(designId: String): StoredDesignRecovery?

  fun listDesignIds(): List<String>
}

/**
 * Restart-safe single-process store for the first collaboration deployment.
 *
 * The initial document is atomically installed with a checksum. Every reducer result—including a
 * rejected idempotency key—is then appended as one checksummed JSON line and forced to disk before
 * returning. Recovery accepts only complete newline-terminated records, reports an interrupted
 * partial tail, and fails closed on corruption in any acknowledged record.
 *
 * This is intentionally not the final multi-replica adapter. The store enforces per-snapshot,
 * per-event, and per-design event-log byte limits. Compaction, global or tenant quotas, and backup
 * are separate gates.
 */
class FileDesignStore(
  private val root: Path,
  private val limits: DesignStoreLimits = DesignStoreLimits(),
) : DesignStore {
  private val locks = ConcurrentHashMap<String, ReentrantLock>()

  init {
    Files.createDirectories(root)
  }

  override fun create(initialDocument: UiBuilderDocument) {
    val designId = validateDesignId(initialDocument.id)
    locks
      .computeIfAbsent(designId) { ReentrantLock() }
      .withLock {
        val directory = root.resolve(designId)
        Files.createDirectories(directory)
        val snapshot = directory.resolve(SNAPSHOT_FILE)
        if (Files.exists(snapshot)) throw IllegalStateException("design $designId already exists")
        val payload = buildJsonObject {
          put("schema", SNAPSHOT_SCHEMA)
          put("lastSequence", 0)
          put("initialDocument", json.encodeToJsonElement(initialDocument))
        }
        val bytes = encodeEnvelope(payload).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > limits.maxSnapshotBytes) {
          throw DesignStoreLimitException(
            "design $designId snapshot is ${bytes.size} bytes; limit is ${limits.maxSnapshotBytes}"
          )
        }
        atomicWriteNew(snapshot, bytes)
        forceDirectory(directory)
      }
  }

  override fun append(designId: String, event: CollaborationEvent) {
    val safeId = validateDesignId(designId)
    locks
      .computeIfAbsent(safeId) { ReentrantLock() }
      .withLock {
        val directory = root.resolve(safeId)
        if (!Files.isRegularFile(directory.resolve(SNAPSHOT_FILE))) {
          throw IllegalArgumentException("unknown design $safeId")
        }
        val payload = encodeEvent(event)
        val bytes = (encodeEnvelope(payload) + "\n").toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > limits.maxEventBytes) {
          throw DesignStoreLimitException(
            "design $safeId event is ${bytes.size} bytes; limit is ${limits.maxEventBytes}"
          )
        }
        val eventPath = directory.resolve(EVENTS_FILE)
        FileChannel.open(
            eventPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
          )
          .use { channel ->
            channel.lock().use {
              val currentSize = channel.size()
              if (
                currentSize > limits.maxEventLogBytes ||
                  bytes.size.toLong() > limits.maxEventLogBytes - currentSize
              ) {
                throw DesignStoreLimitException(
                  "design $safeId event log would exceed ${limits.maxEventLogBytes} bytes; " +
                    "compaction is required"
                )
              }
              var buffer = ByteBuffer.wrap(bytes)
              while (buffer.hasRemaining()) channel.write(buffer)
              channel.force(true)
            }
          }
      }
  }

  override fun load(designId: String): StoredDesignRecovery? {
    val safeId = validateDesignId(designId)
    return locks
      .computeIfAbsent(safeId) { ReentrantLock() }
      .withLock {
        val directory = root.resolve(safeId)
        val snapshot = directory.resolve(SNAPSHOT_FILE)
        if (!Files.isRegularFile(snapshot)) return@withLock null
        requireFileWithinLimit(snapshot, limits.maxSnapshotBytes, "$safeId/$SNAPSHOT_FILE")
        val snapshotPayload = decodeEnvelope(Files.readString(snapshot), "$safeId/$SNAPSHOT_FILE")
        when (val schema = snapshotPayload.string("schema")) {
          SNAPSHOT_SCHEMA -> Unit
          LEGACY_SNAPSHOT_SCHEMA ->
            throw DesignStoreCorruptionException(
              "snapshot schema $schema for $safeId has no durable sequence; migration required"
            )
          else -> throw DesignStoreCorruptionException("unsupported snapshot schema for $safeId")
        }
        val snapshotLastSequence = snapshotPayload.requiredLong("lastSequence")
        if (snapshotLastSequence < 0) {
          throw DesignStoreCorruptionException("negative snapshot sequence for $safeId")
        }
        val initial =
          try {
            json.decodeFromJsonElement<UiBuilderDocument>(
              snapshotPayload.getValue("initialDocument")
            )
          } catch (failure: Exception) {
            throw DesignStoreCorruptionException("invalid initial document for $safeId", failure)
          }
        val eventFile = directory.resolve(EVENTS_FILE)
        if (!Files.exists(eventFile)) {
          return@withLock StoredDesignRecovery(initial, snapshotLastSequence, emptyList())
        }
        requireFileWithinLimit(eventFile, limits.maxEventLogBytes, "$safeId/$EVENTS_FILE")
        val bytes = Files.readAllBytes(eventFile)
        val events = mutableListOf<CollaborationEvent>()
        var lineStart = 0
        var lineNumber = 0
        bytes.forEachIndexed { index, byte ->
          if (byte == '\n'.code.toByte()) {
            lineNumber += 1
            if (index > lineStart) {
              val line = String(bytes, lineStart, index - lineStart, StandardCharsets.UTF_8)
              val payload = decodeEnvelope(line, "$safeId/$EVENTS_FILE:$lineNumber")
              events += decodeEvent(payload, "$safeId/$EVENTS_FILE:$lineNumber")
            }
            lineStart = index + 1
          }
        }
        StoredDesignRecovery(
          initialDocument = initial,
          snapshotLastSequence = snapshotLastSequence,
          events = events,
          ignoredPartialTailBytes = bytes.size - lineStart,
        )
      }
  }

  private fun requireFileWithinLimit(path: Path, limit: Long, description: String) {
    val size = Files.size(path)
    if (size > limit) {
      throw DesignStoreLimitException("$description is $size bytes; limit is $limit")
    }
  }

  override fun listDesignIds(): List<String> =
    Files.list(root).use { paths ->
      paths
        .filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve(SNAPSHOT_FILE)) }
        .map { it.fileName.toString() }
        .sorted()
        .toList()
    }

  private fun encodeEnvelope(payload: JsonObject): String {
    val canonicalPayload = canonicalJson(payload)
    return canonicalJson(
      buildJsonObject {
        put("checksumSha256", sha256Hex(canonicalPayload))
        put("payload", payload)
      }
    )
  }

  private fun decodeEnvelope(encoded: String, location: String): JsonObject {
    val envelope =
      try {
        json.parseToJsonElement(encoded).jsonObject
      } catch (failure: Exception) {
        throw DesignStoreCorruptionException("invalid JSON at $location", failure)
      }
    if (envelope.keys != setOf("checksumSha256", "payload")) {
      throw DesignStoreCorruptionException("invalid envelope at $location")
    }
    val payload =
      envelope["payload"] as? JsonObject
        ?: throw DesignStoreCorruptionException("missing payload at $location")
    val expected = envelope.string("checksumSha256")
    val actual = sha256Hex(canonicalJson(payload))
    if (expected != actual) {
      throw DesignStoreCorruptionException("checksum mismatch at $location")
    }
    return payload
  }

  private fun encodeEvent(event: CollaborationEvent): JsonObject = buildJsonObject {
    put("schema", EVENT_SCHEMA)
    when (val mutation = event.mutation) {
      is RejectedMutation.Design -> {
        put("kind", "design")
        put("mutation", json.encodeToJsonElement(mutation.command))
      }
      is RejectedMutation.Undo -> {
        put("kind", "undo")
        put("mutation", json.encodeToJsonElement(mutation.command))
      }
      is RejectedMutation.Redo -> {
        put("kind", "redo")
        put("mutation", json.encodeToJsonElement(mutation.command))
      }
    }
    put("outcome", encodeOutcome(event.outcome))
  }

  private fun decodeEvent(payload: JsonObject, location: String): CollaborationEvent {
    if (payload.string("schema") != EVENT_SCHEMA) {
      throw DesignStoreCorruptionException("unsupported event schema at $location")
    }
    val mutationElement =
      payload["mutation"] ?: throw DesignStoreCorruptionException("missing mutation at $location")
    val mutation =
      try {
        when (payload.string("kind")) {
          "design" ->
            RejectedMutation.Design(json.decodeFromJsonElement<DesignCommand>(mutationElement))
          "undo" -> RejectedMutation.Undo(json.decodeFromJsonElement<UndoCommand>(mutationElement))
          "redo" -> RejectedMutation.Redo(json.decodeFromJsonElement<RedoCommand>(mutationElement))
          else -> throw DesignStoreCorruptionException("unknown mutation kind at $location")
        }
      } catch (failure: DesignStoreCorruptionException) {
        throw failure
      } catch (failure: Exception) {
        throw DesignStoreCorruptionException("invalid mutation at $location", failure)
      }
    val outcome =
      try {
        decodeOutcome(payload.getValue("outcome").jsonObject)
      } catch (failure: Exception) {
        throw DesignStoreCorruptionException("invalid outcome at $location", failure)
      }
    return CollaborationEvent(mutation, outcome)
  }

  private fun encodeOutcome(outcome: CommandOutcome): JsonObject = buildJsonObject {
    when (outcome) {
      is CommandOutcome.Accepted -> {
        put("kind", "accepted")
        put("committedRevision", outcome.committedRevision)
        put("canonicalDocument", outcome.canonicalDocument)
        put("idempotentReplay", outcome.idempotentReplay)
        put(
          "conflicts",
          JsonArray(
            outcome.conflicts.map { conflict ->
              buildJsonObject {
                put("code", conflict.code.name)
                put("nodeId", conflict.nodeId)
                conflict.field?.let { put("field", it) }
                put("overwrittenRevision", conflict.overwrittenRevision)
              }
            }
          ),
        )
      }
      is CommandOutcome.Rejected -> {
        put("kind", "rejected")
        put("code", outcome.code.name)
        put("message", outcome.message)
        outcome.operationIndex?.let { put("operationIndex", it) }
        outcome.nodeId?.let { put("nodeId", it) }
        outcome.field?.let { put("field", it) }
      }
    }
  }

  private fun decodeOutcome(value: JsonObject): CommandOutcome =
    when (value.string("kind")) {
      "accepted" ->
        CommandOutcome.Accepted(
          committedRevision = value.requiredInt("committedRevision"),
          canonicalDocument = value.string("canonicalDocument"),
          idempotentReplay = value.requiredBoolean("idempotentReplay"),
          conflicts =
            value["conflicts"]?.jsonArray.orEmpty().map { encoded ->
              val conflict = encoded.jsonObject
              ConflictNotice(
                code = ConflictCode.valueOf(conflict.string("code")),
                nodeId = conflict.string("nodeId"),
                field = conflict.optionalString("field"),
                overwrittenRevision = conflict.requiredInt("overwrittenRevision"),
              )
            },
        )
      "rejected" ->
        CommandOutcome.Rejected(
          code = RejectionCode.valueOf(value.string("code")),
          message = value.string("message"),
          operationIndex = value.optionalInt("operationIndex"),
          nodeId = value.optionalString("nodeId"),
          field = value.optionalString("field"),
        )
      else -> throw IllegalArgumentException("unknown outcome kind")
    }

  private fun atomicWriteNew(target: Path, bytes: ByteArray) {
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
      FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
      }
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, target)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun forceDirectory(directory: Path) {
    try {
      FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
      // Some file systems cannot open directories. The file itself was already forced.
    }
  }

  private fun validateDesignId(value: String): String {
    require(value.matches(Regex("[A-Za-z0-9._-]+"))) { "design id is not path-safe" }
    return value
  }

  private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("$name must be text")

  private fun JsonObject.optionalString(name: String): String? =
    (this[name] ?: JsonNull).let { value ->
      if (value is JsonNull) null else value.jsonPrimitive.contentOrNull
    }

  private fun JsonObject.requiredInt(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull
      ?: throw IllegalArgumentException("$name must be an integer")

  private fun JsonObject.requiredLong(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull
      ?: throw DesignStoreCorruptionException("$name must be a long integer")

  private fun JsonObject.optionalInt(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

  private fun JsonObject.requiredBoolean(name: String): Boolean =
    this[name]?.jsonPrimitive?.content?.toBooleanStrict()
      ?: throw IllegalArgumentException("$name must be a boolean")

  companion object {
    private const val SNAPSHOT_FILE = "snapshot.json"
    private const val EVENTS_FILE = "events.jsonl"
    private const val SNAPSHOT_SCHEMA = "compose-ui-builder-store-snapshot/v2"
    private const val LEGACY_SNAPSHOT_SCHEMA = "compose-ui-builder-store-snapshot/v1"
    private const val EVENT_SCHEMA = "compose-ui-builder-store-event/v1"
    private val json = Json { encodeDefaults = true }
  }
}
