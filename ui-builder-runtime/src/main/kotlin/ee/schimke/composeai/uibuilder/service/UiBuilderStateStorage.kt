package ee.schimke.composeai.uibuilder.service

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

public class UiBuilderPersistenceException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

public data class UiBuilderPersistenceMigrationResult(
  val migrated: Boolean,
  val fromFormat: String,
  val toFormat: String,
  val persistedBytes: Int,
)

/**
 * How much of a bounded storage is spent, so an operator learns before a save is refused. `bytes`
 * is what is stored; `maximumBytes` the ceiling.
 */
public data class UiBuilderStorageUsage(val bytes: Long, val maximumBytes: Long) {
  init {
    require(maximumBytes > 0) { "maximumBytes must be positive" }
  }

  /** 0.0 when nothing is stored, 1.0 at the ceiling. */
  public val usedFraction: Double
    get() = bytes.toDouble() / maximumBytes.toDouble()
}

/**
 * Atomic opaque-state storage. Implementations must replace the complete value or write nothing.
 */
public interface UiBuilderStateStorage {
  public fun load(): ByteArray?

  public fun replace(value: ByteArray)

  /**
   * What this storage holds against its ceiling, or null when it has neither. Defaulted so external
   * implementations and in-memory storages need not change.
   */
  public fun usage(): UiBuilderStorageUsage? = null
}

/** Storage that can retain and explicitly restore the exact pre-migration generation. */
public interface RecoverableUiBuilderMigrationStorage : UiBuilderStateStorage {
  /**
   * Atomically installs [value] while retaining the exact prior primary as the migration backup.
   */
  public fun replaceForMigration(value: ByteArray)

  /** Atomically restores that exact backup; false means no rollback generation is available. */
  public fun restoreMigrationBackup(): Boolean
}

/**
 * Single-process file-backed storage with a cross-process advisory lock and atomic replacement.
 *
 * Bounds bytes before reading or writing, forces the new file before rename, ignores orphaned temp
 * files, and preserves the prior state as [BACKUP_FILE] for explicit restore; corrupt primary state
 * is never hidden. No multi-replica compare-and-set: concurrent writers need a transactional
 * [UiBuilderStateStorage].
 */
public class FileUiBuilderStateStorage(
  root: Path,
  /**
   * The ceiling a write is refused at. Every edit rewrites the whole file, so this is headroom
   * only; the per-design store (`docs/design/UI_BUILDER_STATE_STORAGE.md`) is the real fix.
   */
  private val maximumBytes: Long = 128L * 1024L * 1024L,
) : RecoverableUiBuilderMigrationStorage {
  private val directory = root.toAbsolutePath().normalize()
  private val stateFile = directory.resolve(STATE_FILE)
  private val backupFile = directory.resolve(BACKUP_FILE)
  private val lockFile = directory.resolve(LOCK_FILE)

  init {
    require(maximumBytes > 0) { "maximumBytes must be positive" }
    Files.createDirectories(directory)
    require(Files.isDirectory(directory)) { "UI-builder state root is not a directory: $directory" }
  }

  override fun load(): ByteArray? = locked {
    if (!Files.exists(stateFile)) return@locked null
    val size =
      try {
        Files.size(stateFile)
      } catch (failure: IOException) {
        throw UiBuilderPersistenceException("cannot stat UI-builder state at $stateFile", failure)
      }
    if (size > maximumBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder state at $stateFile is $size bytes; limit is $maximumBytes"
      )
    }
    try {
      Files.readAllBytes(stateFile)
    } catch (failure: IOException) {
      throw UiBuilderPersistenceException("cannot read UI-builder state at $stateFile", failure)
    }
  }

  override fun replace(value: ByteArray) {
    if (value.size.toLong() > maximumBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder state is ${value.size} bytes; limit is $maximumBytes"
      )
    }
    locked {
      var temporary: Path? = null
      var backupTemporary: Path? = null
      try {
        temporary = writeTemporary(STATE_FILE, value)
        if (Files.exists(stateFile)) {
          val previous = readBounded(stateFile, "state")
          backupTemporary = writeTemporary(BACKUP_FILE, previous)
          replaceAtomically(backupTemporary, backupFile)
          backupTemporary = null
          forceDirectory()
        }
        replaceAtomically(temporary, stateFile)
        temporary = null
        forceDirectory()
      } catch (failure: IOException) {
        throw UiBuilderPersistenceException(
          "cannot replace UI-builder state at $stateFile",
          failure,
        )
      } finally {
        backupTemporary?.let { Files.deleteIfExists(it) }
        temporary?.let { Files.deleteIfExists(it) }
      }
    }
  }

  /**
   * Replaces the primary state with its one-generation backup, leaving the backup intact. Explicit:
   * callers should diagnose a corrupt primary first; the service still validates on startup.
   * Returns false when no prior generation exists.
   */
  public fun restoreBackup(): Boolean = locked {
    if (!Files.exists(backupFile)) return@locked false
    var temporary: Path? = null
    try {
      val backup = readBounded(backupFile, "backup")
      temporary = writeTemporary(STATE_FILE, backup)
      replaceAtomically(temporary, stateFile)
      temporary = null
      forceDirectory()
    } catch (failure: IOException) {
      throw UiBuilderPersistenceException(
        "cannot restore UI-builder state backup at $backupFile",
        failure,
      )
    } finally {
      temporary?.let { Files.deleteIfExists(it) }
    }
    true
  }

  /**
   * The primary file's size against the ceiling. Not locked and tolerant of a missing file: a
   * status gauge must never block a save or fail.
   */
  override fun usage(): UiBuilderStorageUsage {
    val bytes =
      try {
        if (Files.exists(stateFile)) Files.size(stateFile) else 0L
      } catch (_: IOException) {
        0L
      }
    return UiBuilderStorageUsage(bytes, maximumBytes)
  }

  override fun replaceForMigration(value: ByteArray): Unit = replace(value)

  override fun restoreMigrationBackup(): Boolean = restoreBackup()

  private fun readBounded(path: Path, description: String): ByteArray {
    val size = Files.size(path)
    if (size > maximumBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder $description at $path is $size bytes; limit is $maximumBytes"
      )
    }
    return Files.readAllBytes(path)
  }

  private fun writeTemporary(name: String, value: ByteArray): Path {
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

  private fun replaceAtomically(source: Path, target: Path) {
    Files.move(
      source,
      target,
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING,
    )
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

  private fun forceDirectory() {
    try {
      FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
      // Some file systems cannot open a directory. The state file itself was already forced.
    }
  }

  public companion object {
    public const val STATE_FILE: String = "ui-builder-service-v1.json"
    public const val BACKUP_FILE: String = "ui-builder-service-v1.json.backup"
    private const val LOCK_FILE = ".ui-builder-service.lock"
  }
}
