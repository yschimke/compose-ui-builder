package ee.schimke.composeai.uibuilder.host

import ee.schimke.composeai.uibuilder.UidDesignCollection
import ee.schimke.composeai.uibuilder.UidDesignFiles
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

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

  /** Decodes the shared on-disk protocol, ignoring only a future polymorphic home variant. */
  fun decode(text: String): DesignDocumentV1 {
    val encoded = json.parseToJsonElement(text)
    val objectValue = encoded as? JsonObject
    val schema = (objectValue?.get("schema") as? JsonPrimitive)?.contentOrNull
    if (
      schema?.startsWith("compose-ui-builder-production/") == true ||
        schema == UidDesignFiles.COLLECTION_SCHEMA
    ) {
      return UidDesignFiles.open(text).document.toDesignDocumentV1()
    }
    val home = objectValue?.get("home") as? JsonObject
    val homeKind = (home?.get("kind") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    // The protocol's sealed serializer rejects a future home discriminator before the builder's
    // tolerant home serializer gets a chance to run. Strip only that variant here; known or
    // malformed homes and the rest of the protocol document remain typed and validated.
    val compatible =
      if (objectValue != null && homeKind != null && homeKind !in KNOWN_HOME_KINDS) {
        JsonObject(objectValue - "home")
      } else {
        encoded
      }
    return json.decodeFromJsonElement(DesignDocumentV1.serializer(), compatible)
  }

  /** Decodes [path], refusing a document this editor cannot write back or has no catalog for. */
  fun read(path: Path): UiBuilderDocument {
    val document =
      decode(Files.readString(path)).also {
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
    writeText(path, json.encodeToString(DesignDocumentV1.serializer(), document))
  }

  /** The exact text [write] puts in a file for [document]. */
  fun encode(document: DesignDocumentV1): String =
    json.encodeToString(DesignDocumentV1.serializer(), document)

  /**
   * A host saving an opened file must supply its original bytes so production metadata survives.
   */
  fun encode(document: DesignDocumentV1, original: String?): String {
    val schema =
      original
        ?.let { (json.parseToJsonElement(it) as? JsonObject)?.get("schema") as? JsonPrimitive }
        ?.contentOrNull
    return if (
      schema?.startsWith("compose-ui-builder-production/") == true ||
        schema == UidDesignFiles.COLLECTION_SCHEMA
    ) {
      UidDesignFiles.open(requireNotNull(original)).encode(document.toUiBuilderDocument())
    } else encode(document)
  }

  /**
   * The designs [path] holds: a collection's, or an ordinary file's one design as a collection of
   * one.
   */
  fun readCollection(path: Path): UidDesignCollection =
    UidDesignFiles.decodeCollection(Files.readString(path)).also {
      it.catalogSystemId?.let(OfflineCatalog::forSystem)
    }

  /**
   * Rewrites [path] with [update] applied to its designs, turning a single design into a
   * collection.
   */
  fun updateCollection(path: Path, update: (UidDesignCollection) -> UidDesignCollection) {
    writeText(path, UidDesignFiles.encodeCollection(update(readCollection(path))))
  }

  fun write(path: Path, document: DesignDocumentV1, original: String?) {
    writeText(path, encode(document, original))
  }

  internal fun writeText(path: Path, text: String) {
    val absolute = path.toAbsolutePath()
    val temporary = Files.createTempFile(absolute.parent, ".${absolute.fileName}", ".tmp")
    try {
      Files.writeString(temporary, text)
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

  private val KNOWN_HOME_KINDS = setOf("server", "repo")
}

/**
 * Writes one design file back only while it still holds what this editor last read or wrote.
 *
 * A checked-in `.uid` is also edited by agents, other editors and `git checkout`. Writing the
 * in-memory document over such a change would erase it without a trace, so a write against a file
 * that has moved on is refused instead, and the editor says so; reopening the file picks the change
 * up.
 *
 * The comparison and the replacement are two steps, so a save landing in the instant between them
 * can still be replaced; closing that needs an OS file lock the other writers would have to share.
 * What this does close is everything outside that instant.
 */
class DesignFileGuard(private val path: Path) {
  private var known: String? = current()

  /** Writes [document] unless [path] changed since it was last read or written here. */
  @Synchronized
  fun write(document: DesignDocumentV1) {
    check(current() == known) {
      "${path.fileName} changed on disk since it was opened; reopen it to pick up that change"
    }
    val text = DesignFiles.encode(document, known)
    DesignFiles.writeText(path, text)
    // What was written, not what is there now: a save by another process landing just after this
    // one must read as a change at the next write, not be adopted as this editor's own.
    known = text
  }

  private fun current(): String? = if (Files.exists(path)) Files.readString(path) else null
}
