@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ee.schimke.composeai.uibuilder.editor.UiBuilderChatController
import ee.schimke.composeai.uibuilder.editor.UiBuilderChatHost
import ee.schimke.composeai.uibuilder.editor.UiBuilderChatMessage
import ee.schimke.composeai.uibuilder.editor.UiBuilderChatSession
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineHost
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val CHAT_CALLBACK = "openrouter-chat-callback"
private const val CHAT_VERIFIER = "ui-builder.chat.pkce-verifier"
private val chatJson = Json { ignoreUnknownKeys = true }
private val connections = mutableMapOf<String, BrowserChatConnection>()

/** Account-scoped memory by default. Remembering a provider key is an explicit browser choice. */
private class BrowserChatConnection(actorId: String) {
  private val prefix = "ui-builder.chat.connection." + chatJson.encodeToString(actorId)
  var remembered by mutableStateOf(readChatSetting("$prefix.remember") == "1")
    private set

  var key by mutableStateOf(if (remembered) readChatSetting("$prefix.key") else "")
    private set

  var notice by mutableStateOf<String?>(null)

  fun useKey(value: String) {
    key = value.trim()
    notice = writeChatSetting("$prefix.key", key.takeIf { remembered }.orEmpty())
  }

  fun remember(value: Boolean) {
    remembered = value
    val flagFailure = writeChatSetting("$prefix.remember", if (value) "1" else "")
    val keyFailure = writeChatSetting("$prefix.key", key.takeIf { value }.orEmpty())
    notice = flagFailure ?: keyFailure
  }

  fun disconnect() {
    key = ""
    remembered = false
    val keyFailure = writeChatSetting("$prefix.key", "")
    val flagFailure = writeChatSetting("$prefix.remember", "")
    notice = keyFailure ?: flagFailure
  }
}

/**
 * All inference goes directly to OpenRouter. No chat or provider key is sent to the design host.
 */
