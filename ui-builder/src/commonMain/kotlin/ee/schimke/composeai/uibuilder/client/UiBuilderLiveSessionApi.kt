package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1

/**
 * HTTP bootstrap boundary shared by the Wasm entry point and deterministic session tests. Opening
 * only: creation is a `POST` to `/ui-builder/designs` (the New design form) or a `PUT` of the
 * design's API resource, both seeded by the server.
 */
class UiBuilderLiveSessionApi(
  private val designId: String,
  private val http: UiBuilderProtocolHttpClient,
) {
  suspend fun open(): UiBuilderHttpResult = http.execute(OpenDesignRequestV1(designId))
}
