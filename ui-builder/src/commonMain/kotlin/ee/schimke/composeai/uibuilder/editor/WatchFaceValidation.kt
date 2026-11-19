package ee.schimke.composeai.uibuilder.editor

import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A stored revision's watch-face validation, as
 * `/api/ui-builder/v1/designs/{id}/watchface-validation` answers it: the WFF schema and XSD checks,
 * the memory footprint against the watch's limits, and the push validator. Run on the server
 * against the exported WFF of [revision], never on this browser's copy.
 */
data class WatchFaceValidationReport(
  val designId: String = "",
  val revision: Long = 0,
  val digest: String = "",
  val ranAtEpochMillis: Long? = null,
  val schemaVersion: String? = null,
  val wffVersion: String? = null,
  val minimumWffVersion: String? = null,
  val passed: Boolean = false,
  val checks: List<WatchFaceValidationCheck> = emptyList(),
)

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
  /**
   * What the memory footprint is made of, on the `memory-footprint` check beside [memory]: the
   * server lists it even when Google's evaluator fails, so it can arrive without [memory].
   */
  val contributors: List<WatchFaceMemoryContributor> = emptyList(),
)

data class WatchFaceValidationFinding(
  /** `error` or `warning`. */
  val severity: String = "error",
  val message: String,
  val line: Int? = null,
  val column: Int? = null,
  val path: String? = null,
  /** What to do about it, when the validator knows (`downscale to 450x450`). */
  val suggestion: String? = null,
)

data class WatchFaceMemoryFootprint(
  val totalBytes: Long = 0,
  val maxActiveBytes: Long = 0,
  val maxAmbientBytes: Long = 0,
  val activeLimitBytes: Long = 0,
  val ambientLimitBytes: Long = 0,
  /** Older placement of [WatchFaceValidationCheck.contributors]; read only when that is empty. */
  val contributors: List<WatchFaceMemoryContributor> = emptyList(),
)

/** One asset's share of a memory footprint. Every field but [resource] is optional. */
data class WatchFaceMemoryContributor(
  /** The resource as the exported WFF names it; matched against the design to select it. */
  val resource: String,
  /** `image`, `font` or `other`. */
  val kind: String? = null,
  val width: Int? = null,
  val height: Int? = null,
  /** Absent when the resource is [missing]. */
  val format: String? = null,
  /** Its size in the package. */
  val fileBytes: Long? = null,
  val decodedBytes: Long? = null,
  val activeBytes: Long? = null,
  val ambientBytes: Long? = null,
  /** The largest size the face ever draws it at; past it, the asset carries pixels nobody sees. */
  val renderedMaxWidth: Int? = null,
  val renderedMaxHeight: Int? = null,
  /** The design names it but the package has no such file. */
  val missing: Boolean = false,
  /** The server's own advice, preferred over the downscale this editor works out. */
  val suggestion: String? = null,
)

/** `GET /api/ui-builder/v1/designs/{id}/watch-installs`: where the owner's watches stand. */
data class WatchInstalls(val installs: List<WatchInstall> = emptyList())

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
  val atEpochMillis: Long? = null,
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
 * The report as `watchface-validation` sends it. Read by hand rather than through generated
 * serializers, which for these models cost the Wasm build more than its size budget allows; and
 * lenient on purpose: a version may arrive as `4` or `"4"`, a field may be missing or null, and the
 * server may add fields before this editor knows them.
 */
fun parseWatchFaceValidationReport(text: String): WatchFaceValidationReport =
  Json.parseToJsonElement(text).jsonObject.let { o ->
    WatchFaceValidationReport(
      designId = o.fieldText("designId").orEmpty(),
      revision = o.fieldLong("revision") ?: 0,
      digest = o.fieldText("digest").orEmpty(),
      ranAtEpochMillis = o.fieldLong("ranAtEpochMillis"),
      schemaVersion = o.fieldText("schemaVersion"),
      wffVersion = o.fieldText("wffVersion"),
      minimumWffVersion = o.fieldText("minimumWffVersion"),
      passed = o.fieldBool("passed") ?: false,
      checks = o.fieldObjects("checks").map { it.toCheck() },
    )
  }

