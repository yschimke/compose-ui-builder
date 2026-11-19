package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * "Test on your watch": the watch-face editor's main next step. It publishes the current revision
 * as a push APK and sends the person to the Faces app on their watch. The Native pane's Android
 * renderer stays available, but as the slower, secondary check.
 */
@Composable
internal fun TestOnWatchSection(
  controller: WatchFacePublishController,
  revision: Long,
  installs: WatchInstallsView,
  /** Re-reads "On your watch" once a publish lands, so the rows move as watches catch up. */
  onPublished: () -> Unit,
) {
  val view by controller.view.collectAsState()
  LaunchedEffect(controller) { controller.load() }
  val published = (view as? WatchFacePublishView.Ready)?.publication?.takeIf { it.published }
  LaunchedEffect(published?.version, published?.revision) { if (published != null) onPublished() }
  if (view == WatchFacePublishView.Hidden) return
  TestOnWatchPanel(
    view = view,
    revision = revision,
    watchesLinked = (installs as? WatchInstallsView.Listed)?.installs?.isNotEmpty(),
    onPublish = controller::publish,
    onUnpublish = controller::unpublish,
  )
}

/** [TestOnWatchSection] without its host: every state previews and tests draw. */
@Composable
internal fun TestOnWatchPanel(
  view: WatchFacePublishView,
  revision: Long,
  /** Whether a watch has installed this design before; null when that is not known. */
  watchesLinked: Boolean?,
  onPublish: (String) -> Unit,
  onUnpublish: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Set apart from the checks around it: this is the step the section leads to.
  Surface(
    modifier.fillMaxWidth().padding(vertical = 4.dp),
    shape = RoundedCornerShape(12.dp),
    // Not secondaryContainer: that is a selected chip's colour, and the chips would vanish into it.
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        "Test on your watch",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      when (view) {
        WatchFacePublishView.Hidden -> Unit
        WatchFacePublishView.Loading -> Note("Checking whether this design can go to a watch…")
        is WatchFacePublishView.NotAllowed -> Note(view.reason)
        is WatchFacePublishView.Ready ->
          ReadyBody(view, revision, watchesLinked, onPublish, onUnpublish)
      }
      Note("The Native pane's Android renderer is still there for a slower check in the browser.")
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadyBody(
  view: WatchFacePublishView.Ready,
  revision: Long,
  watchesLinked: Boolean?,
  onPublish: (String) -> Unit,
  onUnpublish: () -> Unit,
) {
  val publication = view.publication
  Text(publicationLine(publication), style = MaterialTheme.typography.bodyMedium)
  publication.build?.let { WatchBuildChip(it, publication.version) }
  val pinned = publication.revision
  if (publication.published && pinned != null && pinned < revision) {
    Text(
      "Watches have revision $pinned; the design is at $revision. Publish again to send your edits.",
      color = BEHIND_TEXT,
      style = MaterialTheme.typography.bodySmall,
    )
  }
  apkLine(publication)?.let { Note(it) }
  if (publication.refusal.isNotEmpty()) {
    Note("The packager refused the latest build; watches keep the version they have.")
    Diagnostics(publication.refusal)
  }
  var visibility by
    remember(publication.requestedVisibility) {
      mutableStateOf(publication.requestedVisibility ?: WATCH_FACE_VISIBILITY_PRIVATE)
    }
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    FilterChip(
      selected = visibility == WATCH_FACE_VISIBILITY_PRIVATE,
      onClick = { visibility = WATCH_FACE_VISIBILITY_PRIVATE },
      label = { Text("My watches") },
      enabled = !view.busy,
    )
    FilterChip(
      selected = visibility == WATCH_FACE_VISIBILITY_PUBLIC,
      onClick = { visibility = WATCH_FACE_VISIBILITY_PUBLIC },
      label = { Text("Every watch") },
      enabled = !view.busy,
    )
  }
  // Wraps rather than squeezing "Unpublish" beside a long publish label.
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    Button(onClick = { onPublish(visibility) }, enabled = !view.busy) {
      Text(
        when {
          view.busy -> "Publishing…"
          !publication.published -> "Publish to watch"
          pinned == revision && publication.requestedVisibility == visibility -> "Publish again"
          else -> "Publish revision $revision"
        },
      )
    }
    if (publication.published && !publication.automatic) {
      TextButton(onClick = onUnpublish, enabled = !view.busy) { Text("Unpublish") }
    }
  }
  view.failure?.let { failure ->
    Text(
      failure.message,
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )
    if (failure.diagnostics.isNotEmpty()) Diagnostics(failure.diagnostics)
  }
  NextSteps(publication, watchesLinked)
}

