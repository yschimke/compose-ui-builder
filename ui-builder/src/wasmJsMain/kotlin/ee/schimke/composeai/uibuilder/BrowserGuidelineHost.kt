@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineHost
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRecord
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRequest
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The browser half of the guidelines check: the person's own OpenRouter key in this browser's
 * `localStorage`, OpenRouter's PKCE sign-in to get one without copying it by hand, and the request
 * itself — sent from this page straight to openrouter.ai (the editor page's `connect-src` admits
 * it), so the key never reaches the design host.
 *
 * [designId] and [hostedByServer] are for the picture the visual rules need: on
 * compose-preview-server the design's thumbnail is a same-origin read; anywhere else there is none
 * and only the rules judged from the design tree run.
 */
internal class BrowserGuidelineHost(
  private val designId: String,
  private val hostedByServer: Boolean,
) : DesignGuidelineHost {
  override fun storedKey(): String? = readGuidelineSetting(KEY_STORAGE).takeIf { it.isNotBlank() }

  override fun storeKey(key: String?) {
    writeGuidelineSetting(KEY_STORAGE, key.orEmpty())
  }

  override fun storedModel(): String? =
    readGuidelineSetting(MODEL_STORAGE).takeIf { it.isNotBlank() }

  override fun storeModel(model: String?) {
    writeGuidelineSetting(MODEL_STORAGE, model.orEmpty())
  }

  override fun storedOverlay(): Boolean? =
    when (readGuidelineSetting(OVERLAY_STORAGE)) {
      "1" -> true
      "0" -> false
      else -> null
    }

  override fun storeOverlay(shown: Boolean) {
    writeGuidelineSetting(OVERLAY_STORAGE, if (shown) "1" else "0")
  }

  override val signIn: (() -> Unit) = { beginOpenRouterSignIn(VERIFIER_STORAGE) }

  /**
   * The sign-in [finishOpenRouterSignInAtBoot] finished when the page loaded, if any, and otherwise
   * one finished now. Startup is where it is really done: the key is saved before the editor
   * composes, so it does not depend on this panel ever being asked.
   */
  override suspend fun completeSignIn(): DesignGuidelineHost.SignInResult =
    bootSignIn?.also { bootSignIn = null } ?: exchangeOpenRouterSignIn()

  override suspend fun complete(body: String, key: String): DesignGuidelineHost.Response {
    val raw = awaitCommentString(openRouterComplete(OPENROUTER_CHAT_COMPLETIONS, body, key))
    val wire = guidelineJson.decodeFromString(ResponseWire.serializer(), raw)
    return DesignGuidelineHost.Response(wire.status, wire.body)
  }

  override suspend fun picture(document: UiBuilderDocument): String? {
    if (!hostedByServer) return null
    val url =
      try {
        sameOriginRequestUrl(
          "/api/ui-builder/v1/designs/${encodeUriComponent(designId)}/thumbnail.png" +
            "?revision=${document.revision}"
        )
      } catch (_: Throwable) {
        return null
      }
    return try {
      awaitCommentString(fetchAsDataUrl(url)).takeIf { it.startsWith("data:image/") }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * The Compose source this revision exports to, from compose-preview-server's live export. Null
   * off that host, for a reader without the export grant, and for a design the export gate refuses
   * (the route answers 422 with the reasons rather than code).
   */
  override suspend fun source(document: UiBuilderDocument): String? {
    if (!hostedByServer) return null
    val url =
      try {
        sameOriginRequestUrl(
          "/api/ui-builder/v1/designs/${encodeUriComponent(designId)}/export.compose" +
            "?revision=${document.revision}"
        )
      } catch (_: Throwable) {
        return null
      }
    return try {
      val wire =
        guidelineJson.decodeFromString(
          ResponseWire.serializer(),
          awaitCommentString(commentFetch("GET", url, "", false)),
        )
      wire.body.takeIf { wire.status == 200 && it.isNotBlank() }
    } catch (_: Exception) {
      null
    }
  }

  /**
   * The request compose-preview-server builds for this revision — native device and unrolled
   * pictures, the exported source — so the Prompt view shows exactly what an agent gets from
   * `ui_builder_guidelines_prompt`. Null off that host, and from a server without the route; the
   * editor then builds its own.
   */
  override suspend fun hostedRequest(document: UiBuilderDocument): DesignGuidelineRequest? =
    serverGet("/guidelines/prompt?revision=${document.revision}&rendered=true")?.let {
      guidelineJson.decodeFromString(DesignGuidelineRequest.serializer(), it)
    }

  override suspend fun sharedResult(): DesignGuidelineRecord? =
    serverGet("/guidelines")?.let {
      guidelineJson.decodeFromString(DesignGuidelineRecord.serializer(), it)
    }

  /**
   * Posts a run on this person's key to the design's shared record. The server stamps who ran it
   * and when from the session, and refuses a reader without write access; either way the person
   * keeps their own result on screen.
   */
  override suspend fun recordResult(record: DesignGuidelineRecord): DesignGuidelineRecord? {
    val url = designUrl("/guidelines") ?: return null
    return try {
      val wire =
        guidelineJson.decodeFromString(
          ResponseWire.serializer(),
          awaitCommentString(
            commentFetch(
              "POST",
              url,
              guidelineJson.encodeToString(DesignGuidelineRecord.serializer(), record),
              true,
            )
          ),
        )
      if (wire.status !in 200..299) null
      else guidelineJson.decodeFromString(DesignGuidelineRecord.serializer(), wire.body)
    } catch (_: Exception) {
      null
    }
  }

  override suspend fun serverAccess(): DesignGuidelineHost.ServerCheckAccess? =
    serverGet("/guidelines/access")?.let {
      val wire = guidelineJson.decodeFromString(AccessWire.serializer(), it)
      DesignGuidelineHost.ServerCheckAccess(wire.serverCheck, wire.model, wire.reason)
    }

  /**
   * Runs the check on compose-preview-server's own key (`POST …/guidelines/check`), which answers
   * with the design's guidelines — the record it just wrote — or a sentence saying why not.
   */
  override suspend fun runServerCheck(
    document: UiBuilderDocument
  ): DesignGuidelineHost.ServerCheckOutcome {
    val url =
      designUrl("/guidelines/check?revision=${document.revision}")
        ?: return DesignGuidelineHost.ServerCheckOutcome.Refused(
          "this page is not served by a design host that runs the check"
        )
    val wire =
      guidelineJson.decodeFromString(
        ResponseWire.serializer(),
        awaitCommentString(commentFetch("POST", url, "{}", true)),
      )
    if (wire.status !in 200..299) {
      return DesignGuidelineHost.ServerCheckOutcome.Refused(
        "The server could not run the check (${wire.status}): " +
          (runCatching {
            guidelineJson.decodeFromString(ErrorWire.serializer(), wire.body).let {
              it.message ?: it.error
            }
          }
            .getOrNull() ?: wire.body.take(200))
      )
    }
    // The route answers with the stored record itself; a reply shaped like `GET …/guidelines`
    // (the record under `record`) is read too, so either server shape works.
    val record =
      runCatching { guidelineJson.decodeFromString(DesignGuidelineRecord.serializer(), wire.body) }
        .getOrNull()
        ?: runCatching {
          guidelineJson.decodeFromString(GuidelinesWire.serializer(), wire.body).record
        }
          .getOrNull()
    return record?.let { DesignGuidelineHost.ServerCheckOutcome.Recorded(it) }
      ?: DesignGuidelineHost.ServerCheckOutcome.Refused(
        "The server ran the check but recorded nothing."
      )
  }

  private fun designUrl(suffix: String): String? {
    if (!hostedByServer) return null
    return try {
      sameOriginRequestUrl("/api/ui-builder/v1/designs/${encodeUriComponent(designId)}$suffix")
    } catch (_: Throwable) {
      null
    }
  }

  /** A same-origin GET's body when it answers 200, else null. */
  private suspend fun serverGet(suffix: String): String? {
    val url = designUrl(suffix) ?: return null
    return try {
      val wire =
        guidelineJson.decodeFromString(
          ResponseWire.serializer(),
          awaitCommentString(commentFetch("GET", url, "", false)),
        )
      wire.body.takeIf { wire.status == 200 && it.isNotBlank() }
    } catch (_: Exception) {
      null
    }
  }

  @Serializable
  internal data class SignInWire(
    val state: String,
    val status: Int? = null,
    val body: String? = null,
    val reason: String? = null,
  )

  @Serializable internal data class KeyWire(val key: String? = null)

  @Serializable private data class ResponseWire(val status: Int, val body: String)

  @Serializable
  private data class AccessWire(
    val serverCheck: Boolean = false,
    val model: String? = null,
    val reason: String? = null,
  )

  @Serializable private data class GuidelinesWire(val record: DesignGuidelineRecord? = null)

  @Serializable private data class ErrorWire(val message: String? = null, val error: String? = null)

  internal companion object {
    const val KEY_STORAGE = "ui-builder.guidelines.openrouter-key"
    const val MODEL_STORAGE = "ui-builder.guidelines.model"
    const val OVERLAY_STORAGE = "ui-builder.guidelines.overlay"
    const val VERIFIER_STORAGE = "ui-builder.guidelines.pkce-verifier"
    const val OPENROUTER_CHAT_COMPLETIONS = "https://openrouter.ai/api/v1/chat/completions"
    val guidelineJson = Json { ignoreUnknownKeys = true }
  }
}

/** A sign-in finished at startup, held until the guidelines panel asks for it. */
private var bootSignIn: DesignGuidelineHost.SignInResult? = null

/**
 * Called from `main` before the editor composes: when this page is OpenRouter's sign-in coming
 * back, trades the code for a key and saves it. Each step is logged, so a sign-in that does not
 * take says why in the console.
 */
internal suspend fun finishOpenRouterSignInAtBoot() {
  val result = exchangeOpenRouterSignIn()
  when (result) {
    DesignGuidelineHost.SignInResult.NotReturning -> return
    is DesignGuidelineHost.SignInResult.Signed -> {
      writeGuidelineSetting(BrowserGuidelineHost.KEY_STORAGE, result.key)
      logGuidelines("OpenRouter sign-in finished; the key is saved in this browser.")
    }
    is DesignGuidelineHost.SignInResult.Failed ->
      logGuidelines("OpenRouter sign-in did not finish: ${result.reason}")
  }
  bootSignIn = result
}

private suspend fun exchangeOpenRouterSignIn(): DesignGuidelineHost.SignInResult {
  val json = Json { ignoreUnknownKeys = true }
  val raw =
    try {
      awaitCommentString(finishOpenRouterSignIn(BrowserGuidelineHost.VERIFIER_STORAGE))
    } catch (e: Exception) {
      return DesignGuidelineHost.SignInResult.Failed("OpenRouter sign-in failed: ${e.message}")
    }
  val outcome =
    try {
      json.decodeFromString(BrowserGuidelineHost.SignInWire.serializer(), raw)
    } catch (_: Exception) {
      return DesignGuidelineHost.SignInResult.Failed("OpenRouter sign-in answered unreadably.")
    }
  return when (outcome.state) {
    "none" -> DesignGuidelineHost.SignInResult.NotReturning
    "ok" -> {
      val key =
        try {
          json
            .decodeFromString(BrowserGuidelineHost.KeyWire.serializer(), outcome.body.orEmpty())
            .key
        } catch (_: Exception) {
          null
        }
      if (key.isNullOrBlank()) {
        DesignGuidelineHost.SignInResult.Failed("OpenRouter did not hand back a key.")
      } else {
        DesignGuidelineHost.SignInResult.Signed(key)
      }
    }
    else ->
      DesignGuidelineHost.SignInResult.Failed(
        outcome.reason
          ?: "OpenRouter refused the sign-in (${outcome.status ?: "no answer"}); try again or " +
            "paste a key."
      )
  }
}

@JsFun("(message) => console.info('[ui-builder guidelines] ' + message)")
private external fun logGuidelines(message: String)

@JsFun("(key) => { try { return localStorage.getItem(key) || ''; } catch (_) { return ''; } }")
private external fun readGuidelineSetting(key: String): String

@JsFun(
  """(key, value) => {
  try {
    if (value) localStorage.setItem(key, value); else localStorage.removeItem(key);
  } catch (_) {}
}"""
)
private external fun writeGuidelineSetting(key: String, value: String)

/**
 * OpenRouter's PKCE sign-in, step one: a random verifier kept in `sessionStorage`, its SHA-256
 * challenge sent along, and the page handed to openrouter.ai, which returns to this same address
 * with `?code=` and the `openrouter-callback` marker [finishOpenRouterSignIn] looks for.
 */
@JsFun(
  """(storageKey) => {
  const b64url = (buffer) => btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  const verifier = b64url(bytes);
  crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)).then((digest) => {
    sessionStorage.setItem(storageKey, verifier);
    const back = new URL(globalThis.location.href);
    back.hash = '';
    back.searchParams.delete('code');
    back.searchParams.set('openrouter-callback', '1');
    globalThis.location.href = 'https://openrouter.ai/auth?callback_url=' +
      encodeURIComponent(back.toString()) + '&code_challenge=' + b64url(digest) +
      '&code_challenge_method=S256';
  });
}"""
)
private external fun beginOpenRouterSignIn(storageKey: String)

/**
 * Step two, on the page OpenRouter returned to: take the code off the address (so a reload or a
 * shared link never carries it), and trade it with the stored verifier for a key.
 */
@JsFun(
  """(storageKey) => {
  const url = new URL(globalThis.location.href);
  if (url.searchParams.get('openrouter-callback') !== '1') {
    return Promise.resolve(JSON.stringify({ state: 'none' }));
  }
  const code = url.searchParams.get('code');
  url.searchParams.delete('code');
  url.searchParams.delete('openrouter-callback');
  globalThis.history.replaceState(globalThis.history.state, '', url.toString());
  let verifier = null;
  try { verifier = sessionStorage.getItem(storageKey); sessionStorage.removeItem(storageKey); } catch (_) {}
  if (!code || !verifier) {
    return Promise.resolve(JSON.stringify({
      state: 'failed',
      reason: 'OpenRouter came back without a sign-in this page started; try Connect again.',
    }));
  }
  return fetch('https://openrouter.ai/api/v1/auth/keys', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, code_verifier: verifier, code_challenge_method: 'S256' }),
  }).then((response) => response.text().then((body) => JSON.stringify({
    state: response.ok ? 'ok' : 'failed',
    status: response.status,
    body,
  }))).catch((error) => JSON.stringify({ state: 'failed', reason: String(error) }));
}"""
)
private external fun finishOpenRouterSignIn(storageKey: String): Promise<JsString>

/** One chat completion, credential-free apart from the bearer: no cookies leave for OpenRouter. */
@JsFun(
  """(url, body, key) => fetch(url, {
    method: 'POST',
    credentials: 'omit',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer ' + key,
      'X-Title': 'Compose UI Builder guidelines check',
      // No X-OpenRouter-Metadata here: OpenRouter's CORS preflight does not allow that header, so
      // a browser request carrying it is blocked outright. The served model, provider and cost
      // still come back in the body; only a router's routing reason needs the header, and the
      // server-side check (no CORS) sends it.
    },
    body,
  }).then((response) => response.text().then((text) => JSON.stringify({
    status: response.status,
    body: text,
  })))"""
)
private external fun openRouterComplete(url: String, body: String, key: String): Promise<JsString>

@JsFun(
  """(url) => fetch(url, { credentials: 'same-origin' })
    .then((response) => response.ok ? response.blob() : null)
    .then((blob) => blob ? new Promise((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => resolve('');
      reader.readAsDataURL(blob);
    }) : '')"""
)
private external fun fetchAsDataUrl(url: String): Promise<JsString>
