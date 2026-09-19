package ee.schimke.composeai.uibuilder.local

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Durable [LocalDesignStorage] for a desktop host.
 *
 * Keys are encoded as file names instead of being used as paths, so a protocol key can never escape
 * [root]. Writes replace a sibling temporary file atomically where the host file system supports
 * it.
 */
class FileLocalDesignStorage(private val root: Path) : LocalDesignStorage {
  init {
    try {
      Files.createDirectories(root)
    } catch (failure: Exception) {
      throw LocalDesignStorageException("the desktop cannot create $root", failure)
    }
  }

  override fun read(key: String): String? =
    try {
      val file = fileFor(key)
      if (Files.isRegularFile(file)) Files.readString(file) else null
    } catch (failure: Exception) {
      throw LocalDesignStorageException("the desktop cannot read $key", failure)
    }

  override fun write(key: String, value: String) {
    val destination = fileFor(key)
    try {
      val temporary = Files.createTempFile(root, ".ui-builder-", ".tmp")
      try {
        Files.writeString(temporary, value)
        try {
          Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
          Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
      } finally {
        Files.deleteIfExists(temporary)
      }
    } catch (failure: Exception) {
      throw LocalDesignStorageException("the desktop cannot store $key", failure)
    }
  }

  override fun remove(key: String) {
    try {
      Files.deleteIfExists(fileFor(key))
    } catch (failure: Exception) {
      throw LocalDesignStorageException("the desktop cannot remove $key", failure)
    }
  }

  override fun keys(): List<String> =
    try {
      Files.list(root).use { files ->
        files
          .filter(Files::isRegularFile)
          .map { file -> decode(file.fileName.toString()) }
          .filter { it != null }
          .map { requireNotNull(it) }
          .toList()
      }
    } catch (failure: Exception) {
      throw LocalDesignStorageException("the desktop cannot list $root", failure)
    }

  private fun fileFor(key: String): Path = root.resolve(encode(key))

  private fun encode(key: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(StandardCharsets.UTF_8))

  private fun decode(name: String): String? =
    try {
      String(Base64.getUrlDecoder().decode(name), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
      null
    }
}
