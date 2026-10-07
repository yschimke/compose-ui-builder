package ee.schimke.composeai.uibuilder.guidelines

import androidx.compose.runtime.staticCompositionLocalOf
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What the guidelines check needs from wherever the editor runs: somewhere to keep a person's own
 * OpenRouter key, a way to get one, and a way to send a request with it.
 *
 * The key belongs to the person at the keyboard and is spent on their account. It is kept in this
 * browser only and sent to OpenRouter only — never to the design host.
 */
interface DesignGuidelineHost {
  /** The stored key, or null when this person has not added one. */
  fun storedKey(): String?

  fun storeKey(key: String?)

  fun storedModel(): String?

  fun storeModel(model: String?)

  /**
   * Starts OpenRouter's sign-in (OAuth PKCE): the page leaves for openrouter.ai and comes back with
   * a code [completeSignIn] trades for a key. Null where the host cannot navigate.
   */
  val signIn: (() -> Unit)?

  /** Finishes a sign-in this page started, returning the key or a sentence saying why not. */
  suspend fun completeSignIn(): SignInResult

  /** POSTs [body] to OpenRouter's chat completions with [key]. */
  suspend fun complete(body: String, key: String): Response

  /** A PNG of [document] as a `data:` URL, for the visual rules; null where none can be made. */
  suspend fun picture(document: UiBuilderDocument): String?

  data class Response(val status: Int, val body: String)

  sealed interface SignInResult {
    data object NotReturning : SignInResult

    data class Signed(val key: String) : SignInResult

    data class Failed(val reason: String) : SignInResult
  }
}

/** Where to get a key, shown beside the field. */
const val OPENROUTER_KEYS_URL: String = "https://openrouter.ai/settings/keys"

/** The model a person starts with; any OpenRouter model id works. */
const val DEFAULT_GUIDELINE_MODEL: String = "typesafe/jev-router"

sealed interface DesignGuidelineState {
  /** No key yet: the panel explains how to get one. */
  data class NeedsKey(val notice: String? = null) : DesignGuidelineState

  data class Ready(
    val model: String,
    val result: DesignGuidelineResult? = null,
    val running: Boolean = false,
    val notice: String? = null,
  ) : DesignGuidelineState
}

data class DesignGuidelineResult(
  /** The design revision judged, so the panel can say when it is out of date. */
  val revision: Int,
  val platform: String?,
  val findings: List<DesignGuidelineFinding>,
  val judged: Int,
  val visualSkipped: Int,
  val model: String,
  /** Rules asked about that the model returned no verdict for: unchecked, not passed. */
  val unanswered: List<String> = emptyList(),
)

/**
 * The browser half of the guidelines check: holds the person's key and model, runs a check of the
 * design on screen, and keeps the last result. Requests go from this page straight to OpenRouter.
 */
