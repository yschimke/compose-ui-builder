package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.production.ProductionUidFile
import ee.schimke.composeai.uibuilder.export.production.ProductionUidFiles
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

  /**
   * Opens an ordinary design or an explicitly declared production file without dropping its API.
   */
  fun open(text: String): OpenedUidDesign {
    val encoded = json.parseToJsonElement(text) as? JsonObject
    val schema = (encoded?.get("schema") as? JsonPrimitive)?.content
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

  /** Reads a design file, refusing one that does not declare a design schema this editor reads. */
  fun decode(text: String): UiBuilderDocument {
    val wire = json.decodeFromString(DesignDocumentV1.serializer(), text)
    require(wire.schema in SCHEMAS) {
      "unsupported design schema '${wire.schema}'; this editor reads " + SCHEMAS.joinToString()
    }
    return wire.toUiBuilderDocument()
  }

  /** The file's bytes for [document]: what a host writes back. */
  fun encode(document: UiBuilderDocument): String =
    json.encodeToString(DesignDocumentV1.serializer(), document.toDesignDocumentV1()) + "\n"
}

/** File context belongs to the host session, never the ordinary design-service document. */
class OpenedUidDesign
internal constructor(
  val document: UiBuilderDocument,
  val production: ProductionUidFile?,
) {
  /** Saves visual edits while preserving the complete explicitly authored production API. */
  fun encode(current: UiBuilderDocument = document): String {
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
