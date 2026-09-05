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
 * Atomic opaque-state storage. Implementations must replace the complete value or write nothing.
 */
public interface UiBuilderStateStorage {
  public fun load(): ByteArray?

  public fun replace(value: ByteArray)
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
 * The service payload carries its own version and checksum. This class bounds bytes before reading
 * or writing, forces the new file before rename, and ignores orphaned temporary files from a crash.
 * Before replacing an existing state it atomically preserves that state as [BACKUP_FILE]. Operators
 * may explicitly restore that one-generation backup after diagnosing a failed startup; corrupt
 * primary state is never silently hidden. It does not provide multi-replica compare-and-set
 * semantics; deployments requiring concurrent writers must supply a transactional
 * [UiBuilderStateStorage].
 */
public class FileUiBuilderStateStorage(
  root: Path,
  private val maximumBytes: Long = 32L * 1024L * 1024L,
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
   * Replaces the primary state with its one-generation backup, leaving the backup intact.
   *
   * This is deliberately explicit: callers should first retain and diagnose a corrupt primary. The
   * restored envelope is still validated by [PersistentUiBuilderService] on its next startup.
   * Returns false when no prior generation has been recorded.
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
