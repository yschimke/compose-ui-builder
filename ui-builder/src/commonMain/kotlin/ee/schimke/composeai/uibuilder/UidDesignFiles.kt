package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.json.Json

/**
 * A checked-in design file (`.uid`) as the editor's hosts read and write it.
 *
 * Every host that owns the file rather than the editor — the IntelliJ plugin's project sessions,
 * the VS Code host bridge (`HostBridgeApp.kt`) and the MCP App host (`McpAppHostApp.kt`) — must
 * write the same design as the same bytes, or opening a file in one and saving it in another is a
 * diff of whitespace. So the format lives here once: `DesignDocumentV1`, with
 * `UiBuilderProjectService.projectDesignJson`'s options, and a trailing newline.
 */
object UidDesignFiles {
  /** The declarations a checked-in design may carry, as the IntelliJ plugin accepts them. */
  val SCHEMAS: Set<String> =
    setOf("compose-ui-builder-document/v1", "compose-ui-builder-document/v1-candidate")

  /** `UiBuilderProjectService.projectDesignJson`, so every host writes a design identically. */
  private val json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
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
