package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.production.ProductionUidFile
import ee.schimke.composeai.uibuilder.export.production.ProductionUidFiles
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A checked-in design file (`.uid`) as the editor's hosts read and write it.
 *
 * Every host that owns the file rather than the editor — the IntelliJ plugin's project sessions,
 * the VS Code host bridge (`HostBridgeApp.kt`) and the MCP App host (`McpAppHostApp.kt`) — must
 * write the same design as the same bytes, or opening a file in one and saving it in another is a
 * diff of whitespace. So the format lives here once: `DesignDocumentV1`, with the established
 * host-file JSON options, and a trailing newline.
 */
object UidDesignFiles {
  /** The declarations a checked-in design may carry, as the IntelliJ plugin accepts them. */
  val SCHEMAS: Set<String> =
    setOf("compose-ui-builder-document/v1", "compose-ui-builder-document/v1-candidate")

  /** Established ordinary-design encoding, shared with the native host file writer. */
  private val json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
  }

  /** The declaration of a file holding several top-level designs; see [UidDesignCollection]. */
  const val COLLECTION_SCHEMA: String = "compose-ui-builder-designs/v1"

  /**
   * Opens an ordinary design, an explicitly declared production file without dropping its API, or a
   * design collection, whose active design is the one opened.
   */
  fun open(text: String): OpenedUidDesign {
    val encoded = json.parseToJsonElement(text) as? JsonObject
    val schema = (encoded?.get("schema") as? JsonPrimitive)?.content
    if (schema == COLLECTION_SCHEMA) {
      val collection = decodeCollection(text)
      return OpenedUidDesign(collection.activeDocument, null, collection, text)
    }
    if (schema?.startsWith("compose-ui-builder-production/") == true) {
      val production = ProductionUidFiles.decode(text)
      val document =
        requireNotNull(production.design) {
          "model-only production files have no visual layout; edit their declarations in source"
        }
      require(document.schema in SCHEMAS) {
        "unsupported embedded design schema '${document.schema}'"
      }
      requireNotNull(production.entryPoint) { "a production layout requires an entry point" }
      val opened = OpenedUidDesign(document, production)
      opened.encode(document) // Refuse unsupported or dangling local declarations before editing.
      return opened
    }
    return OpenedUidDesign(decode(text), null)
  }

  /**
   * Reads a design file, refusing one that does not declare a design schema this editor reads. A
   * collection reads as its active design; writing that back needs [open], which keeps the others.
   */
  fun decode(text: String): UiBuilderDocument {
    if (isCollection(text)) return decodeCollection(text).activeDocument
    val wire = json.decodeFromString(DesignDocumentV1.serializer(), text)
    require(wire.schema in SCHEMAS) {
      "unsupported design schema '${wire.schema}'; this editor reads " + SCHEMAS.joinToString()
    }
    return wire.toUiBuilderDocument()
  }

  /** The file's bytes for [document]: what a host writes back. */
  fun encode(document: UiBuilderDocument): String =
    json.encodeToString(DesignDocumentV1.serializer(), document.toDesignDocumentV1()) + "\n"

  /** Whether [text] declares [COLLECTION_SCHEMA]. */
  fun isCollection(text: String): Boolean = runCatching {
    ((json.parseToJsonElement(text) as? JsonObject)?.get("schema") as? JsonPrimitive)
      ?.contentOrNull == COLLECTION_SCHEMA
  }
    .getOrDefault(false)

  /**
   * Reads a collection file. Any ordinary design file also reads, as a collection of one, so a host
   * can add a second design to a file that held only one.
   */
  fun decodeCollection(text: String): UidDesignCollection {
    val encoded =
      requireNotNull(json.parseToJsonElement(text) as? JsonObject) { "a design file is an object" }
    val schema = (encoded["schema"] as? JsonPrimitive)?.contentOrNull
    if (schema != COLLECTION_SCHEMA) {
      require(schema?.startsWith("compose-ui-builder-production/") != true) {
        "a production file declares one entry point and cannot hold further designs"
      }
      val single = decode(text)
      return UidDesignCollection(single.id, listOf(single))
    }
    val designs = collectionEntries(encoded).map { it.second }
    val active =
      requireNotNull(encoded["active"]?.jsonPrimitive?.contentOrNull) {
        "a design collection names its active design"
      }
    return UidDesignCollection(active, designs)
  }

  /**
   * The file's bytes for [collection]. Given the [original] file, every design that is unchanged
   * from it is written back as the JSON it was read from, so a field this editor does not model —
   * written by a newer one — survives in the designs nobody edited.
   */
  fun encodeCollection(collection: UidDesignCollection, original: String? = null): String {
    val unchanged =
      original
        ?.let { text -> runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() }
        ?.takeIf { (it["schema"] as? JsonPrimitive)?.contentOrNull == COLLECTION_SCHEMA }
        ?.let { encoded -> runCatching { collectionEntries(encoded) }.getOrNull() }
        .orEmpty()
        .associate { (raw, document) -> document to raw }
    val encoded =
      JsonObject(
        linkedMapOf(
          "schema" to JsonPrimitive(COLLECTION_SCHEMA),
          "active" to JsonPrimitive(collection.active),
          "designs" to
            JsonArray(
              collection.designs.map {
                unchanged[it]
                  ?: json.encodeToJsonElement(
                    DesignDocumentV1.serializer(),
                    it.toDesignDocumentV1(),
                  )
              }
            ),
        )
      )
    return json.encodeToString(JsonObject.serializer(), encoded) + "\n"
  }

  /** Each entry of a collection file as its JSON and the design it decodes to. */
  private fun collectionEntries(encoded: JsonObject): List<Pair<JsonElement, UiBuilderDocument>> =
    requireNotNull(encoded["designs"] as? JsonArray) { "a design collection lists its designs" }
      .map { element ->
        val wire = json.decodeFromJsonElement(DesignDocumentV1.serializer(), element)
        require(wire.schema in SCHEMAS) {
          "unsupported design schema '${wire.schema}' in a design collection; this editor reads " +
            SCHEMAS.joinToString()
        }
        element to wire.toUiBuilderDocument()
      }
}

