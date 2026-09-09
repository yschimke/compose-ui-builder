package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CommittedOperationV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Everything a store holds, as the service wants it at construction.
 *
 * [quarantined] carries the designs whose files could not be read, by id and reason, so a design
 * that cannot be loaded costs exactly itself: the single-file store had one checksum over every
 * design at once, which is why one bad byte took the whole lane down.
 */
internal data class StoredDesigns(
  val designs: Map<String, PersistedDesignV1> = emptyMap(),
  val quarantined: Map<String, String> = emptyMap(),
)

/**
 * Where the UI builder's designs are kept.
 *
 * The seam this replaces — [UiBuilderStateStorage], `load(): ByteArray?` and `replace(ByteArray)` —
 * can express nothing except "here is all of it", so an edit to one design rewrote every design.
 * This one names the design an edit touched, and is handed the value before and after so it can
 * write only the parts that differ. See `docs/design/UI_BUILDER_STATE_STORAGE.md`.
 */
internal interface UiBuilderDesignStore {
  /** Every stored design. Called once, at construction. */
  fun load(): StoredDesigns

  /**
   * Persists [next], writing only the parts in which it differs from [previous].
   *
   * [previous] is what this store last held for the design, or null when it held nothing. The
   * candidate is built with `copy()`, so an untouched part is the same object and the comparison
   * costs a pointer.
   */
  fun commit(designId: String, previous: PersistedDesignV1?, next: PersistedDesignV1)

  fun remove(designId: String)

  /**
   * The key of the quarantine occupying the place [designId] would be written to, or null.
   *
   * Not the same question as "is this id quarantined". A design whose header cannot be parsed has
   * no id to be quarantined under — it is reported under its directory instead — and a design
   * restored under someone else's name is reported under that name. Both still hold a place on the
   * disk, and creating a design into a place a quarantine holds is how one design becomes two: the
   * new design serves while the stale quarantine still names its directory, and retiring the
   * quarantine takes the new design with it.
   */
  fun quarantineHolding(designId: String): String? = null

  /** What is held against the ceiling that would refuse a write, or null when there is neither. */
  fun usage(): UiBuilderStorageUsage? = null
}

/**
 * The v3 per-design store on disk, as a host constructs it.
 *
 * Opaque on purpose: the shapes it reads and writes are the service's own persistence model, which
 * is not published, so this exposes only what a host needs — where the state is and how full it is.
 */
public class UiBuilderDesignStateStore
internal constructor(internal val store: UiBuilderDesignStore) {
  /** What the store holds against the ceiling a write is refused at. */
  public fun usage(): UiBuilderStorageUsage? = store.usage()

  public companion object {
    /** The marker whose presence means a state directory holds the per-design store. */
    public const val STORE_FILE: String = "store.json"

    /** The directory the designs are under, named because an operator's recovery acts on it. */
    public const val DESIGNS_DIRECTORY: String = "designs"

    /**
     * Opens the store under [root], migrating a v2 single-file state the first time it is found.
     *
     * The migration is one-shot and one-directional: `store.json` present means v3 and the old file
     * is ignored; otherwise `ui-builder-service-v1.json` is decoded, written out per design, and
     * renamed to `.migrated` — never deleted, because it is the rollback.
     */
    @JvmStatic
    public fun open(
      root: Path,
      limits: UiBuilderStoreLimits = UiBuilderStoreLimits(),
    ): UiBuilderDesignStateStore = UiBuilderDesignStateStore(FileUiBuilderDesignStore(root, limits))
  }
}

/**
 * The byte budgets the per-design store applies.
 *
 * A design can no longer fail to save because another design grew, so the one whole-store cliff
 * becomes two numbers that say what they bound: [maximumDesignBytes] refuses a write to the design
 * that is too large, and [maximumBytes] is the gauge an operator watches.
 */
public data class UiBuilderStoreLimits(
  /** Reported by [UiBuilderDesignStateStore.usage]; nothing is refused for reaching it. */
  val maximumBytes: Long = 1_024L * 1_024 * 1_024,
  /** A single design's own ceiling: its parts, its retained revisions and its journal. */
  val maximumDesignBytes: Long = 64L * 1_024 * 1_024,
  /** The journal is rewritten once it is this large and this much larger than what it holds. */
  val journalCompactionBytes: Long = 256L * 1_024,
  /**
   * The largest v2 state file the one-shot migration will read into memory.
   *
   * The same ceiling `FileUiBuilderStateStorage` refused a read at, kept for the same reason: the
   * migration reads the whole file at once, and a file large enough to exhaust the heap would take
   * the host down rather than the lane.
   */
  val maximumMigrationBytes: Long = 128L * 1_024 * 1_024,
  val journalCompactionRatio: Int = 4,
) {
  init {
    require(maximumBytes > 0) { "maximumBytes must be positive" }
    require(maximumDesignBytes > 0) { "maximumDesignBytes must be positive" }
    require(journalCompactionBytes > 0) { "journalCompactionBytes must be positive" }
    require(maximumMigrationBytes > 0) { "maximumMigrationBytes must be positive" }
    require(journalCompactionRatio > 1) { "journalCompactionRatio must be greater than one" }
  }
}

/** The header of one stored design: which file each part is in, and what a listing needs. */
@Serializable
internal data class StoredDesignHeaderV3(
  val designId: String,
  val title: String,
  val revision: Long,
  val lastSequence: Long,
  val access: DesignAccessControlV1,
  val catalogPin: CatalogReferenceV1,
  val createdAtEpochMillis: Long,
  val updatedAtEpochMillis: Long,
  val documentFile: String,
  val positionsFile: String,
  /** Retained revision to the file holding it, keyed by revision as a string because JSON. */
  val revisionFiles: Map<String, String> = emptyMap(),
  val journalFile: String? = null,
  /**
   * How much of [journalFile] this header commits to.
   *
   * The append happens before the header lands, so a crash between the two leaves records nothing
   * points at. Replay reads exactly this many bytes; the tail is truncated by the next commit.
   */
  val journalBytes: Long = 0,
  /**
   * What writing this design's collections out whole cost, the last time that was done.
   *
   * The measure of when a journal stops paying: appends are worth it while the file is small
   * against what it describes, and a file several times that size is cheaper to replace than to
   * keep replaying.
   */
  val journalCompactedBytes: Long = 0,
)

/** One retained revision: the document at it, the positions at it, or both. */
@Serializable
internal data class StoredRevisionV3(
  val revision: Long,
  val sequence: Long? = null,
  val document: DesignDocumentV1? = null,
  val positions: Map<String, StableNodePositionV1>? = null,
)

/** The current position map, in a file of its own because it is replaced whole. */
@Serializable
internal data class StoredPositionsV3(val positions: Map<String, StableNodePositionV1>)

/**
 * One commit's changes to the collections that grow by an entry per edit.
 *
 * `history`, `audit`, `operationOutcomes`, `acceptedOperations` and `tombstones` are appended to
 * and trimmed from the front, so writing each as a whole file would put the store back where it
 * started: a design holding four megabytes of undo state would rewrite four megabytes to record one
 * outcome. Every field is null when that collection did not change.
 */
@Serializable
internal data class JournalEntryV3(
  val historyAppend: List<CommittedOperationV1>? = null,
  val historyKeep: Int? = null,
  val historySet: List<CommittedOperationV1>? = null,
  val auditAppend: List<AuditRecordV1>? = null,
  val auditKeep: Int? = null,
  val auditSet: List<AuditRecordV1>? = null,
  val outcomesPut: Map<String, OperationOutcomeRecordV1>? = null,
  val outcomesRemoved: List<String>? = null,
  val acceptedPut: Map<String, AcceptedOperationRecordV1>? = null,
  val acceptedRemoved: List<String>? = null,
  val tombstonesPut: Map<String, NodeTreeSnapshotV1>? = null,
  val tombstonesRemoved: List<String>? = null,
)

