package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlImporter
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * What a host may state when it creates a watch face design from Watch Face Format XML.
 *
 * Built with [Builder], so an option added later changes no signature.
 */
public class WatchFaceXmlCreateOptions private constructor(builder: Builder) {
  /** The Watch Face Format version the XML targets, 1–5. XML does not carry it. */
  public val wffVersion: Int = builder.wffVersion

  /** The new design's id; a fresh `watch-face-…` id when null. */
  public val designId: String? = builder.designId

  public val title: String = builder.title

  public fun newBuilder(): Builder =
    Builder().also {
      it.wffVersion = wffVersion
      it.designId = designId
      it.title = title
    }

  public class Builder {
    public var wffVersion: Int = WatchFaceXmlExporter.DEFAULT_WFF_VERSION
    public var designId: String? = null
    public var title: String = "Imported watch face"

    public fun build(): WatchFaceXmlCreateOptions = WatchFaceXmlCreateOptions(this)
  }
}

/** The outcome of [createDesignFromWatchFaceXml]. Never an exception. */
public sealed interface WatchFaceXmlCreateResult {
  /**
   * The design exists, owned as any created design is, at [revision] (0, like every new design: the
   * first edit is revision 1). [warnings] name what the import kept as raw XML.
   */
  public data class Created(
    val designId: String,
    val revision: Long,
    val warnings: List<String>,
  ) : WatchFaceXmlCreateResult

  /**
   * Nothing was created. [line] and [column] (1-based) are set when the XML is malformed; [code] is
   * set when the service refused the design.
   */
  public data class Failed(
    val reasons: List<String>,
    val line: Int? = null,
    val column: Int? = null,
    val code: ServiceErrorCodeV1? = null,
  ) : WatchFaceXmlCreateResult
}

/**
 * Create a design from Watch Face Format XML: [WatchFaceXmlImporter] reads it losslessly into the
 * `wff-watchface` catalog, and the design goes through the service's own `CreateDesign`, so it is
 * pinned, validated, quota-checked, owned by [actor] and logged like any other, and from there
 * edited, undone and shared through the usual requests.
 *
 * Additive: it is built on [UiBuilderServiceRequest.CreateDesign] and
 * [UiBuilderServiceRequest.ListCatalogs], so no request or response type changes.
 */
public suspend fun UiBuilderServicePort.createDesignFromWatchFaceXml(
  actor: AuthenticatedUiBuilderActor,
  xml: String,
  options: WatchFaceXmlCreateOptions = WatchFaceXmlCreateOptions.Builder().build(),
): WatchFaceXmlCreateResult {
  val designId = options.designId ?: "watch-face-${UUID.randomUUID()}"
  val imported =
    WatchFaceXmlImporter.import(
      xml,
      WatchFaceXmlImporter.Options(
        wffVersion = options.wffVersion,
        id = designId,
        title = options.title,
      ),
    )
  if (imported is WatchFaceXmlImporter.Result.Failed)
    return WatchFaceXmlCreateResult.Failed(imported.reasons, imported.line, imported.column)
  imported as WatchFaceXmlImporter.Result.Imported
  return try {
    val catalogs = execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.ListCatalogs))
    val pin =
      (catalogs as? UiBuilderServiceResponse.Catalogs)
        ?.pins
        ?.get(WatchFaceXmlExporter.CATALOG_SYSTEM_ID)
        ?: return WatchFaceXmlCreateResult.Failed(
          listOf("the ${WatchFaceXmlExporter.CATALOG_SYSTEM_ID} catalog is not served here"),
          code = ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
        )
    val document = imported.document.toDesignDocumentV1().copy(catalogPin = pin)
    when (
      val response =
        execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.CreateDesign(document)))
    ) {
      is UiBuilderServiceResponse.Snapshot ->
        WatchFaceXmlCreateResult.Created(
          response.snapshot.designId,
          response.snapshot.state.document.revision,
          imported.warnings,
        )
      is UiBuilderServiceResponse.Error ->
        WatchFaceXmlCreateResult.Failed(
          listOf(response.error.message),
          code = response.error.code,
        )
      else -> WatchFaceXmlCreateResult.Failed(listOf("unexpected service response: $response"))
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    WatchFaceXmlCreateResult.Failed(listOf("create failed: ${e::class.simpleName}: ${e.message}"))
  }
}
