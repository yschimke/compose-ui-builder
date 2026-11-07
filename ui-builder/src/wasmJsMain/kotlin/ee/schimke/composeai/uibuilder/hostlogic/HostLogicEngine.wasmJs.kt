@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.hostlogic

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * QuickJS compiled to Wasm, from `assets/host-logic/engine.mjs` (see its README). Imported the
 * first time a design with host logic opens, so the editor does not load it for a design without
 * any.
 */
internal actual val platformHostLogicEngine: HostLogicEngine? = QuickJsHostLogicEngine

private object QuickJsHostLogicEngine : HostLogicEngine {
  override suspend fun load(source: String): Result<HostLogicProgram> {
    val reply = envelope(awaitString(loadHostLogicPromise(source)))
    val handle = (reply["handle"] as? JsonPrimitive)?.intOrNull
    return if (handle != null) Result.success(QuickJsProgram(handle))
    else Result.failure(HostLogicException(reply.error()))
  }
}

private class QuickJsProgram(private val handle: Int) : HostLogicProgram {
  override fun call(function: String, argumentsJson: String, now: Long, seed: Int): Result<String> {
    val reply = envelope(callHostLogic(handle, function, argumentsJson, now.toDouble(), seed))
    val result = (reply["result"] as? JsonPrimitive)?.contentOrNull
    return if (result != null) Result.success(result)
    else Result.failure(HostLogicException(reply.error()))
  }

  override fun close() = disposeHostLogic(handle)
}

private class HostLogicException(message: String) : Exception(message)

private fun envelope(json: String): JsonObject =
  runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
    ?: JsonObject(mapOf("error" to JsonPrimitive("the host-logic engine answered `$json`")))

private fun JsonObject.error(): String =
  (this["error"] as? JsonPrimitive)?.contentOrNull ?: "the host-logic engine failed"

private suspend fun awaitString(promise: kotlin.js.Promise<JsString>): String =
  suspendCancellableCoroutine { continuation ->
    promise.then { value ->
      if (continuation.isActive) continuation.resume(value.toString())
      null
    }
  }

/**
 * Imports the engine once, loads [source] into a session of its own, and resolves to `{"handle":
 * n}` or `{"error": "…"}`. Never rejects, so an error keeps its message across the Wasm boundary.
 */
@JsFun(
  """(source) => {
    const g = (globalThis.__uiBuilderHostLogic ??= { next: 1, sessions: new Map(), engine: null });
    g.engine ??= import('./host-logic/engine.mjs');
    const message = (error) => String(error && error.message ? error.message : error);
    return g.engine
      .then((engine) => engine.loadHostLogic(source, ''))
      .then(
        (session) => {
          const handle = g.next++;
          g.sessions.set(handle, session);
          return JSON.stringify({ handle });
        },
        (error) => JSON.stringify({ error: message(error) }),
      );
  }""",
)
private external fun loadHostLogicPromise(source: String): kotlin.js.Promise<JsString>

/** Calls a loaded session: `{"result": "<json>"}` or `{"error": "…"}`, never a throw. */
@JsFun(
  """(handle, name, args, now, seed) => {
    const session = globalThis.__uiBuilderHostLogic?.sessions.get(handle);
    if (!session) return JSON.stringify({ error: 'host logic is not loaded' });
    try {
      return JSON.stringify({ result: session.call(name, args, now, seed) });
    } catch (error) {
      return JSON.stringify({ error: String(error && error.message ? error.message : error) });
    }
  }""",
)
private external fun callHostLogic(
  handle: Int,
  name: String,
  args: String,
  now: Double,
  seed: Int,
): String

@JsFun(
  """(handle) => {
    const g = globalThis.__uiBuilderHostLogic;
    const session = g?.sessions.get(handle);
    if (session) {
      g.sessions.delete(handle);
      session.dispose();
    }
  }""",
)
private external fun disposeHostLogic(handle: Int)
