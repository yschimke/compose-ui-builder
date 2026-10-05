package example

import androidx.compose.runtime.Composable
import example.domain.ProjectEpisode
import example.ui.LibraryData
import example.ui.LibraryScreen
import example.ui.QueueScreen
import example.ui.components.EpisodeCard

// Handwritten application code depends on the declared API, including fields unused by a layout.
@Composable
fun Application(data: LibraryData, onEpisodeClick: (String) -> Unit) {
  LibraryScreen(data, onEpisodeClick)
  QueueScreen(data, onEpisodeClick)
  EpisodeCard(data.featured, onEpisodeClick)
}

fun sampleData(): LibraryData =
  LibraryData("My library", ProjectEpisode("Featured"), listOf(ProjectEpisode("Queued")))

@androidx.compose.ui.tooling.preview.Preview(
  widthDp = 320,
  heightDp = 120,
  showBackground = true,
  backgroundColor = 0xFFFFFFFF,
)
@Composable
fun DurableLibraryPreview() {
  androidx.compose.material3.MaterialTheme {
    LibraryScreen(sampleData(), onEpisodeClick = { println("Selected episode: $it") })
  }
}