/**
 * Several top-level designs kept in one `.uid` file, all for the same design system, one of which
 * is [active].
 *
 * The active design is the one every host opens, edits, previews and exports: to everything
 * downstream of the file it is an ordinary document with one root, so nothing learns a new shape.
 * The other designs ride along untouched until a host makes one of them active.
 *
 * ```json
 * { "schema": "compose-ui-builder-designs/v1", "active": "home", "designs": [ {…}, {…} ] }
 * ```
 *
 * Each entry is a complete `DesignDocumentV1`, so one can be cut out into a file of its own and
 * still open.
 */
data class UidDesignCollection(val active: String, val designs: List<UiBuilderDocument>) {
  init {
    require(designs.isNotEmpty()) { "a design collection holds at least one design" }
    val duplicate = designs.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicate.isEmpty()) {
      "design ids in a collection must be unique; repeated: ${duplicate.joinToString()}"
    }
    require(designs.any { it.id == active }) {
      "the active design '$active' is not in the collection (" +
        designs.joinToString { it.id } +
        ")"
    }
    val systems = designs.map { it.catalogSystemId }.distinct()
    require(systems.size == 1) {
      "every design in a collection uses the same design system; found ${systems.joinToString()}"
    }
  }

  /** The design hosts open and previews render. */
  val activeDocument: UiBuilderDocument
    get() = designs.first { it.id == active }

  /** The design system every design here is built from. */
  val catalogSystemId: String?
    get() = designs.first().catalogSystemId

  /** This collection with [id] active. */
  fun withActive(id: String): UidDesignCollection = copy(active = id)

  /** This collection with [document] appended and made active. */
  fun plus(document: UiBuilderDocument): UidDesignCollection =
    UidDesignCollection(document.id, designs + document)

  /** This collection without [id]; the first remaining design becomes active if [id] was. */
  fun minus(id: String): UidDesignCollection {
    val remaining = designs.filterNot { it.id == id }
    require(remaining.size < designs.size) { "no design '$id' in the collection" }
    require(remaining.isNotEmpty()) { "a design collection keeps at least one design" }
    return UidDesignCollection(if (active == id) remaining.first().id else active, remaining)
  }

  /** This collection with the design whose id is [document]'s replaced by it. */
  fun withDesign(document: UiBuilderDocument): UidDesignCollection {
    require(designs.any { it.id == document.id }) { "no design '${document.id}' in the collection" }
    return copy(designs = designs.map { if (it.id == document.id) document else it })
  }

  /** This collection with the active design replaced by [current], an edit of it. */
  fun replaceActive(current: UiBuilderDocument): UidDesignCollection =
    UidDesignCollection(current.id, designs.map { if (it.id == active) current else it })
}

private val UiBuilderDocument.catalogSystemId: String?
  get() = (catalogPin["systemId"] as? JsonPrimitive)?.contentOrNull

/** File context belongs to the host session, never the ordinary design-service document. */
class OpenedUidDesign
internal constructor(
  val document: UiBuilderDocument,
  val production: ProductionUidFile?,
  /** The file's other designs when it is a collection; [document] is its active one. */
  val collection: UidDesignCollection? = null,
  /** The bytes [collection] was read from, so its untouched designs are written back as read. */
  private val collectionText: String? = null,
) {
  /**
   * Saves visual edits while preserving the complete explicitly authored production API, or every
   * other design of a collection.
   */
  /**
   * The file's bytes with [id] made the active design, carrying [current] — the editor's unsaved
   * version of the design that was active — into the file as it goes.
   */
  fun switchTo(id: String, current: UiBuilderDocument = document): String {
    val designs = requireNotNull(collection) { "only a design collection has designs to switch" }
    return UidDesignFiles.encodeCollection(
      designs.replaceActive(current).withActive(id),
      collectionText,
    )
  }

  fun encode(current: UiBuilderDocument = document): String {
    collection?.let {
      return UidDesignFiles.encodeCollection(it.replaceActive(current), collectionText)
    }
    val source = production ?: return UidDesignFiles.encode(current)
    val entry = requireNotNull(source.entryPoint)
    require(current.schema == document.schema && current.id == document.id) {
      "a visual edit cannot change a production design's identity or schema"
    }
    require(current.roots == listOf(entry.root) && entry.root in current.nodes) {
      "the declared production root must remain; change the contract explicitly in source"
    }
    val referenced =
      entry.bindings.map { it.nodeId } +
        entry.eventBindings.map { it.nodeId } +
        entry.components.map { it.nodeId }
    require(referenced.all { it in current.nodes }) {
      "a production binding or component references a removed node; update its contract in source"
    }
    require(
      current.stateVariables.isEmpty() && current.nodes.values.all { it.eventBindings.isEmpty() }
    ) {
      "production callbacks and state must come from the declared API, not preview actions"
    }
    return ProductionUidFiles.encode(source.copy(design = current))
  }
}
