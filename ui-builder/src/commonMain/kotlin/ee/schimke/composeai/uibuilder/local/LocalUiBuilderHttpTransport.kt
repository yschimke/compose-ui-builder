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
 *
 * It exists so the swap between a design that lives on the server and one that lives in this
 * browser is *one constructor argument* in the editor's bootstrap. Everything downstream — the
 * request-id correlation, the strict envelope codec, the drain loop that serializes edits and the
 * rules that decide which snapshot may be displayed — is the code the live session already runs,
 * and is not asked which mode it is in.
 *
 * The round trip through JSON is deliberate rather than an oversight. Skipping it would mean the
 * local mode exercised a different codec path from the live one, and the first shape either side
 * got wrong would be a bug only one of the two modes could find.
 */
class LocalUiBuilderHttpTransport(private val service: LocalUiBuilderService) :
  UiBuilderHttpTransport {
  override suspend fun post(request: UiBuilderHttpRequest): UiBuilderHttpResponse {
    val envelope = protocolJson.decodeFromString(HttpRequestEnvelopeV1.serializer(), request.body)
    val response = service.execute(envelope.request)
    return UiBuilderHttpResponse(
      // The client reads the body, and reports the status only when the body will not parse. A
      // service error is still a well-formed answer, so it is the 400 the server would send rather
      // than a transport failure.
      statusCode = if (response is ErrorResponseV1) 400 else 200,
      body =
        protocolJson.encodeToString(
          HttpResponseEnvelopeV1.serializer(),
          HttpResponseEnvelopeV1(requestId = envelope.requestId, response = response),
        ),
    )
  }
}
