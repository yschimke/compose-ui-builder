package example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import example.domain.ProjectEpisode
import example.ui.LibraryScreen
import example.ui.QueueScreen
import example.ui.components.EpisodeCard
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ProductionEventsTest {
  @Test
  fun `component forwards mapped payload and reads updated application data`() = runComposeUiTest {
    val data = mutableStateOf(sampleData())
    val selected = mutableListOf<String>()
    setContent { MaterialTheme { LibraryScreen(data.value, onEpisodeClick = selected::add) } }
    onNodeWithText("Featured").performClick()
    assertEquals(listOf("Featured"), selected)
    runOnIdle { data.value = data.value.copy(featured = ProjectEpisode("Updated")) }
    onNodeWithText("Updated").performClick()
    assertEquals(listOf("Featured", "Updated"), selected)
  }

  @Test
  fun `second screen forwards the shared component callback`() = runComposeUiTest {
    val selected = mutableListOf<String>()
    setContent { MaterialTheme { QueueScreen(sampleData(), onEpisodeClick = selected::add) } }
    onNodeWithText("Featured").performClick()
    assertEquals(listOf("Featured"), selected)
  }

  @Test
  fun `component API invokes the required application callback`() = runComposeUiTest {
    val selected = mutableListOf<String>()
    setContent {
      MaterialTheme { EpisodeCard(ProjectEpisode("Direct"), onEpisodeClick = selected::add) }
    }
    onNodeWithText("Direct").performClick()
    assertEquals(listOf("Direct"), selected)
  }
}
