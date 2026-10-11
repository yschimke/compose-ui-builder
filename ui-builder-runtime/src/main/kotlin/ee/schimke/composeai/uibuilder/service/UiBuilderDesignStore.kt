@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CommittedOperationV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignSubmissionV1
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Everything a store holds, as the service wants it at construction. [quarantined] carries the
 * designs whose files could not be read, so an unreadable design costs only itself.
 */
internal data class StoredDesigns(
  val designs: Map<String, PersistedDesignV1> = emptyMap(),
  val quarantined: Map<String, StoredQuarantineV3> = emptyMap(),
)

/**
 * Where the UI builder's designs are kept. Unlike [UiBuilderStateStorage]'s whole-state seam, this
 * names the design an edit touched and gets the before/after values so it writes only what differs.
 * See `docs/design/UI_BUILDER_STATE_STORAGE.md`.
 */
internal interface UiBuilderDesignStore {
  /** Every stored design. Called once, at construction. */
  fun load(): StoredDesigns

  /**
   * Persists [next], writing only the parts in which it differs from [previous] (null when the
   * store held nothing). Candidates are built with `copy()`, so an untouched part compares by
   * reference.
   */
  fun commit(designId: String, previous: PersistedDesignV1?, next: PersistedDesignV1)

  /**
   * Persists several designs together, each as [commit] would; [changed] maps an id to (previous,
   * next). The default is one [commit] per design; [LegacyStateStorageDesignStore] overrides it
   * because each of its commits re-encodes the entire state.
   */
  fun commitAll(changed: Map<String, Pair<PersistedDesignV1?, PersistedDesignV1>>) {
    changed.forEach { (designId, values) -> commit(designId, values.first, values.second) }
  }

  fun remove(designId: String)

  /**
   * The key of the quarantine occupying the place [designId] would be written to, or null.
   *
   * Not the same as "is this id quarantined": a quarantine may be reported under its directory or
   * another name yet still hold this id's place, and creating into it would entangle two designs.
   */
  fun quarantineHolding(designId: String): String? = null

  /** What is held against the ceiling that would refuse a write, or null when there is neither. */
  fun usage(): UiBuilderStorageUsage? = null
}

/**
 * The v3 per-design store on disk, as a host constructs it. Opaque because the persistence model is
 * not published.
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
     * `store.json` present means v3; otherwise `ui-builder-service-v1.json` is migrated per design
     * and renamed to `.migrated` — never deleted, because it is the rollback.
     */
    @JvmStatic
    public fun open(
      root: Path,
      limits: UiBuilderStoreLimits = UiBuilderStoreLimits(),
    ): UiBuilderDesignStateStore = UiBuilderDesignStateStore(FileUiBuilderDesignStore(root, limits))
  }
}

/**
 * The byte budgets the per-design store applies: [maximumDesignBytes] refuses a write to a design
 * that is too large; [maximumBytes] is the gauge an operator watches.
 */
