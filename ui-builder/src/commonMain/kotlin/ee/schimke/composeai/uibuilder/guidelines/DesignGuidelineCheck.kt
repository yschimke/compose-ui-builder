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

  /** Whether this person last chose to see findings over the design; null when never chosen. */
  fun storedOverlay(): Boolean? = null

  fun storeOverlay(shown: Boolean) {}

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

  /**
   * The Jetpack Compose source [document] exports to, shown to the model beside the design tree so
   * rules about code are judged on the real calls. Null where the host cannot export it, or the
   * export gate refuses the design; the tree is then all the model sees.
   */
  suspend fun source(document: UiBuilderDocument): String? = null

  /**
   * The request the design host builds for [document] — the same one an agent gets from
   * `ui_builder_guidelines_prompt`, with native device and unrolled pictures. Null where there is
   * no such host; the editor then builds its own with [picture] and [source].
   */
  suspend fun hostedRequest(document: UiBuilderDocument): DesignGuidelineRequest? = null

  /** The design's latest recorded result, whoever ran it; null where the host keeps none. */
  suspend fun sharedResult(): DesignGuidelineRecord? = null

  /** Records [record] as the design's latest result, so agents and other people see it. */
  suspend fun recordResult(record: DesignGuidelineRecord): DesignGuidelineRecord? = null

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
// Called directly rather than through `typesafe/jev-router`, which routes this check to the same
// model most of the time but picks at random, through dearer providers, and reads only the text —
// so it never chooses for the pictures the visual rules are judged on.
const val DEFAULT_GUIDELINE_MODEL: String = "deepseek/deepseek-v4.1-flash"

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
  /** Whether the generated Compose source went to the model with the design tree. */
  val sourceAttached: Boolean = false,
  /** Exactly what the model was asked; null for a result read back without its prompt. */
  val request: DesignGuidelineRequest? = null,
  /** Who ran it, as the host records it (`github:…`, `agent:…`, `server`); null when unknown. */
  val ranBy: String? = null,
  /** The model that actually answered, and how it was chosen; null when not reported. */
  val served: DesignGuidelineServed? = null,
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

  private val _prompt = MutableStateFlow<PromptView>(PromptView.Hidden)

  /** The request shown under Prompt: what the model reads, and where each part came from. */
  val prompt: StateFlow<PromptView> = _prompt.asStateFlow()

  private val _overlay = MutableStateFlow(host.storedOverlay() ?: true)

  /** Whether the findings are drawn over the design on the canvas; on unless turned off. */
  val overlay: StateFlow<Boolean> = _overlay.asStateFlow()

  fun setOverlay(shown: Boolean) {
    host.storeOverlay(shown)
    _overlay.value = shown
  }

  private val _shared = MutableStateFlow<DesignGuidelineResult?>(null)

  /** The design's latest recorded result — from this person, another, or an agent. */
  val shared: StateFlow<DesignGuidelineResult?> = _shared.asStateFlow()

  sealed interface PromptView {
    data object Hidden : PromptView

    data object Loading : PromptView

    data class Shown(val request: DesignGuidelineRequest) : PromptView

    data class Failed(val reason: String) : PromptView
  }

  /** Reads the design's latest recorded result from the host, when it keeps one. */
  suspend fun loadShared() {
    val record = runCatching { host.sharedResult() }.getOrNull() ?: return
    _shared.value = record.toResult(rules)
  }

  /** Builds the request [document] would send, without sending it — no key needed. */
  suspend fun preview(document: UiBuilderDocument, encoded: JsonObject) {
    _prompt.value = PromptView.Loading
    _prompt.value =
      try {
        PromptView.Shown(prepare(document, encoded))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (thrown: Exception) {
        PromptView.Failed("The prompt could not be built: ${thrown.message}")
      }
  }

  fun hidePrompt() {
    _prompt.value = PromptView.Hidden
  }

  private suspend fun prepare(
    document: UiBuilderDocument,
    encoded: JsonObject,
  ): DesignGuidelineRequest =
    runCatching { host.hostedRequest(document) }.getOrNull()
      ?: run {
        val platform =
          (encoded["catalogPin"] as? JsonObject)
            ?.get("systemId")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.let(DesignGuidelinePrompt::platformOf)
        val visual = platform != null && rules.forPlatform(platform).any { it.visual }
        DesignGuidelinePrompt.prepare(
          rules = rules,
          designId = document.id,
          revision = document.revision,
          document = encoded,
          devicePicture = if (visual) host.picture(document) else null,
          source = if (platform != null) host.source(document) else null,
        )
      }

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
    val request = prepare(document, encoded)
    if (_prompt.value !is PromptView.Hidden) _prompt.value = PromptView.Shown(request)
    // A catalog with no guidelines written, or none this host could judge without a picture.
    if (request.platform == null || request.rules.asked.isEmpty()) {
      return DesignGuidelineResult(
        document.revision,
        null,
        emptyList(),
        0,
        0,
        model,
        request = request,
      )
    }
    val asked = request.rules.asked
    val response = host.complete(DesignGuidelinePrompt.body(request, model).toString(), key)
    if (response.status !in 200..299) {
      throw GuidelineCheckFailure(
        response.status,
        "OpenRouter answered ${response.status}: " +
          DesignGuidelinePrompt.errorMessage(response.body),
      )
    }
    val served = DesignGuidelinePrompt.parseServed(response.body)
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
    val result =
      DesignGuidelineResult(
        revision = document.revision,
        platform = request.platform,
        findings = DesignGuidelinePrompt.findings(answered, asked, document.nodes.keys),
        judged = answered.size,
        unanswered = asked.map { it.id } - answered.map { it.ruleId }.toSet(),
        visualSkipped = request.rules.visualSkipped,
        model = model,
        sourceAttached = request.sourceAttached,
        request = request,
        served = served,
      )
    // Shared with agents and other people: the design's latest result is whoever ran it last.
    val recorded = runCatching {
      host.recordResult(
        DesignGuidelineRecord(
          designId = document.id,
          revision = document.revision,
          model = model,
          rulesVersion = request.rules.version,
          asked = asked.map { it.id },
          verdicts = answered,
          servedModel = served.model,
          provider = served.provider,
          costUsd = served.costUsd,
          generationId = served.generationId,
          routing = served.routing,
        )
      )
    }
      .getOrNull()
    if (recorded != null) _shared.value = recorded.toResult(rules)
    return result.copy(ranBy = recorded?.ranBy)
  }

  private fun currentModel(): String = host.storedModel() ?: DEFAULT_GUIDELINE_MODEL

  private class GuidelineCheckFailure(val status: Int, message: String) : Exception(message)

  companion object {
    /** The document as the protocol writes it, which is what the outline reads. */
    fun encode(document: UiBuilderDocument): JsonObject =
      ENCODE_JSON.encodeToJsonElement(UiBuilderDocument.serializer(), document.copy(home = null))
        .jsonObject
  }
}

