package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.toUiBuilderDocument
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/**
 * Reads and writes checked-in design documents — the `.uid` files an agent edits and IntelliJ
 * opens.
 *
 * The same JSON shape the IntelliJ plugin writes: `DesignDocumentV1`, pretty-printed with two-space
 * indent and every default present, so a design saved from the desktop and one saved from the IDE
 * diff cleanly against each other.
 */
object DesignFiles {
  /** The document declarations the offline editor can load and write back. */
  val supportedSchemas: Set<String> =
    setOf("compose-ui-builder-document/v1", "compose-ui-builder-document/v1-candidate")

  val extensions: Set<String> = setOf("uid", "json")

  /** Decodes [path], refusing a document this editor cannot write back or has no catalog for. */
  fun read(path: Path): UiBuilderDocument {
    val document =
      json.decodeFromString(DesignDocumentV1.serializer(), Files.readString(path)).also {
        require(it.schema in supportedSchemas) {
          "${path.fileName} declares schema '${it.schema}', which this editor cannot write back"
        }
        OfflineCatalog.forSystem(it.catalogPin.systemId)
      }
    return document.toUiBuilderDocument()
  }

  /**
   * Writes [document] to [path] through a sibling temporary file, so a crash mid-write leaves the
   * previous version rather than half a document.
   */
  fun write(path: Path, document: DesignDocumentV1) {
    val absolute = path.toAbsolutePath()
    val temporary = Files.createTempFile(absolute.parent, ".${absolute.fileName}", ".tmp")
    try {
      Files.writeString(temporary, json.encodeToString(DesignDocumentV1.serializer(), document))
      Files.move(
        temporary,
        absolute,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private val json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
  }
}
