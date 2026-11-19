package ee.schimke.composeai.uibuilder.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the design must sit still before its revision's stored result is read. */
private const val VALIDATION_READ_DEBOUNCE_MS = 800L

/**
 * The Issues panel's Validate section for a Watch Face Format design, reading [controller]. The
 * stored result for [revision] is re-read when the design settles, so the panel never claims an
 * older revision's verdict for the one on screen.
 */
@Composable
internal fun WatchFaceValidationSection(
  controller: WatchFaceValidationController,
  revision: Long,
  onOpenFinding: (WatchFaceValidationFinding) -> Unit,
  onSelectContributor: (WatchFaceMemoryContributor) -> Unit = {},
) {
  val validation by controller.validation.collectAsState()
  val installs by controller.installs.collectAsState()
  LaunchedEffect(controller) { controller.loadInstalls() }
  LaunchedEffect(controller, revision) {
    delay(VALIDATION_READ_DEBOUNCE_MS)
    controller.load(revision)
  }
  WatchFaceValidationPanel(
    validation = validation,
    installs = installs,
    revision = revision,
    onRun = { controller.start(revision) },
    onOpenFinding = onOpenFinding,
    onSelectContributor = onSelectContributor,
  )
}

/** [WatchFaceValidationSection] without its host: every state previews and tests draw. */
@Composable
internal fun WatchFaceValidationPanel(
  validation: WatchFaceValidationView,
  installs: WatchInstallsView,
  revision: Long,
  onRun: () -> Unit,
  onOpenFinding: (WatchFaceValidationFinding) -> Unit,
  modifier: Modifier = Modifier,
  onSelectContributor: (WatchFaceMemoryContributor) -> Unit = {},
) {
  // The first item of the Issues list, which scrolls as one; no inner scroll box of its own.
  Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
    Text("Watch face validation", style = MaterialTheme.typography.labelLarge)
    if (validation is WatchFaceValidationView.Unavailable) {
      UnavailableNote(validation.reason)
    } else {
      ValidationBody(validation, revision, onRun, onOpenFinding, onSelectContributor)
    }
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    Text("On your watch", style = MaterialTheme.typography.labelLarge)
    InstallsBody(installs, revision)
    HorizontalDivider(Modifier.padding(top = 10.dp))
  }
}

