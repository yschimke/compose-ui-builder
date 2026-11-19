package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A stored revision's watch-face validation, as
 * `/api/ui-builder/v1/designs/{id}/watchface-validation` answers it: the WFF schema and XSD checks,
 * the memory footprint against the watch's limits, and the push validator. Run on the server
 * against the exported WFF of [revision], never on this browser's copy.
 */
@Serializable
data class WatchFaceValidationReport(
  val designId: String = "",
  val revision: Long = 0,
  val digest: String = "",
  val ranAt: String = "",
  val schemaVersion: String? = null,
  val wffVersion: String? = null,
  val minimumWffVersion: String? = null,
  val passed: Boolean = false,
  val checks: List<WatchFaceValidationCheck> = emptyList(),
)

@Serializable
data class WatchFaceValidationCheck(
  /** `wff-schema`, `wff-xsd`, `memory-footprint` or `push-validator`; others are shown as sent. */
  val id: String,
  val title: String = id,
  /** `passed`, `failed` or `skipped`. */
  val status: String,
  val findings: List<WatchFaceValidationFinding> = emptyList(),
  val memory: WatchFaceMemoryFootprint? = null,
  val passedChecks: List<String>? = null,
  val tokenIssued: Boolean? = null,
)

@Serializable
data class WatchFaceValidationFinding(
  /** `error` or `warning`. */
  val severity: String = "error",
  val message: String,
  val line: Int? = null,
  val column: Int? = null,
  val path: String? = null,
)

@Serializable
data class WatchFaceMemoryFootprint(
  val totalBytes: Long = 0,
  val maxActiveBytes: Long = 0,
  val maxAmbientBytes: Long = 0,
  val activeLimitBytes: Long = 0,
  val ambientLimitBytes: Long = 0,
)

/** `GET /api/ui-builder/v1/designs/{id}/watch-installs`: where the owner's watches stand. */
@Serializable data class WatchInstalls(val installs: List<WatchInstall> = emptyList())

@Serializable
data class WatchInstall(
  val deviceName: String = "",
  val deviceModel: String? = null,
  val wearOsApi: Int? = null,
  val version: String? = null,
  val revisionPublished: Long = 0,
  /** `installed`, `updated`, `replaced` or `failed`. */
  val result: String = "installed",
  /** `activated`, `not_requested`, `manual` or `failed`. */
  val activation: String = "not_requested",
  val errorKind: String? = null,
  val at: String = "",
)

const val WATCH_FACE_CHECK_PASSED = "passed"
const val WATCH_FACE_CHECK_FAILED = "failed"
const val WATCH_FACE_CHECK_SKIPPED = "skipped"
const val WATCH_FACE_PUSH_VALIDATOR_CHECK = "push-validator"
const val WATCH_FACE_MEMORY_CHECK = "memory-footprint"

/** "On your watch" where validation itself cannot run: the reason above covers both. */
const val WATCH_INSTALLS_UNAVAILABLE =
  "Pushing to a watch needs the same: a design stored on the server."

/**
 * Lenient on purpose: a version may arrive as `4` or `"4"`, a time as millis or ISO text, and the
 * server may add fields before this editor knows them.
 */
val WatchFaceValidationJson = Json {
  ignoreUnknownKeys = true
  isLenient = true
  coerceInputValues = true
}

/** What a host answered for one validation read or run. */
sealed interface WatchFaceValidationAnswer {
  data class Ran(val report: WatchFaceValidationReport) : WatchFaceValidationAnswer

  /** `GET` answered 404: nobody has validated this revision. */
  data object NeverRun : WatchFaceValidationAnswer

  /** The host's own sentence for why not (signed out, not an editor, runtime missing, ...). */
  data class Refused(val reason: String) : WatchFaceValidationAnswer
}

/** What a host answered for the owner's watch installs. */
sealed interface WatchInstallsAnswer {
  data class Listed(val installs: List<WatchInstall>) : WatchInstallsAnswer

  data class Refused(val reason: String) : WatchInstallsAnswer
}

/**
 * The server half of the Validate section. Provided by the browser app for server designs of a
 * Watch Face Format catalog; a local design's host only says why it cannot.
 */
interface WatchFaceValidationHost {
  /** Why validation cannot run for this design here, or null when it can. */
  val unavailableReason: String?

  suspend fun latest(revision: Long): WatchFaceValidationAnswer

  suspend fun run(revision: Long): WatchFaceValidationAnswer