/** `watch-installs`' rows, read as leniently as [parseWatchFaceValidationReport]. */
fun parseWatchInstalls(text: String): List<WatchInstall> =
  Json.parseToJsonElement(text).jsonObject.fieldObjects("installs").map { o ->
    WatchInstall(
      deviceName = o.fieldText("deviceName").orEmpty(),
      deviceModel = o.fieldText("deviceModel"),
      wearOsApi = o.fieldInt("wearOsApi"),
      version = o.fieldText("version"),
      revisionPublished = o.fieldLong("revisionPublished") ?: 0,
      result = o.fieldText("result") ?: "installed",
      activation = o.fieldText("activation") ?: "not_requested",
      errorKind = o.fieldText("errorKind"),
      atEpochMillis = o.fieldLong("atEpochMillis"),
    )
  }

private fun JsonObject.toCheck(): WatchFaceValidationCheck {
  val id = fieldText("id").orEmpty()
  return WatchFaceValidationCheck(
    id = id,
    title = fieldText("title") ?: id,
    status = fieldText("status").orEmpty(),
    findings =
      fieldObjects("findings").map { f ->
        WatchFaceValidationFinding(
          severity = f.fieldText("severity") ?: "error",
          message = f.fieldText("message").orEmpty(),
          line = f.fieldInt("line"),
          column = f.fieldInt("column"),
          path = f.fieldText("path"),
          suggestion = f.fieldText("suggestion"),
        )
      },
    memory =
      (get("memory") as? JsonObject)?.let { m ->
        WatchFaceMemoryFootprint(
          totalBytes = m.fieldLong("totalBytes") ?: 0,
          maxActiveBytes = m.fieldLong("maxActiveBytes") ?: 0,
          maxAmbientBytes = m.fieldLong("maxAmbientBytes") ?: 0,
          activeLimitBytes = m.fieldLong("activeLimitBytes") ?: 0,
          ambientLimitBytes = m.fieldLong("ambientLimitBytes") ?: 0,
          contributors = m.fieldObjects("contributors").map { it.toContributor() },
        )
      },
    passedChecks = (get("passedChecks") as? JsonArray)?.mapNotNull { it.fieldText() },
    tokenIssued = fieldBool("tokenIssued"),
    contributors = fieldObjects("contributors").map { it.toContributor() },
  )
}

private fun JsonObject.toContributor() =
  WatchFaceMemoryContributor(
    resource = fieldText("resource").orEmpty(),
    kind = fieldText("kind"),
    width = fieldInt("width"),
    height = fieldInt("height"),
    format = fieldText("format"),
    fileBytes = fieldLong("fileBytes"),
    decodedBytes = fieldLong("decodedBytes"),
    activeBytes = fieldLong("activeBytes"),
    ambientBytes = fieldLong("ambientBytes"),
    renderedMaxWidth = fieldInt("renderedMaxWidth"),
    renderedMaxHeight = fieldInt("renderedMaxHeight"),
    missing = fieldBool("missing") ?: false,
    suggestion = fieldText("suggestion"),
  )

/** A field as text, whether the server sent a string, a number or a boolean; null when absent. */
internal fun JsonObject.fieldText(key: String): String? = get(key)?.fieldText()

