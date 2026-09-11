package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import generated.uibuilder.StateActions
import kotlin.test.Test
import kotlinx.serialization.json.Json

/** Compiles and executes the exact source pinned by BehaviorScreenExportTest in the server. */
@OptIn(ExperimentalTestApi::class)
class GeneratedStateActionsTest {
  @Test
  fun `the existing builder canvas executes the same document as the compiled export`() =
    runDesktopComposeUiTest {
      val document =
        Json.decodeFromString<DesignDocumentV1>(
            checkNotNull(javaClass.getResource("/state-actions.document.json")).readText()
          )
          .toUiBuilderDocument()
      setContent { UiBuilderSurface(document) }
      onNodeWithText("Ready").assertIsEnabled().performClick()
      onNodeWithText("Updated").assertIsNotEnabled()
      onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
    }

  @Test
  fun `exported button changes text, enabled state and progress together`() =
    runDesktopComposeUiTest {
      setContent { MaterialTheme { StateActions() } }
      onNodeWithText("Ready").assertIsEnabled().performClick()
      onNodeWithText("Updated").assertIsNotEnabled()
      onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        .assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
    }
}