  suspend fun installs(): WatchInstallsAnswer
}

/** The validation half of the section, as it draws. */
sealed interface WatchFaceValidationView {
  data class Unavailable(val reason: String) : WatchFaceValidationView

  data object Loading : WatchFaceValidationView

  data object NotRun : WatchFaceValidationView

  /** A run in flight; [previous] stays drawn under the progress line so the panel does not jump. */
  data class Running(val previous: WatchFaceValidationReport? = null) : WatchFaceValidationView

  data class Ran(val report: WatchFaceValidationReport) : WatchFaceValidationView

  data class Failed(val reason: String, val previous: WatchFaceValidationReport? = null) :
    WatchFaceValidationView
}

/** The "On your watch" half, as it draws. */
sealed interface WatchInstallsView {
  data object Loading : WatchInstallsView

  data class Unavailable(val reason: String) : WatchInstallsView

  data class Listed(val installs: List<WatchInstall>) : WatchInstallsView
}

/** Holds the section's state across recompositions; one per open design. */
class WatchFaceValidationController(private val host: WatchFaceValidationHost) {
  private val _validation =
    MutableStateFlow<WatchFaceValidationView>(
      host.unavailableReason?.let(WatchFaceValidationView::Unavailable)
        ?: WatchFaceValidationView.Loading,
    )
  val validation: StateFlow<WatchFaceValidationView> = _validation.asStateFlow()

  private val _installs =
    MutableStateFlow<WatchInstallsView>(
      // The reason is already said once, above; this half only says it shares it.
      if (host.unavailableReason != null) WatchInstallsView.Unavailable(WATCH_INSTALLS_UNAVAILABLE)
      else WatchInstallsView.Loading,
    )
  val installs: StateFlow<WatchInstallsView> = _installs.asStateFlow()

  /** Reads the stored result for [revision], unless a run is in flight. */
  suspend fun load(revision: Long) {
    if (host.unavailableReason != null) return
    if (_validation.value is WatchFaceValidationView.Running) return
    val answer = runCatching { host.latest(revision) }
    // A run that started while the read was out wins: its answer is the newer one.
    if (_validation.value is WatchFaceValidationView.Running) return
    _validation.value =
      answer.fold(
        onSuccess = { it.toView(previous = null) },
        onFailure = {
          WatchFaceValidationView.Failed(it.message ?: "the validation result could not be read")
        },
      )
  }

  suspend fun run(revision: Long) {
    if (host.unavailableReason != null) return
    val previous = _validation.value.report()
    _validation.value = WatchFaceValidationView.Running(previous)
    _validation.value =
      runCatching { host.run(revision) }
        .fold(
          onSuccess = { it.toView(previous) },
          onFailure = {
            WatchFaceValidationView.Failed(it.message ?: "the validation request failed", previous)
          },
        )
  }

  suspend fun loadInstalls() {
    if (host.unavailableReason != null) return
    _installs.value =
      runCatching { host.installs() }
        .fold(
          onSuccess = {
            when (it) {
              is WatchInstallsAnswer.Listed -> WatchInstallsView.Listed(it.installs)
              is WatchInstallsAnswer.Refused -> WatchInstallsView.Unavailable(it.reason)
            }
          },
          onFailure = {
            WatchInstallsView.Unavailable(it.message ?: "the watch installs could not be read")
          },
        )
  }

  private fun WatchFaceValidationAnswer.toView(
    previous: WatchFaceValidationReport?,
  ): WatchFaceValidationView =
    when (this) {
      is WatchFaceValidationAnswer.Ran -> WatchFaceValidationView.Ran(report)
      WatchFaceValidationAnswer.NeverRun -> WatchFaceValidationView.NotRun
      is WatchFaceValidationAnswer.Refused -> WatchFaceValidationView.Failed(reason, previous)
    }
}

/** The report a view still shows, if any. */
fun WatchFaceValidationView.report(): WatchFaceValidationReport? =
  when (this) {
    is WatchFaceValidationView.Ran -> report
    is WatchFaceValidationView.Running -> previous
    is WatchFaceValidationView.Failed -> previous
    else -> null
  }

/**
 * The Validate section, where the host offers one: provided by the browser app around the editor
 * for a Watch Face Format design, null everywhere else, where the Issues panel leaves it out.
 */
val LocalWatchFaceValidation = staticCompositionLocalOf<WatchFaceValidationController?> { null }
