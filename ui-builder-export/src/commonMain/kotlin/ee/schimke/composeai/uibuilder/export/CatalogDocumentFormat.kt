package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One way a catalog's designs leave the builder that is not Compose source: a vendor's component
 * JSON today, and in time Watch Face Format XML, a Home Assistant dashboard config, A2UI messages.
 *
 * A catalog selects one by declaration (`statusSemantics.documentFormat {adapter, version}`), as it
 * selects its Compose export by `composeSourceExport`, and every part of the builder that cares
 * (the editor's code pane and Issues, the runtime's export capabilities, the served export) asks
 * through this interface. None of them names a catalog. See
 * `docs/design/UI_BUILDER_CATALOG_FORMATS.md`.
 */
interface CatalogDocumentFormat {
  /** The adapter id a catalog declares, e.g. `<vendor>-component-json`. */
  val id: String

  /** The adapter version a catalog declares. */
  val version: Int

  /** What a person reads: the code pane's caption, the "No …" refusal heading. */
  val label: String

  /** The `statusSemantics` key holding this format's policy, passed to [export] as `policy`. */
  val policyKey: String

  /** The wire format a client asks for. */
  val wireFormat: ExportFormatV1

  /** The artifact's media type. */
  val mediaType: String

  /** How the artifact's content travels. */
  val encoding: ExportEncodingV1

  /** Whether [capabilities] advertise this format. */
  fun offered(capabilities: ExportCapabilitiesV1): Boolean

  /** Sets this format's flag on [builder]. */
  fun offer(builder: ExportCapabilitiesV1.Builder, enabled: Boolean)

  /**
   * Writes [document] as this format, or says why not. [policy] is `statusSemantics[policyKey]`.
   */
  fun export(document: UiBuilderDocument, policy: JsonObject): Result

  sealed interface Result {
    /** The artifact's content, in [encoding]. */
    data class Emitted(val content: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }
}

/**
 * The document formats a build ships, and how a catalog resolves to one. A fixed list, not a plugin
 * loader: an unknown declaration is [Resolution.Unsupported], never code to load.
 */
class CatalogDocumentFormats(val formats: List<CatalogDocumentFormat>) {

  sealed interface Resolution {
    /** The catalog's designs are Compose (or another lane); no document format applies. */
    data object NotDeclared : Resolution

    /** The catalog's designs export as [format], with the catalog's [policy] block. */
    data class Supported(val format: CatalogDocumentFormat, val policy: JsonObject) : Resolution

    /** The catalog names a format this build does not ship. */
    data class Unsupported(val adapter: String, val version: Int) : Resolution
  }

  /**
   * [statusSemantics]' format. A `documentFormat` declaration decides. Without one, a catalog that
   * publishes a format's policy block is read as declaring that format's first version: the block
   * is how catalogs published before the declaration existed said what they were.
   */
  fun resolve(statusSemantics: JsonObject): Resolution {
    val declared = statusSemantics[DECLARATION] as? JsonObject
    if (declared != null) {
      val adapter = (declared["adapter"] as? JsonPrimitive)?.content.orEmpty()
      val version = (declared["version"] as? JsonPrimitive)?.intOrNull ?: 1
      val format =
        formats.firstOrNull { it.id == adapter && it.version == version }
          ?: return Resolution.Unsupported(adapter, version)
      return Resolution.Supported(format, policyOf(format, statusSemantics))
    }
    val inferred =
      formats.firstOrNull { statusSemantics[it.policyKey] is JsonObject }
        ?: return Resolution.NotDeclared
    return Resolution.Supported(inferred, policyOf(inferred, statusSemantics))
  }

  /** The format [statusSemantics] resolves to, or null. */
  fun supported(statusSemantics: JsonObject): Resolution.Supported? =
    resolve(statusSemantics) as? Resolution.Supported

  /**
   * What [statusSemantics]' designs are written as, for a caption: the format's label, the adapter
   * id of one this build does not ship, or null when no document format applies.
   */
  fun label(statusSemantics: JsonObject): String? =
    when (val resolution = resolve(statusSemantics)) {
      Resolution.NotDeclared -> null
      is Resolution.Supported -> resolution.format.label
      is Resolution.Unsupported -> resolution.adapter
    }

  /**
   * [document] written in [statusSemantics]' format, or null when no document format applies. A
   * declared format this build does not ship is a refusal, never a guess at another writer.
   */
  fun export(
    statusSemantics: JsonObject,
    document: UiBuilderDocument,
  ): CatalogDocumentFormat.Result? =
    when (val resolution = resolve(statusSemantics)) {
      Resolution.NotDeclared -> null
      is Resolution.Supported -> resolution.format.export(document, resolution.policy)
      is Resolution.Unsupported ->
        CatalogDocumentFormat.Result.Refused(
          listOf(
            "this build cannot write ${resolution.adapter} version ${resolution.version}, " +
              "the document format this catalog declares",
          ),
        )
    }

  /** Whether [statusSemantics] names any document format, shipped or not. */
  fun declares(statusSemantics: JsonObject): Boolean =
    resolve(statusSemantics) != Resolution.NotDeclared

  /** [base] with every shipped format's flag set. For an executor that writes them all. */
  fun offerAll(base: ExportCapabilitiesV1): ExportCapabilitiesV1 =
    base.newBuilder().also { builder -> formats.forEach { it.offer(builder, true) } }.build()

  /**
   * A catalog's export capabilities. Every format's flag starts cleared, so a catalog never
   * advertises a format it does not declare, whatever [host] can write. A catalog that declares one
   * exports as that format alone: its components exist only in the format's own library, so Compose
   * source and the Remote Compose lowering are off. The declared format is offered where [host]
   * offers it, and a compiled Remote Compose document (`remoteDocument`, an `.rc`) where the host
   * has a compiler for the format: [compilers] holds the format ids the host compiles, a step this
   * builder cannot take itself (the format's own service writes the bytes).
   */
  fun capabilities(
    base: ExportCapabilitiesV1,
    statusSemantics: JsonObject,
    host: ExportCapabilitiesV1,
    compilers: Set<String> = emptySet(),
  ): ExportCapabilitiesV1 {
    val builder = base.newBuilder()
    formats.forEach { it.offer(builder, false) }
    when (val resolution = resolve(statusSemantics)) {
      Resolution.NotDeclared -> Unit
      is Resolution.Unsupported -> builder.offerNoOtherLane()
      is Resolution.Supported -> {
        builder.offerNoOtherLane()
        resolution.format.offer(builder, resolution.format.offered(host))
        builder.remoteDocument = resolution.format.id in compilers
      }
    }
    return builder.build()
  }

  private fun ExportCapabilitiesV1.Builder.offerNoOtherLane() {
    composeCode = false
    remoteJson = false
    remoteDocument = false
  }

  private fun policyOf(format: CatalogDocumentFormat, statusSemantics: JsonObject): JsonObject =
    statusSemantics[format.policyKey] as? JsonObject ?: JsonObject(emptyMap())

  companion object {
    /** The `statusSemantics` key a catalog declares its document format under. */
    const val DECLARATION: String = "documentFormat"
  }
}
