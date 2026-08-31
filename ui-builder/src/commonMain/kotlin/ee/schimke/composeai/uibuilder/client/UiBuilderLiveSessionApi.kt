package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1

/** HTTP bootstrap boundary shared by the Wasm entry point and deterministic session tests. */
class UiBuilderLiveSessionApi(
  private val designId: String,
  private val http: UiBuilderProtocolHttpClient,
) {
  suspend fun openOrCreate(
    createIfMissing: Boolean,
    seed: suspend () -> DesignDocumentV1,
  ): UiBuilderHttpResult {
    val opened = http.execute(OpenDesignRequestV1(designId))
    if (
      !createIfMissing ||
        opened !is UiBuilderHttpResult.ServiceError ||
        opened.error.code != ServiceErrorCodeV1.NOT_FOUND
    ) {
      return opened
    }
    val document = seed()
    require(document.id == designId) { "seed design id must match the requested live design" }
    require(document.revision == 0L) { "seed design must start at revision zero" }
    return http.execute(CreateDesignRequestV1(document))
  }
}
