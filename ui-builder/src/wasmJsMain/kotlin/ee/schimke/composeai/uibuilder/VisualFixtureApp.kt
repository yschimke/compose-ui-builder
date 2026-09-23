@file:OptIn(
  androidx.compose.material3.ExperimentalMaterial3Api::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
  kotlin.js.ExperimentalWasmJsInterop::class,
)

package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalog
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.validateCapabilities
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The capture fixtures the browser harness drives by `?mode=`: the Jetcaster and Confetti replays,
 * the interactive editor over a fixture, and the hand-written Confetti oracle they are compared to.
 *
 * They ship in the same bundle as the live editor on purpose — the harness captures the production
 * archive, so a fixture built separately would prove nothing about what users load — but they are
 * not the application, and they lived in `Main.kt` beside it. This file is where they live now, so
 * the entry point reads as the entry point.
 */
@Composable
internal fun VisualFixtureApp(mode: String) {
  if (mode == "reference") {
    ConfettiReference()
    LaunchedEffect(Unit) { markReady() }
    return
  }

  var document by remember { mutableStateOf<UiBuilderDocument?>(null) }
  var catalog by remember { mutableStateOf<CapabilityCatalog?>(null) }
  LaunchedEffect(Unit) {
    val isJetcaster = mode.startsWith("jetcaster-") || mode.startsWith("interactive-editor")
    val fixtureName =
      if (isJetcaster) "jetcaster-discover-operations-v1.json"
      else "confetti-schedule-operations-v1.json"
    val fixture = Json.parseToJsonElement(fetchText(fixtureName)).jsonObject
    val replayed = UiBuilderReducer.replay(fixture).document
    if (isJetcaster) {
      val catalogSource = fetchText("m3-catalog-capabilities-v1.json")
      val validation =
        validateCapabilities(
          replayed,
          catalogSource,
        )
      require(validation.structurallyValid) {
        validation.issues.joinToString(prefix = "invalid Jetcaster design: ") { it.message }
      }
      publishCapabilityDiagnostics(
        validation.structurallyValid,
        validation.wasmRenderable,
        validation.plannedOrUnsupported
          .map { it.componentId }
          .distinct()
          .sorted()
          .joinToString(","),
      )
      catalog = CapabilityCatalogParser.parse(catalogSource)
    }
    document = replayed
  }
  document?.let {
    if (mode.startsWith("interactive-editor")) {
      catalog?.let { loadedCatalog ->
        UiBuilderEditor(
          document = it,
          catalog = loadedCatalog,
          onStateChanged = ::publishEditorState,
          onCanvasMetrics = ::publishEditorCanvasMetrics,
          onCanvasBoundsChanged = ::publishEditorCanvasBounds,
          onDropTargetChanged = ::publishEditorDropTarget,
          onInspectionSnapshot = { snapshot ->
            publishInspection(inspectionJson.encodeToString(snapshot))
          },
          showSelectionOverlay = mode != "interactive-editor-clean",
          // The clean lane exists to prove the editor draws the design the same as the harness
          // does, pixel for pixel. Framing it would compare a resample, so that one mode opens
          // pinned at 1:1 while every editing surface opens framed.
          initialCanvasZoom = if (mode == "interactive-editor-clean") 1f else null,
          onHelp = ::openUiBuilderGuide,
        )
      }
    } else {
      UiBuilderSurface(
        it,
        editorOverlay = mode == "editor" || mode == "jetcaster-editor",
        selectedNodeId = if (mode == "jetcaster-editor") "discover-grid" else null,
        onInspectionSnapshot = { snapshot ->
          publishInspection(inspectionJson.encodeToString(snapshot))
        },
      )
    }
    LaunchedEffect(it.revision) { markReady() }
  }
}