internal class BrowserChatHost(
  designId: String,
  private val actorId: String,
  override val monitoringAvailable: Boolean,
  private val context: () -> String,
) : UiBuilderChatHost {
  private val connection = connections.getOrPut(actorId) { BrowserChatConnection(actorId) }
  private val historyKey =
    "ui-builder.chat.session." + chatJson.encodeToString(listOf(actorId, designId))
  override val connected
    get() = connection.key.isNotBlank()

  override val rememberConnection
    get() = connection.remembered

  override val connectionNotice
    get() = connection.notice

  override fun connect() {
    if (!rememberChatSignInAccount(actorId)) {
      connection.notice = "This browser cannot keep the sign-in verifier. Paste a key instead."
      return
    }
    beginOpenRouterSignIn(CHAT_VERIFIER, CHAT_CALLBACK)
  }

  override fun useKey(key: String) = connection.useKey(key)

  override fun rememberConnection(remember: Boolean) = connection.remember(remember)

  override fun disconnect() = connection.disconnect()

  override fun load(): UiBuilderChatSession = runCatching {
    val raw = readChatSetting(historyKey)
    require(raw.length <= 512_000)
    val stored = chatJson.decodeFromString<UiBuilderChatSession>(raw)
    stored.copy(
      model = stored.model.take(200),
      messages =
        stored.messages
          .filter { it.role == "user" || it.role == "assistant" }
          .takeLast(UiBuilderChatController.MAX_MESSAGES)
          .map { it.copy(content = it.content.take(UiBuilderChatController.MAX_MESSAGE_CHARS)) },
      reviewedComments = stored.reviewedComments.entries.take(500).associate { it.toPair() },
    )
  }
    .getOrDefault(UiBuilderChatSession())

  override fun save(session: UiBuilderChatSession): String? {
    val raw = chatJson.encodeToString(UiBuilderChatSession.serializer(), session)
    if (raw.length > 512_000) return "Chat history is too large to save. Clear chat to start again."
    return writeChatSetting(historyKey, raw)
  }

  override fun clear() = writeChatSetting(historyKey, "")

  override suspend fun complete(model: String, messages: List<UiBuilderChatMessage>): String {
    // Retain recent turns within an input budget without changing the person's saved history.
    var remaining = 48_000
    val recent =
      messages
        .asReversed()
        .takeWhile {
          remaining -= it.content.length
          remaining >= 0
        }
        .asReversed()
    val currentContext = context()
    val body = buildJsonObject {
      put("model", model)
      put("max_tokens", 1800)
      put(
        "messages",
        buildJsonArray {
          add(
            buildJsonObject {
              put("role", "system")
              put("content", UiBuilderChatController.DEFAULT_INSTRUCTIONS)
            }
          )
          add(
            buildJsonObject {
              put("role", "user")
              put(
                "content",
                currentContext.take(64_000) +
                  if (currentContext.length > 64_000) "\n(Context abbreviated.)" else "",
              )
            }
          )
          recent.forEach { message ->
            add(
              buildJsonObject {
                put("role", message.role)
                put("content", message.content)
              }
            )
          }
        },
      )
    }
      .toString()
    val raw =
      suspendCancellableCoroutine<String> { continuation ->
        val request =
          openBrowserChatRequest(
            body,
            connection.key,
            onResult = { result -> if (continuation.isActive) continuation.resume(result) },
            onError = {
              if (continuation.isActive)
                continuation.resumeWithException(IllegalStateException("OpenRouter request failed"))
            },
          )
        continuation.invokeOnCancellation { abortBrowserChatRequest(request) }
      }
    val answer =
      chatJson
        .parseToJsonElement(raw)
        .jsonObject["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?.get("content")
        ?.jsonPrimitive
        ?.contentOrNull
    return answer?.takeIf { it.isNotBlank() } ?: error("OpenRouter returned no answer")
  }
}

/**
 * OAuth return routing is separate from the guidelines connection and never persists by default.
 */
internal fun startBrowserChatSignInAtBoot(scope: CoroutineScope) {
  if (!isChatSignInReturn()) return
  val actorId = takeChatSignInAccount()
  scope.launch {
    val result = runCatching {
      withTimeout(20_000) { exchangeOpenRouterSignIn(CHAT_VERIFIER, CHAT_CALLBACK) }
    }
      .getOrNull()
    if (actorId.isBlank()) return@launch
    val connection = connections.getOrPut(actorId) { BrowserChatConnection(actorId) }
    when (result) {
      is DesignGuidelineHost.SignInResult.Signed -> connection.useKey(result.key)
      else -> connection.notice = "OpenRouter sign-in did not finish. Connect again or paste a key."
    }
  }
}

@JsFun("(key) => { try { return localStorage.getItem(key) || ''; } catch (_) { return ''; } }")
private external fun readChatSetting(key: String): String

@JsFun(
  """(key, value) => {
  try {
    if (value) localStorage.setItem(key, value); else localStorage.removeItem(key);
    return null;
  } catch (_) { return 'Could not update chat storage in this browser.'; }
}"""
)
private external fun writeChatSetting(key: String, value: String): String?

@JsFun(
  """(actorId) => {
  try { sessionStorage.setItem('ui-builder.chat.pkce-account', actorId); return true; }
  catch (_) { return false; }
}"""
)
private external fun rememberChatSignInAccount(actorId: String): Boolean

@JsFun(
  """() => {
  try {
    const account = sessionStorage.getItem('ui-builder.chat.pkce-account') || '';
    sessionStorage.removeItem('ui-builder.chat.pkce-account');
    return account;
  } catch (_) { return ''; }
}"""
)
private external fun takeChatSignInAccount(): String

@JsFun(
  "() => new URL(globalThis.location.href).searchParams.get('openrouter-chat-callback') === '1'"
)
private external fun isChatSignInReturn(): Boolean

/**
 * Fixed provider destination, no application cookies, no redirects, bounded response and timeout.
 */
@JsFun(
  """(body, key, onResult, onError) => {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 60000);
  fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST', credentials: 'omit', redirect: 'error', signal: controller.signal,
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key,
      'X-Title': 'Compose UI Builder browser chat' },
    body,
  }).then(async response => {
    if (!response.ok || !response.body) throw new Error('Provider refused');
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let text = '', bytes = 0;
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        bytes += chunk.value.byteLength;
        if (bytes > 128000) { controller.abort(); throw new Error('Response too large'); }
        text += decoder.decode(chunk.value, {stream: true});
      }
      onResult(text + decoder.decode());
    } finally { reader.releaseLock(); }
  }).catch(() => onError()).finally(() => clearTimeout(timeout));
  return controller;
}"""
)
private external fun openBrowserChatRequest(
  body: String,
  key: String,
  onResult: (String) -> Unit,
  onError: () -> Unit,
): JsAny

@JsFun("(request) => request.abort()") private external fun abortBrowserChatRequest(request: JsAny)