@Composable
private fun UnavailableNote(reason: String) {
  Text(
    reason,
    Modifier.padding(top = 4.dp),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun ValidationBody(
  validation: WatchFaceValidationView,
  revision: Long,
  onRun: () -> Unit,
  onOpenFinding: (WatchFaceValidationFinding) -> Unit,
  onSelectContributor: (WatchFaceMemoryContributor) -> Unit,
) {
  val running = validation is WatchFaceValidationView.Running
  val report = validation.report()
  Text(
    "Checks this revision's exported Watch Face Format against the schema, the XSD, the " +
      "watch's memory limits and the push validator, on the server.",
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
  )
  Row(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    OutlinedButton(
      onClick = onRun,
      enabled = !running && validation !is WatchFaceValidationView.Loading,
    ) {
      Text(
        when {
          running -> "Validating…"
          report?.revision == revision -> "Validate again"
          else -> "Validate"
        },
      )
    }
    Text(
      when (validation) {
        WatchFaceValidationView.Loading -> "Reading the last result…"
        WatchFaceValidationView.NotRun -> "Not run for revision $revision."
        is WatchFaceValidationView.Running -> "Validating revision $revision…"
        is WatchFaceValidationView.Ran -> summaryOf(validation.report)
        is WatchFaceValidationView.Failed -> "Could not validate."
        is WatchFaceValidationView.Unavailable -> ""
      },
      Modifier.weight(1f),
      color =
        when {
          validation is WatchFaceValidationView.Failed -> MaterialTheme.colorScheme.error
          validation is WatchFaceValidationView.Ran && !validation.report.passed ->
            MaterialTheme.colorScheme.error
          else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
      style = MaterialTheme.typography.bodySmall,
    )
  }
  if (validation is WatchFaceValidationView.Failed) {
    Text(
      validation.reason,
      Modifier.padding(top = 4.dp),
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )
  }
  report ?: return
  if (report.revision != revision) {
    Text(
      "Shown: revision ${report.revision}. The design is now at revision $revision; validate " +
        "again to check it.",
      Modifier.padding(top = 4.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
  versionLine(report)?.let {
    Text(
      it,
      Modifier.padding(top = 4.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
  // Failed checks open; the rest collapsed. Keyed on the report so a new run re-decides.
  val expanded =
    remember(report) {
      mutableStateMapOf<String, Boolean>().apply {
        report.checks.forEach { put(it.id, it.status == WATCH_FACE_CHECK_FAILED) }
      }
    }
  Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    report.checks.forEach { check ->
      CheckRow(
        check = check,
        expanded = expanded[check.id] == true,
        onToggle = { expanded[check.id] = expanded[check.id] != true },
        onOpenFinding = onOpenFinding,
      )
    }
  }
  val memoryCheck =
    report.checks.firstOrNull { it.id == WATCH_FACE_MEMORY_CHECK }
      ?: report.checks.firstOrNull { it.memory != null }
  memoryCheck
    ?.takeIf { it.memory != null || contributorsOf(it).isNotEmpty() }
    ?.let {
      MemoryBars(it.memory, contributorsOf(it), onSelectContributor, Modifier.padding(top = 8.dp))
    }
  PushValidatorLine(report)
}

private fun summaryOf(report: WatchFaceValidationReport): String {
  val failed = report.checks.count { it.status == WATCH_FACE_CHECK_FAILED }
  val skipped = report.checks.count { it.status == WATCH_FACE_CHECK_SKIPPED }
  val head = if (report.passed) "Passed" else "$failed check${if (failed == 1) "" else "s"} failed"
  return buildList {
      add("$head on revision ${report.revision}")
      if (skipped > 0) add("$skipped skipped")
    }
    .joinToString(" · ")
}

private fun versionLine(report: WatchFaceValidationReport): String? =
  listOfNotNull(
      report.wffVersion?.let { "WFF $it" },
      report.minimumWffVersion?.let { "needs WFF $it or newer" },
      report.schemaVersion?.let { "schema $it" },
      report.ranAtEpochMillis?.let { "ran ${formatEpochMillis(it)}" },
    )
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" · ")

@Composable
private fun statusColor(status: String): Color =
  when (status) {
    WATCH_FACE_CHECK_PASSED -> PASSED_GREEN
    WATCH_FACE_CHECK_FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
  }

private fun statusLabel(status: String): String =
  when (status) {
    WATCH_FACE_CHECK_PASSED -> "Passed"
    WATCH_FACE_CHECK_FAILED -> "Failed"
    WATCH_FACE_CHECK_SKIPPED -> "Skipped"
    else -> status
  }

/** Legible on both the light and dark inspector surfaces. */
private val PASSED_GREEN = Color(0xff2e9e5b)

/** A watch a revision or more behind: a nudge, not an error. */
private val BEHIND_AMBER = Color(0xffd08a1c)

@Composable
private fun CheckRow(
  check: WatchFaceValidationCheck,
  expanded: Boolean,
  onToggle: () -> Unit,
  onOpenFinding: (WatchFaceValidationFinding) -> Unit,
) {
  val expandable = check.findings.isNotEmpty() || !check.passedChecks.isNullOrEmpty()
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(6.dp))
        .then(
          if (expandable) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier,
        )
        .padding(vertical = 6.dp, horizontal = 4.dp)
        .semantics { contentDescription = "${check.title}: ${statusLabel(check.status)}" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor(check.status)))
      Text(check.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
      Text(
        buildString {
          append(statusLabel(check.status))
          if (check.findings.isNotEmpty()) append(" · ${check.findings.size}")
          if (expandable) append(if (expanded) "  ▴" else "  ▾")
        },
        color = statusColor(check.status),
        style = MaterialTheme.typography.labelSmall,
      )
    }
    if (expanded) {
      Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        check.findings.forEach { FindingRow(it, onOpenFinding) }
        check.passedChecks
          ?.takeIf { it.isNotEmpty() }
          ?.let {
            Text(
              "Passed: ${it.joinToString(", ")}",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
          }
      }
    }
  }
}

@Composable
private fun FindingRow(
  finding: WatchFaceValidationFinding,
  onOpenFinding: (WatchFaceValidationFinding) -> Unit,
) {
  val located = finding.line != null
  Column(
    Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .then(
        if (located) Modifier.clickable(role = Role.Button) { onOpenFinding(finding) }
        else Modifier,
      )
      .padding(vertical = 2.dp),
  ) {
    Text(
      finding.message,
      color =
        if (finding.severity == "warning") MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
    )
    val where =
      listOfNotNull(
          finding.severity.replaceFirstChar { it.uppercase() },
          finding.line?.let { line -> "line $line" + (finding.column?.let { ":$it" } ?: "") },
          finding.path,
        )
        .joinToString(" · ")
    Text(
      if (located) "$where · show in XML" else where,
      color =
        if (located) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
      fontFamily = FontFamily.Monospace,
      style = MaterialTheme.typography.labelSmall,
    )
    finding.suggestion?.let { SuggestionLine(it) }
  }
}

@Composable
private fun SuggestionLine(suggestion: String) {
  Text(
    "→ ${suggestion.replaceFirstChar { it.uppercase() }}",
    color = PASSED_GREEN,
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun MemoryBars(
  /** Null when Google's evaluator failed; the contributors are still listed. */
  memory: WatchFaceMemoryFootprint?,
  contributors: List<WatchFaceMemoryContributor>,
  onSelectContributor: (WatchFaceMemoryContributor) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (memory != null) {
      Text(
        "Memory · ${formatBytes(memory.totalBytes)} of assets",
        style = MaterialTheme.typography.labelMedium,
      )
      MemoryBar("Active", memory.maxActiveBytes, memory.activeLimitBytes)
      MemoryBar("Ambient", memory.maxAmbientBytes, memory.ambientLimitBytes)
    }
    val top = topContributors(contributors)
    if (top.isNotEmpty()) {
      Text(
        "Largest contributors",
        Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.labelMedium,
      )
      top.forEach { ContributorRow(it, memory?.activeLimitBytes ?: 0, onSelectContributor) }
    }
  }
}

/** How many contributors the section lists; the rest are the long tail nobody acts on first. */
private const val TOP_CONTRIBUTORS = 5

/** A contributor's weight against the active budget: what it costs while the face is awake. */
private fun WatchFaceMemoryContributor.cost(): Long = costOrNull() ?: fileBytes ?: 0

private fun WatchFaceMemoryContributor.costOrNull(): Long? =
  activeBytes ?: decodedBytes ?: ambientBytes

/** Missing resources first (they fail the package), then the largest. */
internal fun topContributors(
  contributors: List<WatchFaceMemoryContributor>,
): List<WatchFaceMemoryContributor> =
  contributors
    .sortedWith(
      compareByDescending<WatchFaceMemoryContributor> { it.missing }.thenByDescending { it.cost() },
    )
    .take(TOP_CONTRIBUTORS)

/** A memory check's contributors: on the check, else inside its footprint (older servers). */
internal fun contributorsOf(check: WatchFaceValidationCheck): List<WatchFaceMemoryContributor> =
  check.contributors.ifEmpty { check.memory?.contributors.orEmpty() }

/**
 * What to do about [this], worked out here when the server sends sizes: an asset stored larger than
 * the face ever draws it is downscaled to the drawn size.
 */
internal fun WatchFaceMemoryContributor.suggestion(): String? {
  suggestion?.let {
    return it
  }
  val w = width ?: return null
  val h = height ?: return null
  val drawnW = renderedMaxWidth ?: return null
  val drawnH = renderedMaxHeight ?: return null
  if (w <= drawnW && h <= drawnH) return null
  return "downscale to ${drawnW}x$drawnH"
}

@Composable
private fun ContributorRow(
  contributor: WatchFaceMemoryContributor,
  activeLimitBytes: Long,
  onSelectContributor: (WatchFaceMemoryContributor) -> Unit,
) {
  val cost = contributor.cost()
  val share =
    if (activeLimitBytes > 0 && contributor.costOrNull() != null)
      (cost * 100 / activeLimitBytes).toInt()
    else null
  Column(
    Modifier.fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .clickable(role = Role.Button) { onSelectContributor(contributor) }
      .padding(vertical = 2.dp)
      .semantics { contentDescription = "Select ${contributor.resource}" },
  ) {
    Row(Modifier.fillMaxWidth()) {
      Text(
        contributor.resource,
        Modifier.weight(1f),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        if (contributor.missing) "Missing"
        else formatBytes(cost) + (share?.let { " · $it% of active" } ?: ""),
        color =
          if (contributor.missing) MaterialTheme.colorScheme.error
          else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    val facts =
      listOfNotNull(
        contributor.kind,
        contributor.format,
        if (contributor.width != null && contributor.height != null)
          "${contributor.width}x${contributor.height}"
        else null,
        if (contributor.renderedMaxWidth != null && contributor.renderedMaxHeight != null)
          "drawn at ${contributor.renderedMaxWidth}x${contributor.renderedMaxHeight}"
        else null,
      )
    if (facts.isNotEmpty()) {
      Text(
        facts.joinToString(" · "),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    contributor.suggestion()?.let { SuggestionLine(it) }
  }
}

@Composable
private fun MemoryBar(label: String, used: Long, limit: Long) {
  val over = limit in 1..<used
  val fraction = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
  val barColor = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
  Column(
    Modifier.fillMaxWidth().semantics {
      contentDescription =
        "$label memory ${formatBytes(used)} of ${formatBytes(limit)}" + if (over) ", over" else ""
    },
  ) {
    Row(Modifier.fillMaxWidth()) {
      Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
      Text(
        "${formatBytes(used)} / ${if (limit > 0) formatBytes(limit) else "no limit"}" +
          if (over) " · over by ${formatBytes(used - limit)}" else "",
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Box(
      Modifier.padding(top = 2.dp)
        .fillMaxWidth()
        .height(6.dp)
        .clip(RoundedCornerShape(3.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(barColor))
    }
  }
}

@Composable
private fun PushValidatorLine(report: WatchFaceValidationReport) {
  val push = report.checks.firstOrNull { it.id == WATCH_FACE_PUSH_VALIDATOR_CHECK } ?: return
  val issued =
    when {
      push.status == WATCH_FACE_CHECK_SKIPPED -> "not asked"
      push.tokenIssued == true -> "yes"
      else -> "no"
    }
  Text(
    "Push validator: token issued — $issued",
    Modifier.padding(top = 8.dp),
    color = if (push.tokenIssued == true) PASSED_GREEN else MaterialTheme.colorScheme.onSurface,
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun InstallsBody(installs: WatchInstallsView, revision: Long) {
  when (installs) {
    WatchInstallsView.Loading -> UnavailableNote("Reading your watches…")
    is WatchInstallsView.Unavailable -> UnavailableNote(installs.reason)
    is WatchInstallsView.Listed ->
      if (installs.installs.isEmpty()) {
        UnavailableNote("Not pushed to a watch yet.")
      } else {
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          installs.installs.forEach { InstallRow(it, revision) }
        }
      }
  }
}

@Composable
private fun InstallRow(install: WatchInstall, revision: Long) {
  val failed = install.result == "failed"
  val behind = !failed && install.revisionPublished < revision
  val activationNote =
    when (install.activation) {
      "activated" -> "active on the watch"
      "manual" -> "pick it on the watch to activate"
      "failed" -> "did not activate"
      else -> "not activated"
    }
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(8.dp)
          .clip(CircleShape)
          .background(
            when {
              failed -> MaterialTheme.colorScheme.error
              behind -> BEHIND_AMBER
              else -> PASSED_GREEN
            },
          ),
      )
      Text(
        install.deviceName.ifBlank { install.deviceModel ?: "Watch" },
        Modifier.padding(start = 8.dp).weight(1f),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        when {
          failed -> "Install failed"
          behind -> "Behind"
          else -> "Up to date"
        },
        color =
          when {
            failed -> MaterialTheme.colorScheme.error
            behind -> BEHIND_AMBER
            else -> PASSED_GREEN
          },
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Text(
      listOfNotNull(
          install.deviceModel?.takeIf { install.deviceName.isNotBlank() },
          install.wearOsApi?.let { "API $it" },
          install.version?.let { "version $it" },
        )
        .joinToString(" · "),
      Modifier.padding(start = 16.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
    Text(
      buildList {
          add(
            when {
              failed -> "Revision ${install.revisionPublished} failed to install"
              behind -> "Revision ${install.revisionPublished}; the design is at $revision"
              else -> "Revision ${install.revisionPublished}, ${install.result}"
            },
          )
          if (!failed) add(activationNote)
          install.errorKind?.let { add(it) }
        }
        .joinToString(" · "),
      Modifier.padding(start = 16.dp),
      color =
        if (failed || install.activation == "failed") MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

/** Bytes as the watch's limits are quoted: binary MB above one, else KB. */
internal fun formatBytes(bytes: Long): String {
  val mb = bytes / (1024.0 * 1024.0)
  if (mb >= 1.0) return "${oneDecimal(mb)} MB"
  val kb = bytes / 1024.0
  if (kb >= 1.0) return "${oneDecimal(kb)} KB"
  return "$bytes B"
}

private fun oneDecimal(value: Double): String {
  val tenths = kotlin.math.round(value * 10).toLong()
  return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** `2026-11-19 09:42 UTC`, the minute a run finished, from the report's epoch millis. */
@OptIn(kotlin.time.ExperimentalTime::class)
internal fun formatEpochMillis(epochMillis: Long): String =
  kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toString().let {
    // ISO-8601 in UTC: `2026-11-19T09:42:00Z` (seconds and fractions vary); keep to the minute.
    it.take(16).replace('T', ' ') + " UTC"
  }
