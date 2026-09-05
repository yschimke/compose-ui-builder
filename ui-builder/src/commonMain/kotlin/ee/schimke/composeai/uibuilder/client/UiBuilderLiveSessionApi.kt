package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1

/**
 * HTTP bootstrap boundary shared by the Wasm entry point and deterministic session tests.
 *
 * Opening is all the browser does. It used to create too — seeding a document and posting it when
 * the open came back `notFound` — which made loading a page a mutation. Creation is a `POST` to
 * `/ui-builder/<catalog>` (the New design form) or a `PUT` of the design's own API resource, both
 * of which the server answers by seeding the document itself.
 */
class UiBuilderLiveSessionApi(
  private val designId: String,
  private val http: UiBuilderProtocolHttpClient,
) {
  suspend fun open(): UiBuilderHttpResult = http.execute(OpenDesignRequestV1(designId))
}