class DesignGuidelineController(
  private val host: DesignGuidelineHost,
  private val rules: DesignGuidelineRuleSet = DesignGuidelineRuleSet.Bundled,
) {
  private val _state =
    MutableStateFlow(
      host.storedKey()?.let { DesignGuidelineState.Ready(currentModel()) }
        ?: DesignGuidelineState.NeedsKey()
    )
  val state: StateFlow<DesignGuidelineState> = _state.asStateFlow()

  val canSignIn: Boolean
    get() = host.signIn != null

  fun signIn() {
    host.signIn?.invoke()
  }

  /** Called once when the page loads, in case it is coming back from OpenRouter's sign-in. */
  suspend fun completeSignIn() {
    when (val result = host.completeSignIn()) {
      DesignGuidelineHost.SignInResult.NotReturning -> Unit
      is DesignGuidelineHost.SignInResult.Signed -> {
        host.storeKey(result.key)
        _state.value =
          DesignGuidelineState.Ready(currentModel(), notice = "Connected to OpenRouter.")
      }
      is DesignGuidelineHost.SignInResult.Failed ->
        _state.value = DesignGuidelineState.NeedsKey(result.reason)
    }
  }

  fun saveKey(key: String) {
    val trimmed = key.trim()
    if (trimmed.isEmpty()) {
      _state.value = DesignGuidelineState.NeedsKey("Paste a key that starts with sk-or-.")
      return
    }
    host.storeKey(trimmed)
    _state.value = DesignGuidelineState.Ready(currentModel())
  }

  fun forgetKey() {
    host.storeKey(null)
    _state.value = DesignGuidelineState.NeedsKey("The key was removed from this browser.")
  }

  fun setModel(model: String) {
    val trimmed = model.trim().ifEmpty { DEFAULT_GUIDELINE_MODEL }
    host.storeModel(trimmed.takeIf { it != DEFAULT_GUIDELINE_MODEL })
    (_state.value as? DesignGuidelineState.Ready)?.let { _state.value = it.copy(model = trimmed) }
  }

  suspend fun check(document: UiBuilderDocument, encoded: JsonObject) {
    val ready = _state.value as? DesignGuidelineState.Ready ?: return
    val key = host.storedKey() ?: return run { _state.value = DesignGuidelineState.NeedsKey() }
    _state.value = ready.copy(running = true, notice = null)
    _state.value =
      try {
        ready.copy(result = judge(document, encoded, key, ready.model), running = false)
      } catch (cancelled: CancellationException) {
        _state.value = ready.copy(running = false)
        throw cancelled
      } catch (failure: GuidelineCheckFailure) {
        if (failure.status == 401) {
          host.storeKey(null)
          DesignGuidelineState.NeedsKey("OpenRouter refused the key: ${failure.message}")
        } else {
          ready.copy(running = false, notice = failure.message)
        }
      } catch (thrown: Exception) {
        ready.copy(running = false, notice = "The check could not run: ${thrown.message}")
      }
  }

  private suspend fun judge(
    document: UiBuilderDocument,
    encoded: JsonObject,
    key: String,
    model: String,
  ): DesignGuidelineResult {
    val systemId =
      (encoded["catalogPin"] as? JsonObject)?.get("systemId")?.let {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
      }
    val platform = systemId?.let(DesignGuidelinePrompt::platformOf)
    if (platform == null) {
      return DesignGuidelineResult(document.revision, null, emptyList(), 0, 0, model)
    }
    val applicable = rules.forPlatform(platform)
    val picture = if (applicable.any { it.visual }) host.picture(document) else null
    val asked = if (picture != null) applicable else applicable.filterNot { it.visual }
    val body =
      DesignGuidelinePrompt.requestBody(model, platform, encoded, asked, picture).toString()
    val response = host.complete(body, key)
    if (response.status !in 200..299) {
      throw GuidelineCheckFailure(
        response.status,
        "OpenRouter answered ${response.status}: " +
          DesignGuidelinePrompt.errorMessage(response.body),
      )
    }
    val verdicts =
      DesignGuidelinePrompt.parseCompletion(response.body).getOrElse {
        throw GuidelineCheckFailure(
          response.status,
          "$model did not answer with the verdict list asked for. Try another model.",
        )
      }
    val answered = DesignGuidelinePrompt.answered(verdicts, asked)
    if (answered.isEmpty()) {
      throw GuidelineCheckFailure(
        response.status,
        "$model returned no verdict for any of the ${asked.size} rules. Try another model.",
      )
    }
    return DesignGuidelineResult(
      revision = document.revision,
      platform = platform,
      findings = DesignGuidelinePrompt.findings(answered, asked, document.nodes.keys),
      judged = answered.size,
      unanswered = asked.map { it.id } - answered.map { it.ruleId }.toSet(),
      visualSkipped = applicable.size - asked.size,
      model = model,
    )
  }

  private fun currentModel(): String = host.storedModel() ?: DEFAULT_GUIDELINE_MODEL

  private class GuidelineCheckFailure(val status: Int, message: String) : Exception(message)

  companion object {
    /** The document as the protocol writes it, which is what the outline reads. */
    fun encode(document: UiBuilderDocument): JsonObject =
      GUIDELINE_JSON.encodeToJsonElement(UiBuilderDocument.serializer(), document.copy(home = null))
        .jsonObject
  }
}

/**
 * The guidelines check, where the host offers one. Provided by the browser app around the editor;
 * null in previews, tests and hosts without one, where the Issues panel leaves the section out.
 */
val LocalDesignGuidelineCheck = staticCompositionLocalOf<DesignGuidelineController?> { null }
