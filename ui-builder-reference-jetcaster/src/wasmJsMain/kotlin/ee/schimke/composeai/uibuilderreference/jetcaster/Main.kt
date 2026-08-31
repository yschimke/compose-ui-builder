@file:OptIn(
  androidx.compose.foundation.ExperimentalFoundationApi::class,
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package ee.schimke.composeai.uibuilderreference.jetcaster

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport

private const val AndroidCoverKey = "jetcaster.cover.android-developers-backstage"
private const val GoogleCoverKey = "jetcaster.cover.google-developers-podcast"

private val ReferenceColors =
  darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF918F99),
    outlineVariant = Color(0xFF46464F),
    surfaceContainer = Color(0xFF1D1F25),
    surfaceContainerLow = Color(0xFF191B20),
    surfaceContainerHigh = Color(0xFF282A30),
    surfaceContainerHighest = Color(0xFF33353B),
  )

private data class Podcast(
  val key: String,
  val title: String,
  val author: String,
  val palette: List<Color>,
  val followed: Boolean,
)

private data class Episode(
  val key: String,
  val number: Int,
  val title: String,
  val summary: String,
  val metadata: String,
)

private val AndroidPodcast =
  Podcast(
    key = AndroidCoverKey,
    title = "Android Developers Backstage",
    author = "Android Developers",
    palette = listOf(Color(0xFF0B57D0), Color(0xFF00A896), Color(0xFF101828)),
    followed = true,
  )

private val GooglePodcast =
  Podcast(
    key = GoogleCoverKey,
    title = "Google Developers podcast",
    author = "Google Developers",
    palette = listOf(Color(0xFFEA4335), Color(0xFFFBBC04), Color(0xFF174EA6)),
    followed = false,
  )

private val Episodes =
  listOf(
    Episode(
      key = "episode-140",
      number = 140,
      title = "Episode 140: Lorem ipsum dolor",
      summary =
        "In this episode, Romain, Chet and Tor talked with Mady Melor and Artur Tsurkan from the System UI team about... Bubbles!",
      metadata = "May 16, 2024 · 42 min",
    ),
    Episode(
      key = "episode-139",
      number = 139,
      title = "Episode 139: Compose across screens",
      summary =
        "A conversation about adaptive layouts, large screens, and practical Compose architecture.",
      metadata = "May 9, 2024 · 38 min",
    ),
  )

private val DetailEpisodes =
  listOf(
    Episodes[0].copy(summary = "Romain, Chet and Tor talk with the System UI team about Bubbles."),
    Episodes[1],
  )

fun main() {
  ComposeViewport(viewportContainerId = "composeApp") { JetcasterReferenceApp() }
}

@Composable
private fun JetcasterReferenceApp() {
  MaterialTheme(colorScheme = ReferenceColors) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 1280.dp) {
          Row(Modifier.fillMaxSize()) {
            MainDiscoverPane(Modifier.width(744.dp).fillMaxHeight())
            Spacer(
              Modifier.width(24.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)
            )
            PodcastDetailPane(Modifier.weight(1f).fillMaxHeight())
          }
        } else {
          MainDiscoverPane(Modifier.fillMaxSize())
        }
      }
    }
  }
  LaunchedEffect(Unit) { publishReady() }
}