public data class UiBuilderStoreLimits(
  /** Reported by [UiBuilderDesignStateStore.usage]; nothing is refused for reaching it. */
  val maximumBytes: Long = 1_024L * 1_024 * 1_024,
  /** A single design's own ceiling: its parts, its retained revisions and its journal. */
  val maximumDesignBytes: Long = 64L * 1_024 * 1_024,
  /** The journal is rewritten once it is this large and this much larger than what it holds. */
  val journalCompactionBytes: Long = 256L * 1_024,
  /**
   * The largest v2 state file the one-shot migration will read into memory, so a huge file cannot
   * exhaust the heap.
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
   * How much of [journalFile] this header commits to. The append precedes the header, so replay
   * reads exactly this many bytes and the uncommitted tail is truncated by the next commit.
   */
  val journalBytes: Long = 0,
  /**
   * What writing this design's collections out whole last cost; once the journal grows several
   * times past this, it is cheaper to compact than replay.
   */
  val journalCompactedBytes: Long = 0,
  /**
   * The branch record when this design is a branch; see `UI_BUILDER_BRANCHES.md`. Small and
   * replaced whole, so it lives in the header. Never written for ordinary designs.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val branch: DesignBranchRecordV1? = null,
  /**
   * [PersistedDesignV1.createdByDelegateActorId]; in the header so a quarantined design keeps it.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER) val createdByDelegateActorId: String? = null,
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
 * One commit's changes to the collections that grow by an entry per edit (`history`, `audit`,
 * `operationOutcomes`, `acceptedOperations`, `tombstones`), so an edit does not rewrite them whole.
 * Every field is null when that collection did not change.
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
  /**
   * Conflict touches, journalled like `history`. Absent in older entries, which replay to an empty
   * list.
   */
  val conflictTouchesAppend: List<ConflictTouchRecordV1>? = null,
  val conflictTouchesKeep: Int? = null,
  val conflictTouchesSet: List<ConflictTouchRecordV1>? = null,
  /**
   * A branch's command log — only ever appended to, or written whole by a compaction. Absent for
   * every design that is not a branch.
   */
  val branchLogAppend: List<DesignSubmissionV1>? = null,
  val branchLogSet: List<DesignSubmissionV1>? = null,
)

/**
 * One journal line: a record and a checksum of it as stored. `journalBytes` says which records are
 * committed; the checksum catches bit flips that still parse. Per record, so an append never
 * re-hashes the whole journal.
 */
@Serializable internal data class JournalLineV3(val checksumSha256: String, val entry: JsonElement)

/**
 * Why one design could not be read, written beside it rather than thrown.
 *
 * [designId] is recorded so the id an operator retires it by is stable across restarts. [header] is
 * the header as it did parse (when a later part failed), carrying access, title and pin so the
 * owner can still see and retire it. Absent when the header itself was unreadable.
 */
@Serializable
internal data class StoredQuarantineV3(
  val reason: String,
  val recordedAtEpochMillis: Long,
  val designId: String? = null,
  val header: StoredDesignHeaderV3? = null,
)

@Serializable
internal data class StoreMarkerV3(val format: String, val migratedFrom: String? = null)

/**
 * One design's parts as they are on disk, so the next commit knows what it may reuse; also carries
 * the journal's live size.
 */
private data class DesignFiles(val header: StoredDesignHeaderV3, val bytes: Long)

