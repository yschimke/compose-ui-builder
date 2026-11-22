package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The document environment key asking a format's export to tag each node's output with its id
 * ([NativeNodeTags.tag], as the semantics content description of the component it becomes), the way
 * the native preview lane tags Remote Compose. An editor playing the compiled document on its
 * canvas reads the tags back to know which drawn box is which node. Set only on the copy such a
 * canvas asks its host to export; never on a design's own export, whose screen reader would read
 * the tags aloud. A format that cannot tag its output ignores it, and the canvas keeps its own
 * picture.
 */
const val DOCUMENT_INSPECTION_TAGS_KEY: String = "uiBuilderInspectionTags"

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

  /**
   * Whether this format writes [componentId]. A catalog declaring the format keeps every component
   * it is given (the builder's own vocabulary included), so a stored design holding one still
   * validates and opens; the insert panel offers only what this answers true for, since anything
   * else is a component the export refuses.
   */
  fun writes(componentId: String): Boolean = true

  /** Whether [capabilities] advertise this format. */
  fun offered(capabilities: ExportCapabilitiesV1): Boolean

  /** Sets this format's flag on [builder]. */
  fun offer(builder: ExportCapabilitiesV1.Builder, enabled: Boolean)

  /**
   * Writes [document] as this format, or says why not. [policy] is `statusSemantics[policyKey]`.
   */
  fun export(document: UiBuilderDocument, policy: JsonObject): Result

  /**
   * How a host compiles [policy]'s documents itself, from files the catalog publishes, or null when
   * only the host's own service can. A host that cannot (no files, no compiler, a refusal) asks its
   * service, as it does for a format without one.
   */
  fun localCompilation(policy: JsonObject): LocalCompilation? = null

  sealed interface Result {
    /** The artifact's content, in [encoding]. */
    data class Emitted(val content: String) : Result

    data class Refused(val reasons: List<String>) : Result
  }
}

/**
 * A document format a host can compile without its service: [expand] writes a design as the
 * document [compiler] compiles, from [files] the catalog publishes (each a path under the catalog,
 * as its `ui-builder.json` names them). [compiler] is a JavaScript module whose `convert(text)`
 * answers the compiled bytes, so a browser host compiles with no round trip.
 */
class LocalCompilation(
  val files: List<String>,
  val compiler: String,
  val expand:
    (document: UiBuilderDocument, files: Map<String, String>) -> CatalogDocumentFormat.Result,
)

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

  /** How a host compiles [statusSemantics]' documents itself, or null when it cannot. */
  fun localCompilation(statusSemantics: JsonObject): LocalCompilation? =
    supported(statusSemantics)?.let { it.format.localCompilation(it.policy) }

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
   * Whether the insert panel offers [componentId] in a catalog with [statusSemantics]: everything,
   * unless the catalog declares a shipped format, and then what that format writes. A format this
   * build does not ship hides nothing; it exports nothing either, and says so.
   */
  fun offersOnPalette(statusSemantics: JsonObject, componentId: String): Boolean =
    when (val resolution = resolve(statusSemantics)) {
      Resolution.NotDeclared,
      is Resolution.Unsupported -> true
      is Resolution.Supported -> resolution.format.writes(componentId)
    }

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