/** Independent developer-authored oracle for the pinned compact Confetti Schedule screen. */
@Composable
private fun ConfettiReference() {
  var selectedTrack by remember { mutableStateOf<String?>(null) }
  MaterialTheme {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      topBar = {
        CenterAlignedTopAppBar(
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = Color.Transparent,
              scrolledContainerColor = Color.Transparent,
            ),
          title = {
            Text(
              "KotlinConf 2023",
              Modifier.padding(horizontal = 8.dp),
              style = MaterialTheme.typography.titleLarge,
              maxLines = 2,
              textAlign = TextAlign.Center,
            )
          },
        )
      },
    ) { contentPadding ->
      Column(Modifier.padding(contentPadding).fillMaxSize()) {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
          item {
            FilterChip(
              selected = selectedTrack == null,
              onClick = { selectedTrack = null },
              label = { Text("All") },
            )
          }
          item { TrackChip("droidCon", Color(0xFF00FF4F), selectedTrack) { selectedTrack = it } }
          item { TrackChip("swiftCon", Color(0xFFFF375F), selectedTrack) { selectedTrack = it } }
          item { TrackChip("flutterCon", Color(0xFF42A5F5), selectedTrack) { selectedTrack = it } }
          item { TrackChip("reactCon", Color(0xFF61DAFB), selectedTrack) { selectedTrack = it } }
        }
        PrimaryTabRow(selectedTabIndex = 0, modifier = Modifier.fillMaxWidth()) {
          Tab(
            selected = true,
            onClick = {},
            text = {
              Text(
                "Thu 13 Apr",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
              )
            },
          )
          Tab(
            selected = false,
            onClick = {},
            text = { Text("Fri 14 Apr", style = MaterialTheme.typography.titleSmall) },
          )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
          item { TimeHeader("14:00") }
          item {
            ScheduleListItem(
              accent = Color(0xFF00C853),
              headline = "Confetti: building a Kotlin Multiplatform conference app",
              speaker = "John O'Reilly, Martin Bonnin",
              metadata = "Effectenbeurszaal  ·  Kotlin  ·  Multiplatform",
              bookmarked = true,
            )
          }
          item { HorizontalDivider(Modifier.padding(start = 16.dp)) }
          item { TimeHeader("14:50") }
          item {
            ScheduleListItem(
              accent = Color(0xFF42A5F5),
              headline = "Compose tips in 5 minutes",
              speaker = "Sebastian Aigner",
              metadata = "14:50–14:55  ·  Effectenbeurszaal  ·  Lightning",
              bookmarked = false,
            )
          }
          item { HorizontalDivider(Modifier.padding(start = 16.dp)) }
          item { TimeHeader("15:00") }
          item {
            Surface(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              shape = RoundedCornerShape(12.dp),
            ) {
              Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
              ) {
                Icon(
                  Icons.Filled.Coffee,
                  contentDescription = null,
                  Modifier.size(24.dp),
                  tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                  Text(
                    "Coffee Break",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    "Foyer · Level 1",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TrackChip(
  name: String,
  color: Color,
  selectedTrack: String?,
  onSelectedTrack: (String?) -> Unit,
) {
  FilterChip(
    selected = selectedTrack == name,
    onClick = { onSelectedTrack(if (selectedTrack == name) null else name) },
    label = { Text(name) },
    leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(color)) },
  )
}

@Composable
private fun TimeHeader(label: String) {
  Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
    Row(
      Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
      Icon(
        Icons.Filled.AccessTime,
        contentDescription = null,
        Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun ScheduleListItem(
  accent: Color,
  headline: String,
  speaker: String,
  metadata: String,
  bookmarked: Boolean,
) {
  ListItem(
    modifier =
      Modifier.fillMaxWidth().drawBehind {
        drawRect(accent, size = Size(3.dp.toPx(), size.height))
      },
    headlineContent = {
      Text(
        headline,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
      )
    },
    supportingContent = {
      Column {
        Text(
          speaker,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          metadata,
          Modifier.padding(top = 6.dp),
          style = MaterialTheme.typography.labelSmall,
        )
      }
    },
    trailingContent = {
      Icon(
        imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint =
          if (bookmarked) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
  )
}

/**
 * The Remote Compose documents the *serving* catalog of the same name publishes.
 *
 * The document's catalog pin names the capability adapter and the published serving catalog, so the
 * palette needs no second piece of configuration to find its content. A box serving one without the
 * other simply gets an empty palette.
 *
 * A failure is not fatal, for the same reason [loadDevicePresets]'s is not: the builder without a
 * Remote Compose palette is where it was before this existed, while a builder that refuses to open
 * because a catalog listing 404'd is worse than one that opens with one panel missing.
 */