/**
 * One directory per design, one file per part, and a header that names them.
 *
 * Parts are named by content digest, so a commit becomes visible only when the `design.json` rename
 * lands. A crash before leaves the previous generation complete; a crash after leaves unreferenced
 * files the next open unlinks. See `docs/design/UI_BUILDER_STATE_STORAGE.md`.
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
   * The designs that could not be read, by id and directory, so `remove` can still resolve and
   * retire them.
   */
  private val quarantinedSlugs = linkedMapOf<String, String>()
  private var storedBytes = 0L
  /** Whether [storedBytes] has been measured, by a load or by the first [usage] before one. */
  private var counted = false

  init {
    Files.createDirectories(directory)
    require(Files.isDirectory(directory)) { "UI-builder state root is not a directory: $directory" }
    // Opening migrates and declares the format here, so an unreadable store fails where `serve`
    // catches it: a warning and a disabled lane, never a half-open store.
    locked {
      migrateLegacyStateIfPresent()
      if (Files.exists(markerFile)) readMarker() else writeMarker(StoreMarkerV3(STORE_FORMAT))
    }
  }

  override fun load(): StoredDesigns = locked {
    val designs = linkedMapOf<String, PersistedDesignV1>()
    val quarantined = linkedMapOf<String, StoredQuarantineV3>()
    storedBytes = 0
    for (slug in designSlugs()) {
      val designDirectory = designsDirectory.resolve(slug)
      val misplaced = misplacedDesignId(designDirectory, slug)
      if (misplaced != null) {
        // Reported before the quarantine file is read: a copy carries the original's
        // `quarantine.json` naming an id this directory must not answer for. The parsed header lets
        // the owner retire it.
        val header = runCatching { readHeader(designDirectory) }.getOrNull()
        quarantined[slug] =
          StoredQuarantineV3(
            "design $misplaced is stored at $DESIGNS_DIRECTORY/$slug rather than at " +
              "$DESIGNS_DIRECTORY/${slug(misplaced)}; move it back under that name to serve it, or " +
              "retire it by the name it has",
            System.currentTimeMillis(),
            header?.designId,
            header,
          )
        quarantinedSlugs[slug] = slug
        storedBytes += runCatching { directoryBytes(designDirectory) }.getOrDefault(0L)
        continue
      }
      val existingQuarantine = readQuarantine(designDirectory)
      if (existingQuarantine != null) {
        quarantined[existingQuarantine.first] = existingQuarantine.second
        quarantinedSlugs[existingQuarantine.first] = slug
        // Counted even though undecodable, so the gauge does not call broken data free. Best-effort
        // so an unwalkable directory cannot take down the load.
        storedBytes += runCatching { directoryBytes(designDirectory) }.getOrDefault(0L)
        continue
      }
      try {
        val header = readHeader(designDirectory)
        val design = readDesign(designDirectory, header)
        designs[header.designId] = design
        // Swept before measuring, so leftovers from a crash or refused commit are not charged
        // forever. Both best-effort: this design is servable, and failures here cost tidiness and
        // gauge precision, never the design. The gauge falls back to referenced bytes.
        runCatching { sweep(designDirectory, header) }
        val bytes = runCatching {
          directoryBytes(designDirectory)
        }
          .recoverCatching { referencedBytes(designDirectory, header) }
          .getOrDefault(0L)
        files[header.designId] = DesignFiles(header, bytes)
        storedBytes += bytes
      } catch (failure: Exception) {
        val header = runCatching { readHeader(designDirectory) }.getOrNull()
        val designId = header?.designId ?: quarantineDesignId(designDirectory, slug)
        val reason = failure.message ?: failure::class.simpleName ?: "unreadable"
        val record = StoredQuarantineV3(reason, System.currentTimeMillis(), designId, header)
        writeQuarantine(designDirectory, designId, reason, header)
        quarantined[designId] = record
        quarantinedSlugs[designId] = slug
        storedBytes += runCatching { directoryBytes(designDirectory) }.getOrDefault(0L)
      }
    }
    // A quarantine keyed by its directory name could collide with a live design's id; then the
    // quarantine yields and is renamed `designs-<slug>` (still one path segment), so deletes cannot
    // hit the live design.
    for (key in quarantined.keys.toList()) {
      if (key !in designs) continue
      val slug = quarantinedSlugs.getValue(key)
      var renamed = "${DESIGNS_DIRECTORY}-${slug}"
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
        // Forcing a directory does not make its name durable in its parent, so a first commit
        // forces the chain above it.
        forceDirectory(designsDirectory)
        forceDirectory(directory)
      }
      if (!Files.exists(markerFile)) writeMarker(StoreMarkerV3(STORE_FORMAT))
      val current = files[designId]
      val known = if (current == null) null else previous
      val written = mutableListOf<Path>()
      // Undo steps for the journal that [written] cannot express: an unreferenced compaction, or
      // appended bytes past the committed length (that file cannot be deleted). Otherwise a refusal
      // leaves bytes the gauge does not count.
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
            branch = next.branch,
            createdByDelegateActorId = next.createdByDelegateActorId,
          )
        var header = headerFor(journal)
        // The budget is checked before the header lands, since the header makes the generation
        // current: a refusal after it would already be durable.
        var encodedHeader = encodeHeader(header)
        var bytes = referencedBytes(designDirectory, header) + encodedHeader.size
        if (bytes > limits.maximumDesignBytes && !compacted) {
          // A design can hit the budget before the compaction ratio triggers, so the budget asks
          // for a rewrite rather than refusing over bytes nothing needs.
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
        // Past this line the design is the new one, so nothing below may roll back; tidying is
        // best-effort and the next open finishes it.
        runCatching { Files.deleteIfExists(designDirectory.resolve(QUARANTINE_FILE)) }
        runCatching { sweep(designDirectory, header) }
        // Measured after the sweep, so a generation the sweep could not remove is still counted.
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
   * Undoes a commit that never became the design. For an existing design, its new parts are
   * unreferenced and go. For a first commit, the whole directory goes: a headerless directory would
   * be quarantined on next open as a phantom sharing the real design's directory.
   */
  private fun discardUncommitted(
    designDirectory: Path,
    current: DesignFiles?,
    written: List<Path>,
    journalUndo: List<() -> Unit> = emptyList(),
  ) {
    if (current == null) {
      // Renamed then unlinked, as `remove` does: the rename is atomic, whereas a half-done
      // recursive unlink would leave the phantom this exists to avoid.
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
   * Cuts an appended journal back to the committed length. Readers ignore the tail anyway; this is
   * for disk and the gauge.
   */
  private fun truncateJournal(path: Path, length: Long) {
    FileChannel.open(path, StandardOpenOption.WRITE).use {
      it.truncate(length)
      it.force(true)
    }
  }

  /**
   * Where a deleted design goes: a directory of the store's own, never a name beside the designs.
   * The store treats unfamiliar directory names as operator content, so tombstones are identified
   * by location rather than by a name that could match someone's backup.
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
        // The rename is the deletion: atomic, so the design is gone whatever happens next;
        // unlinking the tombstone is cleanup. Attempted rather than guarded by `Files.exists`,
        // which also answers false for an unreadable path; only "source not there" means already
        // gone (the parent is created first so that answer is about the design).
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
        // Only after the rename, so bookkeeping never runs ahead of the deletion.
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
    // A host may check capacity before any `load` (the store is published), so measure here once.
    // After a load, the load's number is used.
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
        // Everything under the store's own deleted directory is garbage by location. See
        // [tombstoneFor].
        if (Files.isDirectory(it) && name == DELETED_DIRECTORY) {
          // Retried here; until it succeeds its bytes are still charged.
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
      conflictTouches = journal.conflictTouches,
      tombstones = journal.tombstones,
      positions = positions.positions,
      positionSnapshots =
        revisions.mapNotNull { retained ->
          retained.positions?.let { PositionStateV1(retained.revision, it) }
        },
      createdAtEpochMillis = header.createdAtEpochMillis,
      updatedAtEpochMillis = header.updatedAtEpochMillis,
      audit = journal.audit,
      branch = header.branch,
      branchLog = journal.branchLog,
      createdByDelegateActorId = header.createdByDelegateActorId,
    )
  }

  private data class JournalState(
    val history: List<CommittedOperationV1> = emptyList(),
    val audit: List<AuditRecordV1> = emptyList(),
    val conflictTouches: List<ConflictTouchRecordV1> = emptyList(),
    val outcomes: Map<String, OperationOutcomeRecordV1> = emptyMap(),
    val accepted: Map<String, AcceptedOperationRecordV1> = emptyMap(),
    val tombstones: Map<String, NodeTreeSnapshotV1> = emptyMap(),
    val branchLog: List<DesignSubmissionV1> = emptyList(),
  )

  private fun replayJournal(
    designDirectory: Path,
    header: StoredDesignHeaderV3,
  ): JournalState {
    val file = header.journalFile ?: return JournalState()
    var conflictTouches: List<ConflictTouchRecordV1> = emptyList()
    val path = designDirectory.resolve(file)
    if (!Files.exists(path)) {
      throw UiBuilderPersistenceException("UI-builder journal $file is missing")
    }
    // Read only the committed prefix, and only if it is within budget: reading a runaway journal
    // whole risks an `OutOfMemoryError`, which escapes both the quarantine and the lane guard.
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
    var branchLog = emptyList<DesignSubmissionV1>()
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
        entry.conflictTouchesSet?.let { conflictTouches = it }
        entry.conflictTouchesAppend?.let { appended ->
          conflictTouches =
            (conflictTouches + appended).let { it.takeLast(entry.conflictTouchesKeep ?: it.size) }
        }
        entry.branchLogSet?.let { branchLog = it }
        entry.branchLogAppend?.let { branchLog = branchLog + it }
      }
    return JournalState(
      history = history,
      audit = audit,
      conflictTouches = conflictTouches,
      outcomes = outcomes,
      accepted = accepted,
      tombstones = tombstones,
      branchLog = branchLog,
    )
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
    // The checksum covers the payload as stored, never a re-encode, so defaulted model fields do
    // not invalidate stored files (see `PersistentUiBuilderService.decode`).
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
   * Appended lists record the tail and the length to keep, so front trims replay exactly; replaced
   * lists (an asset write clears `history`) record themselves whole.
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
    val touchesTail =
      if (previous != null && previous.conflictTouches == next.conflictTouches) null
      else appendedTail(previous?.conflictTouches.orEmpty(), next.conflictTouches)
    val historyChanged = previous == null || previous.history != next.history
    val auditChanged = previous == null || previous.audit != next.audit
    val touchesChanged = previous == null || previous.conflictTouches != next.conflictTouches
    // A branch log only grows, so a prefix match is the whole test; anything else is written whole.
    val branchLogChanged = (previous?.branchLog ?: emptyList()) != next.branchLog
    val branchLogAppended =
      previous != null &&
        branchLogChanged &&
        next.branchLog.size > previous.branchLog.size &&
        next.branchLog.subList(0, previous.branchLog.size) == previous.branchLog
    if (
      !historyChanged &&
        !auditChanged &&
        !touchesChanged &&
        !branchLogChanged &&
        outcomes == null &&
        accepted == null &&
        tombstones == null
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
      conflictTouchesAppend = if (touchesChanged) touchesTail else null,
      conflictTouchesKeep =
        if (touchesChanged && touchesTail != null) next.conflictTouches.size else null,
      conflictTouchesSet =
        if (touchesChanged && touchesTail == null) next.conflictTouches else null,
      branchLogAppend =
        if (branchLogAppended) next.branchLog.drop(previous!!.branchLog.size) else null,
      branchLogSet = if (branchLogChanged && !branchLogAppended) next.branchLog else null,
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
        conflictTouchesSet = next.conflictTouches,
        branchLogSet = next.branchLog.takeIf { it.isNotEmpty() },
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
    // Written even when a file of that name exists: re-committing is how a part corrupted in place
    // is repaired.
    Files.createDirectories(path.parent)
    val bytes =
      json.encodeToString(StoredPartSerializer, StoredPart(checksum, payload)).encodeToByteArray()
    val temporary = writeTemporary(path.parent, path.fileName.toString(), bytes)
    replaceAtomically(temporary, path)
    return name
  }

  /**
   * The header as it will be stored, so its size counts toward the budget (an access list is
   * unbounded) and a commit cannot land a header the next open refuses.
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
    // Bounded before allocation: the marker is two fields, so a large one is corruption, and an
    // `OutOfMemoryError` would escape the lane guard.
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
   * Commits and removes resolve `designs/<slug(designId)>` from the id, so a copy under another
   * basename would make later commits name parts that only exist beside the copy. It is reported
   * instead, keyed by directory, so it stays distinct and [remove] can still retire it.
   */
  private fun misplacedDesignId(designDirectory: Path, slug: String): String? {
    val designId = runCatching { readHeader(designDirectory).designId }.getOrNull() ?: return null
    return designId.takeIf { slug(it) != slug }
  }

  /**
   * The quarantine occupying [designDirectory], if any: the id it is reported under and the record.
   * Older records are enriched from the header here when it parses.
   */
  private fun readQuarantine(designDirectory: Path): Pair<String, StoredQuarantineV3>? {
    val path = designDirectory.resolve(QUARANTINE_FILE)
    if (!Files.exists(path)) return null
    val record = runCatching {
      json.decodeFromString(
        StoredQuarantineV3.serializer(),
        readBounded(path, "quarantine record").decodeToString(),
      )
    }
      .getOrNull()
    val header = record?.header ?: runCatching { readHeader(designDirectory) }.getOrNull()
    val designId =
      record?.designId
        ?: header?.designId
        ?: quarantineDesignId(designDirectory, designDirectory.fileName.toString())
    val enriched =
      when {
        record == null -> StoredQuarantineV3("quarantined", 0L, designId, header)
        record.designId == null || record.header == null ->
          record.copy(designId = designId, header = header)
        else -> record
      }
    return designId to enriched
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

  private fun writeQuarantine(
    designDirectory: Path,
    designId: String,
    reason: String,
    header: StoredDesignHeaderV3?,
  ) {
    runCatching {
      // The header can be large (unbounded access list); if carrying it would push the record past
      // the read bound, it is left out so the id stays stable across restarts.
      val record = StoredQuarantineV3(reason, System.currentTimeMillis(), designId, header)
      val encoded = json.encodeToString(StoredQuarantineV3.serializer(), record)
      val bytes =
        if (encoded.encodeToByteArray().size > limits.maximumDesignBytes)
          json
            .encodeToString(
              StoredQuarantineV3.serializer(),
              record.copy(header = null),
            )
            .encodeToByteArray()
        else encoded.encodeToByteArray()
      val temporary = writeTemporary(designDirectory, QUARANTINE_FILE, bytes)
      replaceAtomically(temporary, designDirectory.resolve(QUARANTINE_FILE))
    }
  }

  // ---------------------------------------------------------------- migration

  /**
   * Writes a v2 single-file state out as this tree, once. The old file is renamed rather than
   * deleted: it is the rollback.
   */
  private fun migrateLegacyStateIfPresent() {
    if (Files.exists(markerFile)) return
    val legacyFile = directory.resolve(FileUiBuilderStateStorage.STATE_FILE)
    if (!Files.exists(legacyFile)) return
    // Bounded before allocation: an `OutOfMemoryError` would take the host down instead of the
    // lane.
    val legacyBytes = Files.size(legacyFile)
    if (legacyBytes > limits.maximumMigrationBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder state at $legacyFile is $legacyBytes bytes; the migration limit is " +
          "${limits.maximumMigrationBytes}"
      )
    }
    val decoded = LegacyUiBuilderState.decode(Files.readAllBytes(legacyFile))
    decoded.designs.forEach { (designId, design) -> commitUnlocked(designId, null, design) }
    // The rename precedes the marker (the commit point), so a failed rename leaves a retryable
    // migration rather than a disabled lane with a missing rollback. Retries write the same
    // content-addressed tree.
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
    // The migration runs inside `load`'s non-reentrant lock, so `commit`'s durability steps are
    // repeated here: a slug directory whose name never reached disk would silently drop a design.
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
        branch = next.branch,
        createdByDelegateActorId = next.createdByDelegateActorId,
      )
    // Refuse any file over the per-design read budget, or the next load would quarantine it after
    // the legacy file was already renamed. Per file, not total: an over-budget design still loads
    // and just cannot grow, as with `commit`.
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
        header,
      )
      // Force the recreated directory's name before the marker, so a power loss cannot leave a
      // finished migration missing this design.
      forceDirectory(designDirectory)
      forceDirectory(designsDirectory)
      return
    }
    writeHeader(designDirectory, encodedHeader)
  }

  // ---------------------------------------------------------------- files

  /**
   * The whole of one part, refused before allocation when larger than a design may be, so a corrupt
   * file costs one quarantined design rather than the host.
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
   * Renames [source] onto [target] and forces the target directory, so a header can never name a
   * file whose directory entry did not reach the disk.
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
   * What the design would cost once [header] is current: only the parts it names, not the
   * generation about to be swept.
   */
  private fun referencedBytes(designDirectory: Path, header: StoredDesignHeaderV3): Long {
    val parts = referencedParts(header)
    // Not best-effort: a named part that cannot be measured is missing, and the header must not
    // land naming it. The `IOException` makes `commit` discard and refuse, keeping the previous
    // generation.
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