private fun publicationLine(publication: WatchFacePublication): String =
  if (!publication.published && !publication.automatic) "Not on any watch yet."
  else
    buildString {
      append("Version ${publication.version ?: "?"}")
      publication.revision?.let { append(" (revision $it)") }
      append(
        when (publication.visibility) {
          WATCH_FACE_VISIBILITY_PUBLIC -> " is on every watch"
          WATCH_FACE_VISIBILITY_PRIVATE -> " is on your linked watches"
          else -> " is published"
        },
      )
      if (publication.automatic) append(", listed because the design is public")
      append(".")
    }

private fun apkLine(publication: WatchFacePublication): String? =
  when (publication.apk?.status) {
    "pending" -> "Building the APK…"
    "unavailable" ->
      publication.packaging?.reason?.let { "This server cannot build APKs: $it" }
        ?: "This server cannot build APKs right now."
    "failed" -> "The last APK build failed."
    else -> null
  }

/** What to do on the watch, with the links that get a person there. */
@Composable
private fun NextSteps(publication: WatchFacePublication, watchesLinked: Boolean?) {
  val uriHandler = LocalUriHandler.current
  if (!publication.published && !publication.automatic) {
    Note("Publish, then open $FACES_APP_NAME on your watch to install it.")
    return
  }
  if (watchesLinked == true) {
    Text(
      "Open the $FACES_APP_NAME app on your watch to install version ${publication.version ?: ""}."
        .trimEnd(),
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
    )
    return
  }
  Text(
    "Open the $FACES_APP_NAME app on your watch",
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Medium,
  )
  Step("1.", "Install $FACES_APP_NAME on the watch (Wear OS 6).", "Get it on Google Play") {
    uriHandler.openUri(FACES_APP_PLAY_URL)
  }
  Step(
    "2.",
    "Open it. It shows a code; approve it on your phone, signed in here.",
    "Link a watch",
  ) {
    uriHandler.openUri(WATCH_LINK_PATH)
  }
  Step("3.", "Pick this face in $FACES_APP_NAME to install it.", null) {}
}

@Composable
private fun Step(number: String, text: String, link: String?, onLink: () -> Unit) {
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(number, style = MaterialTheme.typography.bodySmall)
    Column {
      Text(text, style = MaterialTheme.typography.bodySmall)
      if (link != null) {
        Text(
          link,
          Modifier.clickable(role = Role.Button, onClick = onLink),
          color = MaterialTheme.colorScheme.primary,
          textDecoration = TextDecoration.Underline,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun Diagnostics(lines: List<String>) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    lines.forEach {
      Text(
        it,
        color = MaterialTheme.colorScheme.error,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
private fun Note(text: String) {
  Text(text, style = MaterialTheme.typography.bodySmall)
}

/** Amber that reads on the secondary container in both schemes: a nudge, not an error. */
private val BEHIND_TEXT = Color(0xffb26a00)

/**
 * The background build in a few words, as a chip: "Builds in 8 min", "Queued, 1 ahead",
 * "Packaging…", "On your watch: v7", "Refused: …". Shown in the card and, persistently, in the
 * status bar under the canvas, where [onClick] opens the Issues panel.
 */
@Composable
internal fun WatchBuildChip(
  build: WatchFacePublicationBuild,
  publishedVersion: Long?,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null,
) {
  // Re-reads the clock while a countdown is showing, so "Builds in 8 min" counts down.
  val clock = LocalWatchBuildClock.current
  val now by
    produceState(clock(), build, clock) {
      while (build.state == BUILD_PENDING || build.state == BUILD_FAILED) {
        delay(30_000)
        value = clock()
      }
    }
  val text = buildChipText(build, now, publishedVersion) ?: return
  val (container, content) =
    when {
      build.isTrouble() ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
      build.state == BUILD_BUILT ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
      else ->
        MaterialTheme.colorScheme.secondaryContainer to
          MaterialTheme.colorScheme.onSecondaryContainer
    }
  Surface(
    modifier
      .then(
        if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
        else Modifier,
      )
      .semantics { contentDescription = "Watch build: $text" },
    shape = RoundedCornerShape(10.dp),
    color = container,
    contentColor = content,
  ) {
    Text(
      text,
      Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
      style = MaterialTheme.typography.labelSmall,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** [WatchBuildChip] for the open design, when it has a build to talk about; else nothing. */
@Composable
internal fun WatchBuildStatusChip(onClick: () -> Unit) {
  val controller = LocalWatchFacePublish.current ?: return
  val view by controller.view.collectAsState()
  val publication = (view as? WatchFacePublishView.Ready)?.publication ?: return
  val build = publication.build ?: return
  WatchBuildChip(build, publication.version, Modifier.widthIn(max = 280.dp), onClick)
}

/** The clock the chip counts down against; previews hold it still so their pictures are stable. */
internal val LocalWatchBuildClock = staticCompositionLocalOf<() -> Long> { ::epochMillisNow }
