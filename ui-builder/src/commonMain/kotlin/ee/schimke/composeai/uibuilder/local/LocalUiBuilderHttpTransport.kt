package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.client.UiBuilderHttpRequest
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpResponse
import ee.schimke.composeai.uibuilder.client.UiBuilderHttpTransport
import ee.schimke.composeai.uibuilder.client.protocolJson
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1

/**
 * The transport that never leaves the page: encode, dispatch to [LocalUiBuilderService], decode.
 * Swapping server and browser storage is one constructor argument; everything downstream (request
 * correlation, envelope codec, drain loop, snapshot rules) is the live session's code. The JSON
 * round trip is deliberate so both modes exercise the same codec path.
 */
class LocalUiBuilderHttpTransport(private val service: LocalUiBuilderService) :
  UiBuilderHttpTransport {
  override suspend fun post(request: UiBuilderHttpRequest): UiBuilderHttpResponse {
    val envelope = protocolJson.decodeFromString(HttpRequestEnvelopeV1.serializer(), request.body)
    val response = service.execute(envelope.request)
    return UiBuilderHttpResponse(
      // A service error is a well-formed answer, so it gets the server's 400 rather than a
      // transport failure.
      statusCode = if (response is ErrorResponseV1) 400 else 200,
      body =
        protocolJson.encodeToString(
          HttpResponseEnvelopeV1.serializer(),
          HttpResponseEnvelopeV1(requestId = envelope.requestId, response = response),
        ),
    )
  }
}