/**
 * One journal line: a record, and a checksum of the record as stored.
 *
 * The header's `journalBytes` says which records are *committed*, which is a different question
 * from whether the bytes are still the bytes — a torn tail is not corruption. Every other part of a
 * design carries a checksum of its own stored tree, and a journal record that flipped a bit while
 * staying valid JSON would otherwise be replayed as though it were authored: an outcome, an undo
 * record or a tombstone, silently changed. Per record rather than over the whole prefix, because a
 * digest of everything committed would cost the whole journal on every append, which is the cost
 * the journal exists to avoid.
 */
@Serializable internal data class JournalLineV3(val checksumSha256: String, val entry: JsonElement)

/**
 * Why one design could not be read, written beside it rather than thrown.
 *
 * [designId] is recorded because the header it came from is, by definition, the thing that could
 * not be read: recovering the id a second time on the next start would fall back to the directory's
 * hash, and the id an operator uses to retire the design would change under them at a restart.
 */
@Serializable
internal data class StoredQuarantineV3(
  val reason: String,
  val recordedAtEpochMillis: Long,
  val designId: String? = null,
)

@Serializable
internal data class StoreMarkerV3(val format: String, val migratedFrom: String? = null)

/**
 * One design's parts as they are on disk, so the next commit knows what it may reuse.
 *
 * Held per design rather than derived from the header on each write because it also carries the
 * journal's live size, which decides when the journal is rewritten.
 */
private data class DesignFiles(val header: StoredDesignHeaderV3, val bytes: Long)

/**
 * One directory per design, one file per part, and a header that names them.
 *
 * A part file is named by the digest of what is in it, so a commit's new parts are invisible until
 * `design.json` names them and the header rename is the moment the design becomes the new one. A
 * crash before it leaves the previous generation complete; a crash after it leaves files nothing
 * references, which the next open of that design unlinks. See
 * `docs/design/UI_BUILDER_STATE_STORAGE.md`.
 */