@Composable
private fun MainDiscoverPane(modifier: Modifier = Modifier) {
  var selectedCategory by remember { mutableStateOf("Crime") }
  val backgroundColor = MaterialTheme.colorScheme.background
  val scrimColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
  Box(
    modifier.drawBehind {
      drawRect(backgroundColor)
      drawRect(
        brush =
          Brush.radialGradient(
            colors = listOf(scrimColor, Color.Transparent),
            center = Offset(0f, 0f),
            radius = size.maxDimension * 0.82f,
          )
      )
    }
  ) {
    Scaffold(
      containerColor = Color.Transparent,
      topBar = { SearchHeader() },
      modifier = Modifier.fillMaxSize(),
    ) { contentPadding ->
      Box(Modifier.padding(contentPadding).fillMaxSize()) {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(362.dp),
          contentPadding = PaddingValues(bottom = 88.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
            CategoryRow(selectedCategory) { selectedCategory = it }
          }
          item(key = "podcast-carousel", span = { GridItemSpan(maxLineSpan) }) { PodcastCarousel() }
          item(key = Episodes[0].key) {
            EpisodeCard(
              episode = Episodes[0],
              showArtwork = true,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
          }
        }
        DestinationToolbar(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
      }
    }
  }
}

@Composable
private fun SearchHeader() {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.End,
  ) {
    Surface(
      shape = RoundedCornerShape(32.dp),
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 2.dp,
      modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
      Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          Icons.Default.Search,
          contentDescription = "Search",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          "Search for a podcast",
          Modifier.padding(horizontal = 16.dp).weight(1f),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
          Icons.Default.AccountCircle,
          contentDescription = "Account",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun CategoryRow(selectedCategory: String, onSelected: (String) -> Unit) {
  LazyRow(
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    items(count = 3, key = { listOf("Crime", "News", "Comedy")[it] }) { index ->
      val label = listOf("Crime", "News", "Comedy")[index]
      FilterChip(
        selected = label == selectedCategory,
        onClick = { onSelected(label) },
        leadingIcon =
          if (label == selectedCategory) {
            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
          } else {
            null
          },
        label = { Text(label) },
        shape = MaterialTheme.shapes.large,
      )
    }
  }
}

@Composable
private fun PodcastCarousel() {
  LazyRow(
    contentPadding = PaddingValues(start = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    item(key = AndroidPodcast.key) { PodcastArtworkCard(AndroidPodcast) }
    item(key = GooglePodcast.key) { PodcastArtworkCard(GooglePodcast) }
  }
}

@Composable
private fun PodcastArtworkCard(podcast: Podcast) {
  Card(
    shape = MaterialTheme.shapes.large,
    modifier = Modifier.size(128.dp).clip(MaterialTheme.shapes.large),
  ) {
    Box(Modifier.fillMaxSize()) {
      Artwork(podcast, Modifier.fillMaxSize())
      Box(
        Modifier.fillMaxSize()
          .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
      )
      IconButton(
        onClick = {},
        modifier =
          Modifier.align(Alignment.TopStart)
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.46f), CircleShape),
      ) {
        Icon(
          if (podcast.followed) Icons.Default.CheckCircle else Icons.Default.AddCircle,
          contentDescription = if (podcast.followed) "Following" else "Follow",
          tint = if (podcast.followed) MaterialTheme.colorScheme.primary else Color.White,
        )
      }
      Text(
        podcast.title,
        Modifier.align(Alignment.BottomStart).padding(16.dp),
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun Artwork(podcast: Podcast, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    drawRect(
      brush =
        Brush.linearGradient(
          podcast.palette,
          start = Offset.Zero,
          end = Offset(size.width, size.height),
        )
    )
    drawCircle(
      color = Color.White.copy(alpha = 0.18f),
      radius = size.minDimension * 0.34f,
      center = Offset(size.width * 0.76f, size.height * 0.24f),
    )
    drawCircle(
      color = Color.Black.copy(alpha = 0.18f),
      radius = size.minDimension * 0.22f,
      center = Offset(size.width * 0.22f, size.height * 0.72f),
    )
    val path =
      Path().apply {
        moveTo(size.width * 0.19f, size.height * 0.32f)
        lineTo(size.width * 0.48f, size.height * 0.18f)
        lineTo(size.width * 0.82f, size.height * 0.58f)
        lineTo(size.width * 0.48f, size.height * 0.78f)
        close()
      }
    drawPath(path, Color.White.copy(alpha = 0.27f))
    drawRect(
      color = Color.White.copy(alpha = 0.72f),
      topLeft = Offset(size.width * 0.30f, size.height * 0.39f),
      size = Size(size.width * 0.10f, size.height * 0.28f),
    )
    drawRect(
      color = Color.White.copy(alpha = 0.72f),
      topLeft = Offset(size.width * 0.47f, size.height * 0.30f),
      size = Size(size.width * 0.10f, size.height * 0.38f),
    )
    drawRect(
      color = Color.White.copy(alpha = 0.72f),
      topLeft = Offset(size.width * 0.64f, size.height * 0.43f),
      size = Size(size.width * 0.10f, size.height * 0.24f),
    )
    drawCircle(
      color = Color.White.copy(alpha = 0.72f),
      radius = size.minDimension * 0.28f,
      style = Stroke(width = size.minDimension * 0.035f),
    )
  }
}

@Composable
private fun EpisodeCard(
  episode: Episode,
  showArtwork: Boolean,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier,
    shape = MaterialTheme.shapes.large,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(
      Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = if (showArtwork) Arrangement.spacedBy(8.dp) else Arrangement.Top,
    ) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            episode.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            AndroidPodcast.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            episode.summary,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
        if (showArtwork) {
          Artwork(
            AndroidPodcast,
            Modifier.size(56.dp).clip(MaterialTheme.shapes.medium),
          )
        }
      }
      EpisodeActions(episode)
    }
  }
}

@Composable
private fun EpisodeActions(episode: Episode) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = {}) {
      Icon(
        Icons.Default.PlayCircle,
        contentDescription = "Play ${episode.title}",
        Modifier.size(36.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
    Text(
      episode.metadata,
      Modifier.padding(horizontal = 8.dp).weight(1f),
      style = MaterialTheme.typography.bodySmall,
      maxLines = 1,
    )
    IconButton(onClick = {}) {
      Icon(
        Icons.AutoMirrored.Filled.PlaylistAdd,
        contentDescription = "Add ${episode.title} to queue",
      )
    }
    IconButton(onClick = {}) {
      Icon(Icons.Default.MoreVert, contentDescription = "More episode options")
    }
  }
}

@Composable
private fun DestinationToolbar(modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(32.dp),
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
  ) {
    Row(
      Modifier.padding(6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(onClick = {}) {
        Icon(Icons.Default.VideoLibrary, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Library")
      }
      FloatingActionButton(
        onClick = {},
        containerColor = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
      ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Category, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Discover", style = MaterialTheme.typography.labelLarge)
        }
      }
    }
  }
}

@Composable
private fun PodcastDetailPane(modifier: Modifier = Modifier) {
  Scaffold(
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.surface,
    topBar = {
      Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Podcast details", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = {}) {
          Icon(Icons.Default.MoreVert, contentDescription = "Podcast options")
        }
      }
    },
  ) { contentPadding ->
    LazyColumn(
      Modifier.padding(contentPadding).fillMaxSize(),
      contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      item(key = "podcast-header") { PodcastHeader() }
      item(key = "description") {
        Text(
          "An official podcast from the Android team, featuring conversations about the latest platform, tools, libraries, and people behind Android development.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      item(key = "metadata") { AssistChip(onClick = {}, label = { Text("Technology") }) }
      item(key = "divider") { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
      item(key = "latest-title") {
        Text("Latest episodes", style = MaterialTheme.typography.titleLarge)
      }
      items(DetailEpisodes, key = { "detail-${it.key}" }) { episode ->
        EpisodeCard(episode, showArtwork = false, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

@Composable
private fun PodcastHeader() {
  Card(
    shape = MaterialTheme.shapes.large,
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      Modifier.padding(20.dp),
      horizontalArrangement = Arrangement.spacedBy(20.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Artwork(AndroidPodcast, Modifier.size(152.dp).clip(MaterialTheme.shapes.large))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          AndroidPodcast.title,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 3,
        )
        Text(
          AndroidPodcast.author,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = {}) {
          Icon(Icons.Default.Check, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text("Following")
        }
      }
    }
  }
}

@JsFun(
  """() => {
    globalThis.__uiBuilderReferenceJetcaster = { ready: true };
    globalThis.__uiBuilderReferenceJetcasterReady = true;
    document.documentElement.setAttribute('data-ui-builder-reference-jetcaster-ready', 'true');
  }"""
)
private external fun publishReady()