internal fun JsonElement.fieldText(): String? =
  (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.fieldLong(key: String): Long? = fieldText(key)?.toLongOrNull()

internal fun JsonObject.fieldInt(key: String): Int? = fieldText(key)?.toIntOrNull()

internal fun JsonObject.fieldBool(key: String): Boolean? = fieldText(key)?.toBooleanStrictOrNull()

internal fun JsonObject.fieldObjects(key: String): List<JsonObject> =
  (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** What a host answered for one validation read or run. */
sealed interface WatchFaceValidationAnswer {
  data class Ran(val report: WatchFaceValidationReport) : WatchFaceValidationAnswer

  /** `GET` answered 404: nobody has validated this revision. */
  data object NeverRun : WatchFaceValidationAnswer

  /** The host's own sentence for why not (signed out, not an editor, runtime missing, ...). */
  data class Refused(val reason: String) : WatchFaceValidationAnswer

  /**
   * The server has validation switched off (`503 watch_face_validation_disabled`): nothing to
   * retry, so the section says so and offers no button.
   */
  data class Disabled(val reason: String) : WatchFaceValidationAnswer
}

/** The error code a server with validation switched off answers with. */
const val WATCH_FACE_VALIDATION_DISABLED = "watch_face_validation_disabled"

/** What the section says when the server has validation switched off. */
const val WATCH_FACE_VALIDATION_DISABLED_REASON =
  "Watch face validation is switched off on this server."

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
class WatchFaceValidationController(
  private val host: WatchFaceValidationHost,
  /**
   * Where a run started from the panel lives: the open design's, not the panel's, so switching away
   * from Issues (or scrolling the section out of its list) does not abandon a run the server is
   * still doing.
   */
  private val scope: CoroutineScope,
) {
  /**
   * Bumped by every read and run; an answer lands only if nothing newer started after it, so a slow
   * read never replaces the result of a run that finished first.
   */
  private var generation = 0
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
    val mine = ++generation
    val answer = attempt { host.latest(revision) }
    // A run or read that started while this one was out wins: its answer is the newer one.
    if (mine != generation) return
    _validation.value =
      answer.fold(
        onSuccess = { it.toView(previous = null) },
        onFailure = {
          WatchFaceValidationView.Failed(it.message ?: "the validation result could not be read")
        },
      )
  }

  /** Starts a run in the design's scope; see [scope]. */
  fun start(revision: Long) {
    scope.launch { run(revision) }
  }

  suspend fun run(revision: Long) {
    if (host.unavailableReason != null) return
    val mine = ++generation
    val previous = _validation.value.report()
    _validation.value = WatchFaceValidationView.Running(previous)
    val answer = attempt { host.run(revision) }
    if (mine != generation) return
    _validation.value =
      answer.fold(
        onSuccess = { it.toView(previous) },
        onFailure = {
          WatchFaceValidationView.Failed(it.message ?: "the validation request failed", previous)
        },
      )
  }

  suspend fun loadInstalls() {
    if (host.unavailableReason != null) return
    _installs.value =
      attempt { host.installs() }
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

  /** [runCatching] that lets cancellation through, so a cancelled request is never a failure. */
  private inline fun <T> attempt(block: () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      Result.failure(failure)
    }

  private fun WatchFaceValidationAnswer.toView(
    previous: WatchFaceValidationReport?,
  ): WatchFaceValidationView =
    when (this) {
      is WatchFaceValidationAnswer.Ran -> WatchFaceValidationView.Ran(report)
      WatchFaceValidationAnswer.NeverRun -> WatchFaceValidationView.NotRun
      is WatchFaceValidationAnswer.Refused -> WatchFaceValidationView.Failed(reason, previous)
      is WatchFaceValidationAnswer.Disabled -> WatchFaceValidationView.Unavailable(reason)
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

/**
 * The design node a memory contributor stands for: one whose id is the resource, else the first
 * whose `resource` property names it, with or without a path or extension. Null when nothing in the
 * design does (a resource the exporter generated, say).
 */
fun UiBuilderDocument.nodeForResource(resource: String): String? {
  if (resource in nodes) return resource
  val bare = resource.substringAfterLast('/').substringBeforeLast('.')
  return nodes.values
    .firstOrNull { node ->
      val named = resourceName(node.properties["resource"])
      named != null &&
        (named == resource || named == bare || named.substringBeforeLast('.') == bare)
    }
    ?.id
}

/**
 * Shows the exported source in whichever layout is drawn: the wide layout's Code dock or the
 * compact layout's Code sheet. Provided by the editor; null outside it.
 */
internal val LocalShowGeneratedCode = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * A `resource` property's name, whichever way the document stores it: typed, as `{"type": "string",
 * "value": "dial"}` (how designs are saved), or as a bare string.
 */
private fun resourceName(property: JsonElement?): String? {
  val value = (property as? JsonObject)?.get("value") ?: property
  return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
}