internal class FileUiBuilderDesignStore(
  root: Path,
  private val limits: UiBuilderStoreLimits = UiBuilderStoreLimits(),
) : UiBuilderDesignStore {
  private val directory = root.toAbsolutePath().normalize()
  private val designsDirectory = directory.resolve(DESIGNS_DIRECTORY)
  private val markerFile = directory.resolve(STORE_FILE)
  private val lockFile = directory.resolve(LOCK_FILE)
  private val files = linkedMapOf<String, DesignFiles>()
  /**
   * The designs that could not be read, by id and directory.
   *
   * Held because a quarantined design is still the operator's content and still on the disk: it is
   * absent from the service's design map, so `remove` has nothing else to resolve its directory
   * from, and retiring it is the one admin action that must keep working when reading it does not.
   */
  private val quarantinedSlugs = linkedMapOf<String, String>()
  private var storedBytes = 0L
  /** Whether [storedBytes] has been measured, by a load or by the first [usage] before one. */
  private var counted = false

  init {
    Files.createDirectories(directory)
    require(Files.isDirectory(directory)) { "UI-builder state root is not a directory: $directory" }
    // Opening is what migrates a v2 file and what declares the format, and it happens here rather
    // than on the first read so that a store this build cannot read fails where `serve` already
    // catches it — one warning and a disabled lane, never a half-open store
    // (yschimke/compose-preview-server#568).
    locked {
      migrateLegacyStateIfPresent()
      if (Files.exists(markerFile)) readMarker() else writeMarker(StoreMarkerV3(STORE_FORMAT))
    }
  }

  override fun load(): StoredDesigns = locked {
    val designs = linkedMapOf<String, PersistedDesignV1>()
    val quarantined = linkedMapOf<String, String>()
    storedBytes = 0
    for (slug in designSlugs()) {
      val designDirectory = designsDirectory.resolve(slug)
      val misplaced = misplacedDesignId(designDirectory, slug)
      if (misplaced != null) {
        // Reported before the quarantine file is even read: a copy carries the original's
        // `quarantine.json` too, and that record names the design id, which is exactly the id this
        // directory must not be allowed to answer for.
        quarantined[slug] =
          "design $misplaced is stored at $DESIGNS_DIRECTORY/$slug rather than at " +
            "$DESIGNS_DIRECTORY/${slug(misplaced)}; move it back under that name to serve it, or " +
            "retire it by the name it has"
        quarantinedSlugs[slug] = slug
        storedBytes += runCatching { directoryBytes(designDirectory) }.getOrDefault(0L)
        continue
      }
      val existingQuarantine = readQuarantine(designDirectory)
      if (existingQuarantine != null) {
        quarantined[existingQuarantine.first] = existingQuarantine.second
        quarantinedSlugs[existingQuarantine.first] = slug
        // Counted even though it cannot be decoded: a large corrupt design is still on the disk,
        // and a gauge that called those bytes free would be wrong exactly when an operator needs to
        // notice that broken data is being retained. Best-effort, because a directory that cannot
        // even be walked must cost this design and not the load: a gauge that took the lane down
        // would be the failure this store exists to prevent, one scope larger.
        storedBytes += runCatching { directoryBytes(designDirectory) }.getOrDefault(0L)
        continue
      }
      try {
        val header = readHeader(designDirectory)
        val design = readDesign(designDirectory, header)
        designs[header.designId] = design
        // Swept before it is measured. A crash or a refused commit leaves files the header does not
        // name, and this is where they go; measured first, the gauge would charge them for the life
        // of the process — against a ceiling that refuses writes — and only a later commit of that
        // same design would put it right.
        //
        // Both best-effort, because past `readDesign` this design is servable and neither of these
        // is reading it: unrelated garbage that cannot be walked — an unreadable crash artifact, a
        // permission on a subdirectory — must cost the tidying and the gauge's precision, never the
        // design. The gauge falls back to what the header references, which is a floor rather than
        // a guess.
        runCatching { sweep(designDirectory, header) }
        val bytes = runCatching {
          directoryBytes(designDirectory)
        }
          .recoverCatching { referencedBytes(designDirectory, header) }
          .getOrDefault(0L)
        files[header.designId] = DesignFiles(header, bytes)
        storedBytes += bytes
      } catch (failure: Exception) {
        val designId = quarantineDesignId(designDirectory, slug)
        val reason = failure.message ?: failure::class.simpleName ?: "unreadable"
        writeQuarantine(designDirectory, designId, reason)
        quarantined[designId] = reason
        quarantinedSlugs[designId] = slug
        storedBytes += runCatching { directoryBytes(designDirectory) }.getOrDefault(0L)
      }
    }
    // A quarantine is reported under the id in its header, and where there is no id to read, under
    // the directory holding it — which an operator chose and which can be anything, including the
    // id of a design that loads perfectly well from its own directory. Left colliding, the live
    // design is reported unusable and a delete aimed at the quarantine takes it out of the service
    // while removing the backup from the disk. So the quarantine yields the name: it is the one of
    // the two that has no id of its own to insist on.
    for (key in quarantined.keys.toList()) {
      if (key !in designs) continue
      val slug = quarantinedSlugs.getValue(key)
      var renamed = "$DESIGNS_DIRECTORY/$slug"
      while (renamed in designs || renamed in quarantined) renamed += "'"
      quarantined[renamed] = quarantined.remove(key)!!
      quarantinedSlugs[renamed] = quarantinedSlugs.remove(key)!!
    }
    counted = true
    StoredDesigns(designs, quarantined)
  }

  override fun commit(designId: String, previous: PersistedDesignV1?, next: PersistedDesignV1) {
    locked {
      val designDirectory = designsDirectory.resolve(slug(designId))
      val created = !Files.isDirectory(designDirectory)
      Files.createDirectories(designDirectory)
      if (created) {
        // Forcing a directory does not make its own name durable in its parent, so a design's first
        // commit forces the chain above it: without this a power loss can acknowledge a create and
        // then lose the `designs/<slug>` entry, and the design a caller was told exists is gone.
        forceDirectory(designsDirectory)
        forceDirectory(directory)
      }
      if (!Files.exists(markerFile)) writeMarker(StoreMarkerV3(STORE_FORMAT))
      val current = files[designId]
      val known = if (current == null) null else previous
      val written = mutableListOf<Path>()
      // What a refused commit has to undo in the journal, which [written] cannot express. A
      // compaction wrote a whole generation nothing references yet, and an append put bytes past
      // the length the current header commits to — and the appended file is not one this commit may
      // delete, because every committed record is still in front of those bytes. So the undo is
      // recorded per case as it is written. Left undone, a refusal leaves bytes on the disk that
      // the
      // gauge does not know about, and `/status.json` under-reports the store until that design's
      // next journal-changing commit or a restart.
      val journalUndo = mutableListOf<() -> Unit>()
      try {
        val documentFile =
          if (known != null && known.document == next.document && current != null) {
            current.header.documentFile
          } else {
            writePart(designDirectory, DOCUMENT_PART, json.encodeToJsonElement(next.document))
              .also { written.add(designDirectory.resolve(it)) }
          }
        val positionsFile =
          if (known != null && known.positions == next.positions && current != null) {
            current.header.positionsFile
          } else {
            writePart(
                designDirectory,
                POSITIONS_PART,
                json.encodeToJsonElement(StoredPositionsV3(next.positions)),
              )
              .also { written.add(designDirectory.resolve(it)) }
          }
        val revisionFiles =
          writeRevisions(designDirectory, known, next, current?.header?.revisionFiles.orEmpty()) {
            written.add(it)
          }
        val entry = journalEntry(known, next)
        val previousJournal = current?.header?.journalFile
        val previousJournalBytes = current?.header?.journalBytes ?: 0
        val compactedBytes = current?.header?.journalCompactedBytes ?: 0
        var compacted = previousJournal == null
        var journal =
          when {
            previousJournal == null -> compactJournal(designDirectory, next, generation = 1)
            entry == null -> JournalWrite(previousJournal, previousJournalBytes, compactedBytes)
            shouldCompact(previousJournalBytes, compactedBytes) -> {
              compacted = true
              compactJournal(
                  designDirectory,
                  next,
                  generation = journalGeneration(previousJournal) + 1,
                )
                .also { journalUndo.add { discardJournal(designDirectory.resolve(it.file)) } }
            }
            else ->
              appendJournal(
                  designDirectory,
                  previousJournal,
                  previousJournalBytes,
                  entry,
                  compactedBytes,
                )
                .also {
                  journalUndo.add {
                    truncateJournal(designDirectory.resolve(previousJournal), previousJournalBytes)
                  }
                }
          }
        fun headerFor(written: JournalWrite) =
          StoredDesignHeaderV3(
            designId = designId,
            title = next.document.title,
            revision = next.document.revision,
            lastSequence = next.lastSequence,
            access = next.access,
            catalogPin = next.document.catalogPin,
            createdAtEpochMillis = next.createdAtEpochMillis,
            updatedAtEpochMillis = next.updatedAtEpochMillis,
            documentFile = documentFile,
            positionsFile = positionsFile,
            revisionFiles = revisionFiles,
            journalFile = written.file,
            journalBytes = written.bytes,
            journalCompactedBytes = written.compactedBytes,
          )
        var header = headerFor(journal)
        // The budget is checked before the header lands, because the header is what makes the new
        // generation the design. Checked after it, a refused write would already be durable: the
        // caller would be told its edit failed and a restart would load the edit it was told had
        // failed. Refusing here leaves the previous generation whole and the parts this commit
        // wrote unreferenced, which the cleanup below unlinks and the next open would sweep anyway.
        var encodedHeader = encodeHeader(header)
        var bytes = referencedBytes(designDirectory, header) + encodedHeader.size
        if (bytes > limits.maximumDesignBytes && !compacted) {
          // Compaction is decided from the journal's length against what it describes, and a design
          // whose live collections are large enough can reach the budget before that ratio is ever
          // met — every edit refused, for room a rewrite would give back. So the budget asks for
          // the
          // rewrite rather than refusing on the strength of bytes nothing needs to keep.
          journal =
            compactJournal(
                designDirectory,
                next,
                generation = journalGeneration(journal.file) + 1,
              )
              .also { journalUndo.add { discardJournal(designDirectory.resolve(it.file)) } }
          compacted = true
          header = headerFor(journal)
          encodedHeader = encodeHeader(header)
          bytes = referencedBytes(designDirectory, header) + encodedHeader.size
        }
        if (bytes > limits.maximumDesignBytes) {
          discardUncommitted(designDirectory, current, written, journalUndo)
          throw UiBuilderPersistenceException(
            "UI-builder design $designId is $bytes bytes; limit is ${limits.maximumDesignBytes}"
          )
        }
        writeHeader(designDirectory, encodedHeader)
        // Past this line the design *is* the new one, so nothing below may roll anything back: the
        // rollback path deletes parts the durable header now names, and for a first commit it would
        // delete the design itself. Tidying is best-effort, and the next open finishes any of it
        // that did not happen here.
        runCatching { Files.deleteIfExists(designDirectory.resolve(QUARANTINE_FILE)) }
        runCatching { sweep(designDirectory, header) }
        // Measured after the sweep rather than taken from the budget: a generation the sweep could
        // not remove is still on the disk, and a gauge that counted only what the header references
        // would lose those bytes until a restart. The budget is the right question for what a
        // design
        // may *become*; the gauge is the question of what it currently costs.
        val stored = runCatching {
          directoryBytes(designDirectory)
        }
          .getOrDefault(bytes.toLong())
          .toLong()
        storedBytes += stored - (current?.bytes ?: 0)
        files[designId] = DesignFiles(header, stored)
      } catch (failure: UiBuilderPersistenceException) {
        throw failure
      } catch (failure: IOException) {
        discardUncommitted(designDirectory, current, written, journalUndo)
        throw UiBuilderPersistenceException(
          "cannot store UI-builder design $designId under $designDirectory",
          failure,
        )
      }
    }
  }

  /**
   * Undoes a commit that never became the design.
   *
   * For a design that existed before, the parts this commit wrote are unreferenced and go; the
   * previous header still names a complete generation. For a design's **first** commit there is no
   * previous generation, and leaving a headerless directory behind would be worse than the failure:
   * the next open reads it as a corrupt design and quarantines it under the directory's hash, and
   * that phantom shares its directory with the real design if the id is ever created successfully —
   * so retiring the phantom would delete the design that replaced it.
   */
  private fun discardUncommitted(
    designDirectory: Path,
    current: DesignFiles?,
    written: List<Path>,
    journalUndo: List<() -> Unit> = emptyList(),
  ) {
    if (current == null) {
      // Renamed rather than unlinked, and only then unlinked, for the same reason `remove` renames:
      // the rename is one operation that either happened or did not, while a recursive unlink can
      // stop halfway. Best-effort deletion is what leaves the phantom this exists to avoid — a
      // headerless directory the next open quarantines under a hash, holding an id nobody can
      // create until an operator retires it. Everything under the deleted directory is the store's
      // own garbage, so a rename there needs nothing else to be true.
      val discarded = runCatching {
        val tombstone = tombstoneFor(designDirectory)
        Files.createDirectories(tombstone.parent)
        Files.move(designDirectory, tombstone, StandardCopyOption.ATOMIC_MOVE)
        tombstone
      }
        .getOrNull()
      runCatching { deleteRecursively(discarded ?: designDirectory) }
    } else {
      written.forEach { runCatching { Files.deleteIfExists(it) } }
      journalUndo.forEach { runCatching { it() } }
    }
  }

  /** A journal generation this commit wrote and no header will ever name. */
  private fun discardJournal(path: Path) {
    Files.deleteIfExists(path)
  }

  /**
   * Cuts an appended journal back to the length the current header commits to.
   *
   * Not a delete: every committed record is in front of these bytes, and the header that names them
   * is the one still current. The read side would ignore the tail either way — it reads the
   * committed prefix — so this is about the bytes on the disk and the gauge that counts them.
   */
  private fun truncateJournal(path: Path, length: Long) {
    FileChannel.open(path, StandardOpenOption.WRITE).use {
      it.truncate(length)
      it.force(true)
    }
  }

  /**
   * Where a deleted design goes: a directory of the store's own, never a name beside the designs.
   *
   * A tombstone used to be `<directory>.deleted-<millis>` next to the live designs, and the next
   * open unlinked whatever matched that shape. But this store takes an unfamiliar directory name as
   * the operator's content — it quarantines a design restored under one rather than assuming it is
   * garbage — and no name is proof of who wrote it: `checkout.deleted-1700000000000` is a plausible
   * backup, and deleting somebody's only copy is the one outcome this store must never produce. So
   * a deletion moves the design into a directory nothing else writes to, and everything under it is
   * garbage by where it is rather than by a guess about what it is called.
   */
  private fun tombstoneFor(designDirectory: Path): Path {
    val deleted = designsDirectory.resolve(DELETED_DIRECTORY)
    val stamp = System.currentTimeMillis()
    var candidate = deleted.resolve("${designDirectory.fileName}-$stamp")
    var attempt = 1
    while (Files.exists(candidate)) {
      candidate = deleted.resolve("${designDirectory.fileName}-$stamp-${attempt++}")
    }
    return candidate
  }

  override fun remove(designId: String) {
    locked {
      // A quarantined design's id came out of a header this build could not otherwise read, so its
      // directory is the one recorded at load rather than one derived from the id.
      val designDirectory = designsDirectory.resolve(quarantinedSlugs[designId] ?: slug(designId))
      val bytes =
        files[designId]?.bytes ?: runCatching { directoryBytes(designDirectory) }.getOrDefault(0L)
      var reclaimed = true
      try {
        // The rename is the deletion. A recursive unlink that fails halfway would leave the design
        // half gone while the caller was told the delete failed and the service kept it in memory —
        // a restart would then lose or quarantine a design it had been told still existed. Renaming
        // out of the way is atomic, so after it the design is gone whatever happens next; the
        // unlink of the tombstone is cleanup, and a failure there costs disk rather than truth.
        // Attempted rather than guarded by `Files.exists`, which answers false both for a design
        // that is not there and for one it could not look at: a permission that changed under the
        // host, a transient failure on the filesystem. Guarded, the second case skipped the rename,
        // reported a successful delete, and dropped the design from the map — and the directory it
        // never touched came back at the next open. Only the filesystem saying the source is not
        // there means the design is already gone; the parent is created first so that answer can
        // only be about the design.
        val tombstone = tombstoneFor(designDirectory)
        Files.createDirectories(tombstone.parent)
        val moved =
          try {
            Files.move(designDirectory, tombstone, StandardCopyOption.ATOMIC_MOVE)
            true
          } catch (_: NoSuchFileException) {
            false
          }
        if (moved) {
          forceDirectory(designsDirectory)
          // The design is gone either way; the disk is only given back when the unlink finishes, so
          // the gauge follows the disk rather than the design.
          if (runCatching { deleteRecursively(tombstone) }.isFailure) reclaimed = false
        }
        // Only now: the rename is what made the design gone, and bookkeeping that ran ahead of it
        // would leave the store believing a design it still holds was deleted — after which a retry
        // finds nothing, reports success, and the directory comes back on the next open.
        files.remove(designId)
        quarantinedSlugs.remove(designId)
        if (reclaimed) {
          storedBytes -= bytes
          if (storedBytes < 0) storedBytes = 0
        }
      } catch (failure: IOException) {
        throw UiBuilderPersistenceException(
          "cannot remove UI-builder design $designId at $designDirectory",
          failure,
        )
      }
    }
  }

  override fun quarantineHolding(designId: String): String? {
    val slug = slug(designId)
    return quarantinedSlugs.entries.firstOrNull { it.value == slug }?.key
  }

  override fun usage(): UiBuilderStorageUsage {
    // `load` is what counts the store, and a host is not obliged to have called it: the store is
    // published, so a capacity check at startup can reach this before any service is built and
    // would otherwise be told an existing store is empty. Measured here instead, once, and only on
    // that path — after a load this is the number load computed, which is the accurate one.
    if (!counted) {
      storedBytes = runCatching { directoryBytes(designsDirectory) }.getOrDefault(0L)
      counted = true
    }
    return UiBuilderStorageUsage(storedBytes, limits.maximumBytes)
  }

  // ---------------------------------------------------------------- reading

  private fun designSlugs(): List<String> {
    if (!Files.isDirectory(designsDirectory)) return emptyList()
    val slugs = mutableListOf<String>()
    Files.newDirectoryStream(designsDirectory).use { entries ->
      entries.forEach {
        val name = it.fileName.toString()
        // Everything under the store's own deleted directory is a design whose deletion committed
        // and whose unlink did not finish — garbage because of where it is rather than because of
        // what it is called, which is the only test that cannot mistake an operator's backup for
        // one. See [tombstoneFor].
        if (Files.isDirectory(it) && name == DELETED_DIRECTORY) {
          // Cleanup that did not finish. Retried here, and while it keeps failing its bytes are
          // still charged: a gauge that called a tombstone free would report disk nothing can use
          // as available, and it is the deletes that fail which leave the most of it.
          if (runCatching { deleteRecursively(it) }.isFailure) {
            storedBytes += runCatching { directoryBytes(it) }.getOrDefault(0L)
          }
        } else if (Files.isDirectory(it)) slugs.add(name)
      }
    }
    return slugs.sorted()
  }

  private fun readHeader(designDirectory: Path): StoredDesignHeaderV3 =
    decodeChecked(designDirectory.resolve(HEADER_FILE), "header")

  private fun readDesign(
    designDirectory: Path,
    header: StoredDesignHeaderV3,
  ): PersistedDesignV1 {
    val document: DesignDocumentV1 =
      decodeChecked(designDirectory.resolve(header.documentFile), "document")
    val positions: StoredPositionsV3 =
      decodeChecked(designDirectory.resolve(header.positionsFile), "positions")
    val revisions =
      header.revisionFiles.entries
        .sortedBy { it.key.toLongOrNull() ?: 0L }
        .map { (_, file) ->
          decodeChecked<StoredRevisionV3>(designDirectory.resolve(file), "revision")
        }
    val journal = replayJournal(designDirectory, header)
    return PersistedDesignV1(
      document = document,
      lastSequence = header.lastSequence,
      access = header.access,
      history = journal.history,
      revisionSnapshots =
        revisions.mapNotNull { retained ->
          retained.document?.let { RevisionStateV1(it, retained.sequence ?: header.lastSequence) }
        },
      operationOutcomes = journal.outcomes,
      acceptedOperations = journal.accepted,
      tombstones = journal.tombstones,
      positions = positions.positions,
      positionSnapshots =
        revisions.mapNotNull { retained ->
          retained.positions?.let { PositionStateV1(retained.revision, it) }
        },
      createdAtEpochMillis = header.createdAtEpochMillis,
      updatedAtEpochMillis = header.updatedAtEpochMillis,
      audit = journal.audit,
    )
  }

  private data class JournalState(
    val history: List<CommittedOperationV1> = emptyList(),
    val audit: List<AuditRecordV1> = emptyList(),
    val outcomes: Map<String, OperationOutcomeRecordV1> = emptyMap(),
    val accepted: Map<String, AcceptedOperationRecordV1> = emptyMap(),
    val tombstones: Map<String, NodeTreeSnapshotV1> = emptyMap(),
  )

  private fun replayJournal(
    designDirectory: Path,
    header: StoredDesignHeaderV3,
  ): JournalState {
    val file = header.journalFile ?: return JournalState()
    val path = designDirectory.resolve(file)
    if (!Files.exists(path)) {
      throw UiBuilderPersistenceException("UI-builder journal $file is missing")
    }
    // Only the committed prefix is read, and only if the header's own claim is inside the design's
    // budget. Reading the file whole would make a runaway or hand-copied journal an
    // `OutOfMemoryError`, which is not an `Exception`: it would escape the per-design quarantine
    // below *and* the lane guard above, and take the host down over one design's bytes.
    if (header.journalBytes > limits.maximumDesignBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder journal $file commits to ${header.journalBytes} bytes; the per-design limit " +
          "is ${limits.maximumDesignBytes}"
      )
    }
    val size = Files.size(path)
    if (size < header.journalBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder journal $file is $size bytes; the header commits to ${header.journalBytes}"
      )
    }
    val committed = readPrefix(path, header.journalBytes).decodeToString()
    var history = emptyList<CommittedOperationV1>()
    var audit = emptyList<AuditRecordV1>()
    var outcomes = emptyMap<String, OperationOutcomeRecordV1>()
    var accepted = emptyMap<String, AcceptedOperationRecordV1>()
    var tombstones = emptyMap<String, NodeTreeSnapshotV1>()
    committed
      .lineSequence()
      .filter { it.isNotBlank() }
      .forEach { line ->
        val stored =
          try {
            journalJson.decodeFromString(JournalLineV3.serializer(), line)
          } catch (failure: Exception) {
            throw UiBuilderPersistenceException(
              "invalid UI-builder journal record in $file: ${failure.message}",
              failure,
            )
          }
        if (sha256(canonicalJson(stored.entry).encodeToByteArray()) != stored.checksumSha256) {
          throw UiBuilderPersistenceException(
            "UI-builder journal record checksum mismatch in $file"
          )
        }
        val entry =
          try {
            journalJson.decodeFromJsonElement(JournalEntryV3.serializer(), stored.entry)
          } catch (failure: Exception) {
            throw UiBuilderPersistenceException(
              "invalid UI-builder journal record in $file: ${failure.message}",
              failure,
            )
          }
        entry.historySet?.let { history = it }
        entry.historyAppend?.let { appended ->
          history = (history + appended).let { it.takeLast(entry.historyKeep ?: it.size) }
        }
        entry.auditSet?.let { audit = it }
        entry.auditAppend?.let { appended ->
          audit = (audit + appended).let { it.takeLast(entry.auditKeep ?: it.size) }
        }
        entry.outcomesPut?.let { outcomes = outcomes + it }
        entry.outcomesRemoved?.let { removed -> outcomes = outcomes - removed.toSet() }
        entry.acceptedPut?.let { accepted = accepted + it }
        entry.acceptedRemoved?.let { removed -> accepted = accepted - removed.toSet() }
        entry.tombstonesPut?.let { tombstones = tombstones + it }
        entry.tombstonesRemoved?.let { removed -> tombstones = tombstones - removed.toSet() }
      }
    return JournalState(history, audit, outcomes, accepted, tombstones)
  }

  private inline fun <reified T> decodeChecked(path: Path, description: String): T {
    if (!Files.exists(path)) {
      throw UiBuilderPersistenceException("UI-builder $description ${path.fileName} is missing")
    }
    val encoded =
      try {
        readBounded(path, description).decodeToString()
      } catch (failure: IOException) {
        throw UiBuilderPersistenceException("cannot read UI-builder $description at $path", failure)
      }
    val root =
      try {
        json.parseToJsonElement(encoded).jsonObject
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException(
          "invalid UI-builder $description JSON at $path",
          failure,
        )
      }
    // The checksum covers the payload AS STORED — this parsed tree — and never a re-encode of the
    // decoded value, which is the discipline `PersistentUiBuilderService.decode` documents and the
    // reason a defaulted field arriving in the model does not make every stored file unreadable.
    val payload =
      root[PAYLOAD_FIELD]
        ?: throw UiBuilderPersistenceException("UI-builder $description payload is missing")
    val expected =
      (root[CHECKSUM_FIELD] as? JsonPrimitive)?.content
        ?: throw UiBuilderPersistenceException("UI-builder $description checksum is missing")
    if (sha256(canonicalJson(payload).encodeToByteArray()) != expected) {
      throw UiBuilderPersistenceException("UI-builder $description checksum mismatch at $path")
    }
    return try {
      json.decodeFromJsonElement(payload)
    } catch (failure: Exception) {
      throw UiBuilderPersistenceException("invalid UI-builder $description at $path", failure)
    }
  }

  // ---------------------------------------------------------------- writing

  private data class JournalWrite(val file: String, val bytes: Long, val compactedBytes: Long)

  private fun writeRevisions(
    designDirectory: Path,
    previous: PersistedDesignV1?,
    next: PersistedDesignV1,
    previousFiles: Map<String, String>,
    onWritten: (Path) -> Unit,
  ): Map<String, String> {
    val unchanged =
      previous != null &&
        previous.revisionSnapshots == next.revisionSnapshots &&
        previous.positionSnapshots == next.positionSnapshots
    if (unchanged) return previousFiles
    val documents = next.revisionSnapshots.associateBy { it.document.revision }
    val positions = next.positionSnapshots.associateBy { it.revision }
    val revisions = (documents.keys + positions.keys).sorted()
    val previousDocuments =
      previous?.revisionSnapshots?.associateBy { it.document.revision }.orEmpty()
    val previousPositions = previous?.positionSnapshots?.associateBy { it.revision }.orEmpty()
    val written = linkedMapOf<String, String>()
    for (revision in revisions) {
      val key = revision.toString()
      val retainedDocument = documents[revision]
      val retainedPositions = positions[revision]
      val same =
        previousDocuments[revision] == retainedDocument &&
          previousPositions[revision] == retainedPositions
      val existing = previousFiles[key]
      if (same && existing != null) {
        written[key] = existing
        continue
      }
      val stored =
        StoredRevisionV3(
          revision = revision,
          sequence = retainedDocument?.sequence,
          document = retainedDocument?.document,
          positions = retainedPositions?.positions,
        )
      val name =
        writePart(
          designDirectory,
          "$REVISIONS_DIRECTORY/$revision",
          json.encodeToJsonElement(stored),
        )
      onWritten(designDirectory.resolve(name))
      written[key] = name
    }
    return written
  }

  /**
   * The delta this commit adds to the journal, or null when none of those collections changed.
   *
   * A list that grew by appending records the tail and the length to keep, so a trim that dropped
   * entries from the front replays exactly. A list that was replaced outright — an asset write
   * clears `history` — records itself whole, because there is no append that describes it.
   */
  private fun journalEntry(
    previous: PersistedDesignV1?,
    next: PersistedDesignV1,
  ): JournalEntryV3? {
    val historyTail =
      if (previous != null && previous.history == next.history) null
      else appendedTail(previous?.history.orEmpty(), next.history)
    val auditTail =
      if (previous != null && previous.audit == next.audit) null
      else appendedTail(previous?.audit.orEmpty(), next.audit)
    val outcomes = mapDelta(previous?.operationOutcomes.orEmpty(), next.operationOutcomes)
    val accepted = mapDelta(previous?.acceptedOperations.orEmpty(), next.acceptedOperations)
    val tombstones = mapDelta(previous?.tombstones.orEmpty(), next.tombstones)
    val historyChanged = previous == null || previous.history != next.history
    val auditChanged = previous == null || previous.audit != next.audit
    if (
      !historyChanged && !auditChanged && outcomes == null && accepted == null && tombstones == null
    ) {
      return null
    }
    return JournalEntryV3(
      historyAppend = if (historyChanged) historyTail else null,
      historyKeep = if (historyChanged && historyTail != null) next.history.size else null,
      historySet = if (historyChanged && historyTail == null) next.history else null,
      auditAppend = if (auditChanged) auditTail else null,
      auditKeep = if (auditChanged && auditTail != null) next.audit.size else null,
      auditSet = if (auditChanged && auditTail == null) next.audit else null,
      outcomesPut = outcomes?.first,
      outcomesRemoved = outcomes?.second,
      acceptedPut = accepted?.first,
      acceptedRemoved = accepted?.second,
      tombstonesPut = tombstones?.first,
      tombstonesRemoved = tombstones?.second,
    )
  }

  private fun <T> appendedTail(previous: List<T>, next: List<T>): List<T>? {
    // Only a short tail is worth searching for: every mutation appends one record, and a commit
    // that changed a list any other way is cheaper to write out than to describe.
    for (size in 0..minOf(MAXIMUM_APPENDED_TAIL, next.size)) {
      val tail = next.takeLast(size)
      val replayed = (previous + tail).takeLast(next.size)
      if (replayed == next) return tail
    }
    return null
  }

  private fun <V> mapDelta(
    previous: Map<String, V>,
    next: Map<String, V>,
  ): Pair<Map<String, V>, List<String>>? {
    if (previous == next) return null
    val put = next.filter { (key, value) -> previous[key] != value }
    val removed = previous.keys.filter { it !in next }
    return put to removed
  }

  private fun shouldCompact(journalBytes: Long, compactedBytes: Long): Boolean =
    journalBytes > limits.journalCompactionBytes &&
      journalBytes > compactedBytes * limits.journalCompactionRatio

  private fun compactJournal(
    designDirectory: Path,
    next: PersistedDesignV1,
    generation: Int,
  ): JournalWrite {
    val entry =
      JournalEntryV3(
        historySet = next.history,
        auditSet = next.audit,
        outcomesPut = next.operationOutcomes.takeIf { it.isNotEmpty() },
        acceptedPut = next.acceptedOperations.takeIf { it.isNotEmpty() },
        tombstonesPut = next.tombstones.takeIf { it.isNotEmpty() },
      )
    val line = journalLine(entry)
    val name = "$JOURNAL_PREFIX$generation$JOURNAL_SUFFIX"
    val temporary = writeTemporary(designDirectory, name, line)
    replaceAtomically(temporary, designDirectory.resolve(name))
    return JournalWrite(name, line.size.toLong(), line.size.toLong())
  }

  private fun appendJournal(
    designDirectory: Path,
    file: String,
    committedBytes: Long,
    entry: JournalEntryV3,
    compactedBytes: Long,
  ): JournalWrite {
    val path = designDirectory.resolve(file)
    val line = journalLine(entry)
    FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
      // A previous commit may have appended and died before its header landed. Its records are
      // beyond the committed length, and this one overwrites them: the header is the commit.
      channel.truncate(committedBytes)
      channel.position(committedBytes)
      val buffer = ByteBuffer.wrap(line)
      while (buffer.hasRemaining()) channel.write(buffer)
      channel.force(true)
    }
    return JournalWrite(file, committedBytes + line.size, compactedBytes)
  }

  /** One record, checksummed over the tree that is written beside the number. */
  private fun journalLine(entry: JournalEntryV3): ByteArray {
    val payload = journalJson.encodeToJsonElement(JournalEntryV3.serializer(), entry)
    val checksum = sha256(canonicalJson(payload).encodeToByteArray())
    return (journalJson.encodeToString(
        JournalLineV3.serializer(),
        JournalLineV3(checksum, payload),
      ) + "\n")
      .encodeToByteArray()
  }

  private fun writePart(designDirectory: Path, part: String, payload: JsonElement): String {
    val checksum = sha256(canonicalJson(payload).encodeToByteArray())
    val name = "$part-${checksum.take(DIGEST_NAME_LENGTH)}$PART_SUFFIX"
    val path = designDirectory.resolve(name)
    // Deliberately written even when a file of that name is already there. The name says what the
    // content should be, not that the bytes on disk still are it — and the one moment that
    // distinction matters is the one that matters most: re-committing a design whose file was
    // corrupted in place is how it is repaired.
    Files.createDirectories(path.parent)
    val bytes =
      json.encodeToString(StoredPartSerializer, StoredPart(checksum, payload)).encodeToByteArray()
    val temporary = writeTemporary(path.parent, path.fileName.toString(), bytes)
    replaceAtomically(temporary, path)
    return name
  }

  /**
   * The header as it will be stored, so its size can be counted before it is written.
   *
   * `design.json` is a part like any other and can be substantial — an access list has no bound of
   * its own — so leaving it out of the budget let a design commit a header that the next open would
   * then refuse to read, quarantining a design that was accepted.
   */
  private fun encodeHeader(header: StoredDesignHeaderV3): ByteArray {
    val payload = json.encodeToJsonElement(header)
    val checksum = sha256(canonicalJson(payload).encodeToByteArray())
    return json
      .encodeToString(StoredPartSerializer, StoredPart(checksum, payload))
      .encodeToByteArray()
  }

  private fun writeHeader(designDirectory: Path, encoded: ByteArray) {
    val temporary = writeTemporary(designDirectory, HEADER_FILE, encoded)
    replaceAtomically(temporary, designDirectory.resolve(HEADER_FILE))
    forceDirectory(designDirectory)
  }

  private fun writeMarker(marker: StoreMarkerV3) {
    val bytes = json.encodeToString(StoreMarkerV3.serializer(), marker).encodeToByteArray()
    val temporary = writeTemporary(directory, STORE_FILE, bytes)
    replaceAtomically(temporary, markerFile)
    forceDirectory(directory)
  }

  private fun readMarker() {
    // Bounded before it is allocated, like every other read here. The marker is two fields, so a
    // large one is corruption — and reading it whole to discover that is an `OutOfMemoryError`,
    // which is not an `Exception` and so escapes both this handler and `serve`'s lane guard: a bad
    // `store.json` would cost the host rather than the UI-builder lane
    // (yschimke/compose-preview-server#568).
    val markerBytes = Files.size(markerFile)
    if (markerBytes > MAXIMUM_MARKER_BYTES) {
      throw UiBuilderPersistenceException(
        "UI-builder store marker at $markerFile is $markerBytes bytes; the limit is " +
          "$MAXIMUM_MARKER_BYTES"
      )
    }
    val marker =
      try {
        json.decodeFromString(
          StoreMarkerV3.serializer(),
          Files.readAllBytes(markerFile).decodeToString(),
        )
      } catch (failure: Exception) {
        throw UiBuilderPersistenceException(
          "invalid UI-builder store marker at $markerFile",
          failure,
        )
      }
    if (marker.format != STORE_FORMAT) {
      throw UiBuilderPersistenceException(
        "unsupported UI-builder store format ${marker.format} at $markerFile"
      )
    }
  }

  /** Unlinks the files in one design's directory that its header does not name. */
  private fun sweep(designDirectory: Path, header: StoredDesignHeaderV3) {
    val referenced = buildSet {
      add(HEADER_FILE)
      add(QUARANTINE_FILE)
      add(header.documentFile)
      add(header.positionsFile)
      header.journalFile?.let { add(it) }
      addAll(header.revisionFiles.values)
    }
    walkFiles(designDirectory).forEach { path ->
      val name = designDirectory.relativize(path).toString().replace('\\', '/')
      if (name !in referenced) runCatching { Files.deleteIfExists(path) }
    }
  }

  // ---------------------------------------------------------------- quarantine

  /**
   * The design id a directory claims, when the directory is not the one that id addresses.
   *
   * The slug is the address, not a label: [commit] and [remove] both resolve
   * `designs/<slug(designId)>` from the id rather than from the directory a design was read out of.
   * So a design restored or copied under another basename — an in-place backup, a directory put
   * back under a name someone could read — is not a second copy of that design to this store, it is
   * a header claiming an id that already has a home. Loading it would hand the service that
   * header's parts, and the next commit would then write a header into the canonical directory
   * naming part files that only exist beside the copy: an edit accepted, and the design quarantined
   * at the next start. A delete has the same shape from the other end — the canonical directory
   * goes, the copy stays, and the design comes back on the next open.
   *
   * So it is reported instead, keyed by the directory rather than by the id. That keeps it distinct
   * from whichever design legitimately holds that id, and still lets an operator retire it:
   * [remove] resolves a quarantined key through the directory recorded here.
   */
  private fun misplacedDesignId(designDirectory: Path, slug: String): String? {
    val designId = runCatching { readHeader(designDirectory).designId }.getOrNull() ?: return null
    return designId.takeIf { slug(it) != slug }
  }

  private fun readQuarantine(designDirectory: Path): Pair<String, String>? {
    val path = designDirectory.resolve(QUARANTINE_FILE)
    if (!Files.exists(path)) return null
    val record = runCatching {
      json.decodeFromString(
        StoredQuarantineV3.serializer(),
        readBounded(path, "quarantine record").decodeToString(),
      )
    }
      .getOrNull()
    val designId =
      record?.designId
        ?: runCatching { readHeader(designDirectory).designId }.getOrNull()
        ?: quarantineDesignId(designDirectory, designDirectory.fileName.toString())
    return designId to (record?.reason ?: "quarantined")
  }

  private fun quarantineDesignId(designDirectory: Path, slug: String): String =
    runCatching { readHeader(designDirectory).designId }.getOrNull()
      ?: runCatching {
        json
          .parseToJsonElement(
            readBounded(designDirectory.resolve(HEADER_FILE), "header").decodeToString()
          )
          .jsonObject[PAYLOAD_FIELD]
          ?.jsonObject
          ?.get("designId")
          ?.let { (it as? JsonPrimitive)?.content }
      }
        .getOrNull()
      ?: slug

  private fun writeQuarantine(designDirectory: Path, designId: String, reason: String) {
    runCatching {
      val bytes =
        json
          .encodeToString(
            StoredQuarantineV3.serializer(),
            StoredQuarantineV3(reason, System.currentTimeMillis(), designId),
          )
          .encodeToByteArray()
      val temporary = writeTemporary(designDirectory, QUARANTINE_FILE, bytes)
      replaceAtomically(temporary, designDirectory.resolve(QUARANTINE_FILE))
    }
  }

  // ---------------------------------------------------------------- migration

  /**
   * Writes a v2 single-file state out as this tree, once.
   *
   * `store.json` present means the store is already v3 and the old file is ignored. The old file is
   * renamed rather than deleted: it is the rollback, and `--ui-builder-state-dir` pointing at a
   * copy of it is the recovery path an operator has.
   */
  private fun migrateLegacyStateIfPresent() {
    if (Files.exists(markerFile)) return
    val legacyFile = directory.resolve(FileUiBuilderStateStorage.STATE_FILE)
    if (!Files.exists(legacyFile)) return
    // Bounded before it is allocated, as the storage it replaces bounded it. A runaway legacy file
    // read whole is an `OutOfMemoryError` rather than an exception, and `serve` disables the lane
    // on
    // an exception — so an unbounded read here would take the host down instead of the lane, which
    // is the failure #568 exists about.
    val legacyBytes = Files.size(legacyFile)
    if (legacyBytes > limits.maximumMigrationBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder state at $legacyFile is $legacyBytes bytes; the migration limit is " +
          "${limits.maximumMigrationBytes}"
      )
    }
    val decoded = LegacyUiBuilderState.decode(Files.readAllBytes(legacyFile))
    decoded.designs.forEach { (designId, design) -> commitUnlocked(designId, null, design) }
    // The rename goes BEFORE the marker, because the marker is the commit point: it is what makes
    // the next start skip the migration. A rename that failed after it — a locked file, a read-only
    // mount, a `.migrated` name already taken by something else — would leave this start reporting
    // a failed migration and disabling the lane, while the next start reads the marker, skips the
    // migration and serves the very same v3 designs. It would also make the disabled-lane warning
    // untrue, offering a `.migrated` rollback that the failure is precisely the absence of. Ahead
    // of the marker it is a step the migration can be retried from: the designs it rewrites are
    // named by the digests of their contents, so the retry writes the same tree.
    try {
      Files.move(
        legacyFile,
        directory.resolve(FileUiBuilderStateStorage.STATE_FILE + MIGRATED_SUFFIX),
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (failure: IOException) {
      throw UiBuilderPersistenceException(
        "cannot set aside the migrated UI-builder state at $legacyFile",
        failure,
      )
    }
    // Forcing this directory is what makes both the rename and the marker durable, in that order.
    writeMarker(StoreMarkerV3(STORE_FORMAT, migratedFrom = decoded.format.wire))
  }

  private fun commitUnlocked(
    designId: String,
    previous: PersistedDesignV1?,
    next: PersistedDesignV1,
  ) {
    // The migration runs inside `load`'s lock, and `commit` takes the same non-reentrant file lock
    // —
    // which is why this path exists at all, and why the durability `commit` does has to be repeated
    // here rather than assumed: the marker is written last and makes the legacy file irrelevant, so
    // a slug directory whose name never reached the disk would be a design silently dropped by the
    // one operation that was supposed to preserve every one of them.
    val designDirectory = designsDirectory.resolve(slug(designId))
    val created = !Files.isDirectory(designDirectory)
    Files.createDirectories(designDirectory)
    if (created) {
      forceDirectory(designsDirectory)
      forceDirectory(directory)
    }
    val documentFile =
      writePart(designDirectory, DOCUMENT_PART, json.encodeToJsonElement(next.document))
    val positionsFile =
      writePart(
        designDirectory,
        POSITIONS_PART,
        json.encodeToJsonElement(StoredPositionsV3(next.positions)),
      )
    val revisionFiles = writeRevisions(designDirectory, previous, next, emptyMap()) {}
    val journal = compactJournal(designDirectory, next, generation = 1)
    val header =
      StoredDesignHeaderV3(
        designId = designId,
        title = next.document.title,
        revision = next.document.revision,
        lastSequence = next.lastSequence,
        access = next.access,
        catalogPin = next.document.catalogPin,
        createdAtEpochMillis = next.createdAtEpochMillis,
        updatedAtEpochMillis = next.updatedAtEpochMillis,
        documentFile = documentFile,
        positionsFile = positionsFile,
        revisionFiles = revisionFiles,
        journalFile = journal.file,
        journalBytes = journal.bytes,
        journalCompactedBytes = journal.compactedBytes,
      )
    // The migration must not write a file its own reader will refuse. Every read here is bounded by
    // the per-design budget, so a legacy design with one file over that budget — a document, or the
    // header itself, which carries an access list with no bound of its own — would be written out,
    // committed under the marker, and then quarantined by the very next load, with the
    // pre-migration file already renamed. Refused here it is one design named with its size and its
    // limit, and the copy that still has it is the `.migrated` file this migration keeps.
    //
    // File by file, not the design's total: a design merely larger than the budget loads, serves
    // and exports, and refuses to grow, which is what `commit` does with any design that reaches
    // the budget. Quarantining that one would be a harsher rule invented at the migration boundary.
    val encodedHeader = encodeHeader(header)
    val oversize =
      referencedParts(header)
        .map { name ->
          name to runCatching { Files.size(designDirectory.resolve(name)) }.getOrDefault(0L)
        }
        .plus(HEADER_FILE to encodedHeader.size.toLong())
        .firstOrNull { (_, size) -> size > limits.maximumDesignBytes }
    if (oversize != null) {
      val (name, size) = oversize
      runCatching { deleteRecursively(designDirectory) }
      Files.createDirectories(designDirectory)
      writeQuarantine(
        designDirectory,
        designId,
        "did not migrate: $name is $size bytes and the per-design limit is " +
          "${limits.maximumDesignBytes}; the state it was migrated from is kept as " +
          "${FileUiBuilderStateStorage.STATE_FILE}$MIGRATED_SUFFIX",
      )
      // The directory was just deleted and made again, after the fsync above and before the marker
      // that ends the migration. Forcing a directory does not make its own name durable in its
      // parent, so without this a power loss can leave a store whose marker says the migration
      // finished and whose `designs/` has no entry for this design at all — not even the quarantine
      // saying why it did not come across, while the `.migrated` file it is still in is ignored.
      forceDirectory(designDirectory)
      forceDirectory(designsDirectory)
      return
    }
    writeHeader(designDirectory, encodedHeader)
  }

  // ---------------------------------------------------------------- files

  /**
   * The whole of one part, refused before it is allocated when it is larger than a design may be.
   *
   * A part that has grown past the per-design budget cannot be a part this store wrote, and reading
   * it whole to find that out is how one corrupt file becomes a dead host rather than one
   * quarantined design.
   */
  private fun readBounded(path: Path, description: String): ByteArray {
    val size = Files.size(path)
    if (size > limits.maximumDesignBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder $description at $path is $size bytes; the per-design limit is " +
          "${limits.maximumDesignBytes}"
      )
    }
    return Files.readAllBytes(path)
  }

  /** The first [length] bytes of [path], which is as much of a journal as a header commits to. */
  private fun readPrefix(path: Path, length: Long): ByteArray {
    val buffer = ByteBuffer.allocate(length.toInt())
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) break
      }
    }
    if (buffer.hasRemaining()) {
      throw UiBuilderPersistenceException("UI-builder journal at $path ended before $length bytes")
    }
    return buffer.array()
  }

  private fun writeTemporary(directory: Path, name: String, value: ByteArray): Path {
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".$name.", ".tmp")
    try {
      FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(value)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
      }
      return temporary
    } catch (failure: Throwable) {
      Files.deleteIfExists(temporary)
      throw failure
    }
  }

  /**
   * Renames [source] onto [target] and forces the directory the name landed in.
   *
   * The directory entry matters as much as the bytes: a retained revision lands in `revisions/`,
   * and only the design directory was forced before the header was made durable — so a crash could
   * leave a header naming a revision whose directory entry never reached the disk, quarantining the
   * whole design over a file that was written.
   */
  private fun replaceAtomically(source: Path, target: Path) {
    Files.createDirectories(target.parent)
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    forceDirectory(target.parent)
  }

  private fun walkFiles(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    val paths = mutableListOf<Path>()
    Files.walk(directory).use { stream ->
      stream.forEach { if (Files.isRegularFile(it)) paths.add(it) }
    }
    return paths
  }

  private fun directoryBytes(directory: Path): Long =
    walkFiles(directory).sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }

  /**
   * What the design would cost once [header] is current: the parts it names, and nothing else.
   *
   * Not the directory's size, which at this point still holds the generation about to be swept and
   * would refuse a commit for bytes that are on their way out.
   */
  private fun referencedBytes(designDirectory: Path, header: StoredDesignHeaderV3): Long {
    val parts = referencedParts(header)
    // Deliberately not best-effort, unlike the gauge above. A part this header names that cannot be
    // measured is a part that is not there, and a commit reuses the name of every part it did not
    // rewrite: counting the failure as zero bytes would let the header land naming a file that has
    // gone, which is a design accepted here and quarantined at the next open. `Files.size` throws
    // an `IOException`, which `commit` already answers by discarding what it wrote and refusing —
    // leaving the previous generation whole, which is the generation that still has its parts.
    return parts.sumOf { name -> Files.size(designDirectory.resolve(name)) }
  }

  private fun referencedParts(header: StoredDesignHeaderV3): List<String> = buildList {
    add(header.documentFile)
    add(header.positionsFile)
    header.journalFile?.let { add(it) }
    addAll(header.revisionFiles.values)
  }

  private fun deleteRecursively(directory: Path) {
    if (!Files.exists(directory)) return
    Files.walk(directory).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
  }

  private fun forceDirectory(path: Path) {
    try {
      FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
      // Some file systems cannot open a directory. The files themselves were already forced.
    }
  }

  private fun <T> locked(block: () -> T): T {
    Files.createDirectories(directory)
    return try {
      FileChannel.open(
          lockFile,
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE,
        )
        .use { channel -> channel.lock().use { block() } }
    } catch (failure: UiBuilderPersistenceException) {
      throw failure
    } catch (failure: IOException) {
      throw UiBuilderPersistenceException("cannot lock UI-builder state at $lockFile", failure)
    }
  }

  internal companion object {
    const val STORE_FILE: String = "store.json"
    const val STORE_FORMAT: String = "ui-builder-store-v3"
    const val DESIGNS_DIRECTORY: String = "designs"
    const val HEADER_FILE: String = "design.json"
    const val QUARANTINE_FILE: String = "quarantine.json"
    const val REVISIONS_DIRECTORY: String = "revisions"
    const val MIGRATED_SUFFIX: String = ".migrated"
    /** Where `remove` puts a design it has deleted, until the unlink finishes. */
    const val DELETED_DIRECTORY: String = ".deleted"
    private const val LOCK_FILE = ".ui-builder-service.lock"
    private const val DOCUMENT_PART = "document"
    private const val POSITIONS_PART = "positions"
    private const val PART_SUFFIX = ".json"
    private const val JOURNAL_PREFIX = "journal-"
    private const val JOURNAL_SUFFIX = ".jsonl"
    private const val PAYLOAD_FIELD = "payload"
    private const val CHECKSUM_FIELD = "checksumSha256"
    private const val DIGEST_NAME_LENGTH = 16
    private const val MAXIMUM_APPENDED_TAIL = 4
    private const val MAXIMUM_MARKER_BYTES = 64L * 1_024

    private val json = Json { encodeDefaults = true }
    private val journalJson = Json { encodeDefaults = false }

    /** `<part>-<digest>` for a design directory: the id is not promised to be filename-safe. */
    fun slug(designId: String): String = sha256(designId.encodeToByteArray()).take(32)

    fun journalGeneration(file: String): Int =
      file.removePrefix(JOURNAL_PREFIX).removeSuffix(JOURNAL_SUFFIX).toIntOrNull() ?: 1
  }
}

@Serializable private data class StoredPart(val checksumSha256: String, val payload: JsonElement)

private val StoredPartSerializer = StoredPart.serializer()