/**
 * The guidelines check, where the host offers one. Provided by the browser app around the editor;
 * null in previews, tests and hosts without one, where the Issues panel leaves the section out.
 */
val LocalDesignGuidelineCheck = staticCompositionLocalOf<DesignGuidelineController?> { null }

private val ENCODE_JSON = kotlinx.serialization.json.Json { explicitNulls = false }

/**
 * [this] as a result the panel can show. A verdict on a rule this editor's set does not know (a
 * newer rule set on the host) is kept as its id and reason rather than dropped.
 */
fun DesignGuidelineRecord.toResult(rules: DesignGuidelineRuleSet): DesignGuidelineResult {
  val known = rules.rules.associateBy { it.id }
  val askedRules = asked.map { id ->
    known[id]
      ?: DesignGuidelineRule(id, emptyList(), "structure", "info", "", id, GUIDELINE_RULES_URL)
  }
  val answered = DesignGuidelinePrompt.answered(verdicts, askedRules)
  val nodeIds = verdicts.flatMap { it.nodeIds }.toSet()
  return DesignGuidelineResult(
    revision = revision,
    platform = askedRules.firstOrNull()?.platforms?.firstOrNull(),
    findings = DesignGuidelinePrompt.findings(answered, askedRules, nodeIds),
    judged = answered.size,
    visualSkipped = 0,
    model = model,
    unanswered = asked - answered.map { it.ruleId }.toSet(),
    ranBy = ranBy,
    served = served.takeIf { it.model != null || it.routing != null },
  )
}
