package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class UiBuilderLiveSessionApiTest {
  @Test
  fun `opening a design never creates one, however many times the page is loaded`() = runImmediate {
    // The browser used to seed and create a design whose open came back `notFound`, which made
    // loading a URL a mutation. Creating is a `POST` to the New design form's route or a `PUT` of
    // the design resource now; what the page does is open. A missing design is reported as
    // missing, and nothing on this path can write.
    val requests = mutableListOf<UiBuilderRequestV1>()
    var exists = false
    val transport = UiBuilderHttpTransport { request ->
      val envelope = protocolJson.decodeFromString(HttpRequestEnvelopeV1.serializer(), request.body)
      requests += envelope.request
      val response =
        when (envelope.request) {
          is OpenDesignRequestV1 ->
            if (exists) CatalogsResponseV1(emptyList())
            else ErrorResponseV1(ServiceErrorV1(ServiceErrorCodeV1.NOT_FOUND, "missing"))
          is CreateDesignRequestV1 -> error("opening a design must never create one")
          is ApplyOperationRequestV1 -> CatalogsResponseV1(emptyList())
          else -> error("unexpected request")
        }
      UiBuilderHttpResponse(
        200,
        protocolJson.encodeToString(
          HttpResponseEnvelopeV1.serializer(),
          HttpResponseEnvelopeV1(requestId = envelope.requestId, response = response),
        ),
      )
    }
    val http =
      UiBuilderProtocolHttpClient(
        "actor-a",
        "/requests",
        transport,
        MonotonicUiBuilderRequestIds("tab-a"),
      )
    val api = UiBuilderLiveSessionApi("fresh", http)

    val missing = assertIs<UiBuilderHttpResult.ServiceError>(api.open())
    assertEquals(ServiceErrorCodeV1.NOT_FOUND, missing.error.code)

    // The design comes into existence the way it now does — somewhere else entirely — and the
    // same page load opens it without ever asking to create it.
    exists = true
    assertIs<UiBuilderHttpResult.Response>(api.open())
    val localEdit =
      EditorSubmission.Batch(
        DesignCommand(
          designId = "fresh",
          operationId = "tab-a-editor-operation-0001",
          actorId = "actor-a",
          clientId = "tab-a",
          baseRevision = 0,
          operations =
            listOf(
              DesignOperation.SetProperty(
                "title",
                "text",
                buildJsonObject {
                  put("type", JsonPrimitive("string"))
                  put("value", JsonPrimitive("Shared edit"))
                },
              )
            ),
        )
      )
    assertIs<UiBuilderHttpResult.Response>(
      http.execute(
        ApplyOperationRequestV1(
          localEdit.toProtocolSubmission("actor-a", "tab-a", authoritativeRevision = 0)
        )
      )
    )
    assertIs<UiBuilderHttpResult.Response>(api.open())

    assertEquals(
      listOf(
        OpenDesignRequestV1::class,
        OpenDesignRequestV1::class,
        ApplyOperationRequestV1::class,
        OpenDesignRequestV1::class,
      ),
      requests.map { it::class },
    )
  }

  private fun <T> runImmediate(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(
      object : Continuation<T> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<T>) {
          completed = result
        }
      }
    )
    return completed?.getOrThrow() ?: error("fake transport suspended unexpectedly")
  }
}
