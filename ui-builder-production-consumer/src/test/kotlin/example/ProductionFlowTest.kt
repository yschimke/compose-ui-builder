package example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import example.domain.ProjectEpisode
import example.ui.DynamicLibraryData
import example.ui.DynamicLibraryScreen
import example.ui.SelectionData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProductionFlowTest {
  @Test
  fun `nullable branches and fallback follow replacement data`() = runComposeUiTest {
    val data = mutableStateOf(DynamicLibraryData(null, null))
    val selected = mutableListOf<String>()
    setContent { MaterialTheme { DynamicLibraryScreen(data.value, selected::add) } }
    onNodeWithText("No selection").assertExists()
    onAllNodes(hasClickAction()).assertCountEquals(0)
    runOnIdle {
      data.value = DynamicLibraryData(SelectionData(null, ProjectEpisode("Featured")), emptyList())
    }
    onNodeWithText("No selection").assertExists()
    onNodeWithText("Featured").performClick()
    assertEquals(listOf("Featured"), selected)
    runOnIdle { data.value = DynamicLibraryData(SelectionData("Chosen", null), null) }
    onNodeWithText("Chosen").assertExists()
    onNodeWithText("No selection").assertDoesNotExist()
    onNodeWithText("Featured").assertDoesNotExist()
    runOnIdle { data.value = DynamicLibraryData(null, emptyList()) }
    onNodeWithText("No selection").assertExists()
  }

  @Test
  fun `keyed items retain identity across reorder and deliver current payloads`() =
    runComposeUiTest {
      val data =
        mutableStateOf(
          DynamicLibraryData(null, listOf(ProjectEpisode("A", "a"), ProjectEpisode("B", "b")))
        )
      val selected = mutableListOf<String>()
      setContent { MaterialTheme { DynamicLibraryScreen(data.value, selected::add) } }
      val a = onNodeWithText("A").fetchSemanticsNode().id
      val b = onNodeWithText("B").fetchSemanticsNode().id
      onNodeWithText("A").performClick()
      runOnIdle {
        data.value =
          data.value.copy(
            episodes = listOf(ProjectEpisode("B updated", "b"), ProjectEpisode("A", "a"))
          )
      }
      assertEquals(a, onNodeWithText("A").fetchSemanticsNode().id)
      assertEquals(b, onNodeWithText("B updated").fetchSemanticsNode().id)
      onNodeWithText("B updated").performClick()
      assertEquals(listOf("A", "B updated"), selected)
      runOnIdle {
        data.value = data.value.copy(episodes = listOf(ProjectEpisode("B updated", "b")))
      }
      onNodeWithText("A").assertDoesNotExist()
      assertEquals(b, onNodeWithText("B updated").fetchSemanticsNode().id)
    }

  @Test
  fun `duplicate item keys fail instead of aliasing composition identity`() {
    val failure = assertFails {
      runComposeUiTest {
        setContent {
          MaterialTheme {
            DynamicLibraryScreen(
              DynamicLibraryData(
                null,
                listOf(ProjectEpisode("A", "same"), ProjectEpisode("B", "same")),
              ),
              {},
            )
          }
        }
        waitForIdle()
      }
    }
    assertTrue(
      generateSequence(failure) { it.cause }
        .any { it.message?.contains("Duplicate production list keys") == true },
      failure.toString(),
    )
  }
}
