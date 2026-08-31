package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
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
  fun `fresh session creates once after not found and subsequent refresh opens`() = runImmediate {
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
          is CreateDesignRequestV1 -> {
            exists = true
            CatalogsResponseV1(emptyList())
          }
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

    assertIs<UiBuilderHttpResult.Response>(api.openOrCreate(true) { seed("fresh") })
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
    assertIs<UiBuilderHttpResult.Response>(api.openOrCreate(true) { error("must not reseed") })

    assertEquals(
      listOf(
        OpenDesignRequestV1::class,
        CreateDesignRequestV1::class,
        ApplyOperationRequestV1::class,
        OpenDesignRequestV1::class,
      ),
      requests.map { it::class },
    )
    assertEquals("fresh", assertIs<CreateDesignRequestV1>(requests[1]).document.id)
  }

  private fun seed(id: String): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder/v1",
      id = id,
      title = "Seed",
      revision = 0,
      catalogPin = CatalogReferenceV1("m3", "revision", "digest", "runtime"),
      environment =
        DesignEnvironmentV1(
          widthDp = 1280,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-GB",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
        ),
      stateVariables = emptyMap(),
      roots = emptyList(),
      nodes = emptyMap(),
    )

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
