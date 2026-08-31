package ee.schimke.composeai.uibuilder.client

import ee.schimke.composeai.uibuilder.DesignCommand
import ee.schimke.composeai.uibuilder.DesignOperation
import ee.schimke.composeai.uibuilder.EditorSubmission
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

class UiBuilderProtocolBridgeTest {
  @Test
  fun `snapshot document becomes the renderer document without losing ordered content`() {
    val protocol =
      DesignDocumentV1(
        schema = "compose-ui-builder/v1",
        id = "shared-design",
        title = "Shared design",
        revision = 7,
        catalogPin = CatalogReferenceV1("m3", "2026.08", "sha256:test", "m3-runtime"),
        environment =
          DesignEnvironmentV1(
            widthDp = 1280,
            heightDp = 800,
            density = 1.0,
            theme = ThemeV1.LIGHT,
            dynamicColor = null,
            locale = "en-US",
            fontScale = 1.0,
            layoutDirection = LayoutDirectionV1.LTR,
            windowPosture = WindowPostureV1.FLAT,
            browserZoomPercent = null,
            fixedTime = null,
            animations = AnimationStateV1.SETTLED,
            networkAccess = null,
            background = null,
          ),
        stateVariables = emptyMap(),
        roots = listOf("first", "second"),
        nodes =
          linkedMapOf(
            "first" to DesignNodeV1("first", "layout/box"),
            "second" to DesignNodeV1("second", "m3/text"),
          ),
      )

    val renderer = protocol.toRendererDocument()

    assertEquals(7, renderer.revision)
    assertEquals(listOf("first", "second"), renderer.roots)
    assertEquals("m3-runtime", renderer.catalogPin["nativeRuntimeId"]?.jsonPrimitive?.content)
    assertEquals(1280, renderer.environment["widthDp"]?.jsonPrimitive?.content?.toInt())
    val createDocument = renderer.copy(revision = 0).toProtocolDocument()
    assertEquals(0, createDocument.revision)
    assertEquals(listOf("first", "second"), createDocument.roots)
  }

  @Test
  fun `editor batch uses the authoritative revision and configured actor envelope`() {
    val local =
      DesignCommand(
        designId = "shared-design",
        operationId = "browser-a-editor-operation-0001",
        actorId = "local-placeholder",
        clientId = "browser-a",
        baseRevision = 3,
        operations =
          listOf(
            DesignOperation.SetProperty(
              "title",
              "text",
              buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("value", JsonPrimitive("Observed by B"))
              },
            )
          ),
      )

    val protocol =
      EditorSubmission.Batch(local)
        .toProtocolSubmission(
          actorId = "actor-a",
          clientId = "browser-a",
          authoritativeRevision = 41,
        )

    val command = assertIs<DesignCommandV1>(protocol)
    assertEquals("actor-a", command.actorId)
    assertEquals("browser-a", command.clientId)
    assertEquals(41, command.baseRevision)
    assertIs<SetPropertyMutationV1>(command.operations.single())
  }
}
