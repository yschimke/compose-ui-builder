package ee.schimke.composeai.uibuilder.guidelines

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.GuidelineFindingsOverlay
import ee.schimke.composeai.uibuilder.editor.GuidelinesSection
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionGeneration
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderNodeInspection
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderSemanticsInspection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

@OptIn(ExperimentalTestApi::class)
class GuidelineFindingsOverlayTest {
  private class Host : DesignGuidelineHost {
    var overlayStored: Boolean? = null

    override fun storedKey(): String? = null

    override fun storeKey(key: String?) {}

    override fun storedModel(): String? = null

    override fun storeModel(model: String?) {}

    override fun storedOverlay() = overlayStored

    override fun storeOverlay(shown: Boolean) {
      overlayStored = shown
    }

    override val signIn: (() -> Unit)? = null

    override suspend fun completeSignIn() = DesignGuidelineHost.SignInResult.NotReturning

    override suspend fun complete(body: String, key: String) = error("not called")

    override suspend fun picture(document: UiBuilderDocument): String? = null

    override suspend fun sharedResult() =
      DesignGuidelineRecord(
        revision = 1,
        model = "m",
        rulesVersion = 5,
        asked = listOf("wear.layout.responsive-width"),
        verdicts =
          DesignGuidelinePrompt.parseVerdicts(
            """{"verdicts":[{"ruleId":"wear.layout.responsive-width","verdict":"fail",""" +
              """"confidence":0.9,"nodeIds":["stop"],"reason":"Fixed width."}]}"""
          ),
      )
  }

  @Test
  fun `a finding marks its node on the canvas, and the switch takes it away`() = runComposeUiTest {
    val host = Host()
    val controller = DesignGuidelineController(host)
    val selected = mutableListOf<String>()
    setContent {
      MaterialTheme {
        CompositionLocalProvider(LocalDesignGuidelineCheck provides controller) {
          Column {
            GuidelinesSection(controller, document(), {}, {})
            Box(Modifier.size(200.dp)) {
              GuidelineFindingsOverlay(snapshot(), Offset.Zero, 1f) { selected += it }
            }
          }
        }
      }
    }
    waitUntil(timeoutMillis = 5_000) {
      onAllNodesWithContentDescription("Guidelines on stop", substring = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    onNodeWithContentDescription("Guidelines on stop", substring = true).performClick()
    assertEquals(listOf("stop"), selected)

    onNodeWithContentDescription("Show findings on the canvas").performClick()
    onNodeWithContentDescription("Guidelines on stop", substring = true).assertDoesNotExist()
    assertEquals(false, host.overlayStored)
  }

  private fun snapshot() =
    UiBuilderInspectionSnapshot(
      documentId = "workout",
      documentRevision = 1,
      generation =
        UiBuilderInspectionGeneration(
          "workout@1",
          expectedAuthoredNodeIds = listOf("stop"),
          expectedAuthoredTextNodeIds = emptyList(),
          measuredNodeIds = listOf("stop"),
          measuredTextNodeIds = emptyList(),
        ),
      nodes =
        listOf(
          UiBuilderNodeInspection(
            "stop",
            "wear-m3/button",
            UiBuilderPixelBounds(x = 20f, y = 30f, width = 80f, height = 40f),
            semantics = UiBuilderSemanticsInspection(role = "button", actions = emptyList()),
          )
        ),
      slots = emptyList(),
    )

  private fun document(): UiBuilderDocument =
    Json.decodeFromString(
      UiBuilderDocument.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "workout", "title": "Workout", "revision": 1,
        "catalogPin": {"systemId": "wear-m3"},
        "environment": {"widthDp": 192, "heightDp": 192},
        "stateVariables": {},
        "roots": ["stop"],
        "nodes": {"stop": {"id": "stop", "componentId": "wear-m3/button"}}
      }
      """,
    )
}
