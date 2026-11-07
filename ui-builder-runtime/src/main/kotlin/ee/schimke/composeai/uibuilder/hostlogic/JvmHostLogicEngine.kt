package ee.schimke.composeai.uibuilder.hostlogic

import ee.schimke.composeai.uibuilder.export.HostLogicProgram
import io.roastedroot.quickjs4j.core.Builtins
import io.roastedroot.quickjs4j.core.Engine
import io.roastedroot.quickjs4j.core.Runner
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import run.endive.runtime.ByteArrayMemory
import run.endive.wasm.types.MemoryLimits

/**
 * The JVM's host-logic engine: QuickJS ([quickjs4j](https://github.com/roastedroot/quickjs4j),
 * Javy's build compiled ahead of time to JVM bytecode), so a design's program runs on the server as
 * data for an interpreter, with no native code, no `fetch`, no file system and no timers.
 *
 * It answers the way the browser's engine (`assets/host-logic/engine.src.mjs`) does: the same
 * prelude fixes every way of reading the time to the design's clock and seeds `Math.random()`, the
 * same messages describe what a program threw, and `docs/design/fixtures/ui-builder/
 * host-logic-parity-v1.json` is the set of answers both are tested against.
 *
 * Every load and call runs on the program's own thread under a deadline. A call that overruns it is
 * interrupted, which leaves that interpreter unusable, so the program is loaded again on a fresh
 * one before its next call: the state a run keeps is the one it hands back, so a reload loses
 * nothing a reducer may rely on.
 */
internal class JvmHostLogicEngine(
  private val callBudgetMs: Int = DEFAULT_CALL_BUDGET_MS,
  private val loadBudgetMs: Int = DEFAULT_LOAD_BUDGET_MS,
  private val memoryPages: Int = DEFAULT_MEMORY_PAGES,
) {
  /** Loads [source] as a host-logic module: the program, or why it cannot run. */
  fun load(source: String): Result<JvmHostLogicProgram> = runCatching {
    JvmHostLogicProgram(source, callBudgetMs, loadBudgetMs, memoryPages).also { it.start() }
  }

  companion object {
    /**
     * Generous beside the browser's 200 ms: a cold JVM interprets the engine before it compiles.
     */
    const val DEFAULT_CALL_BUDGET_MS: Int = 1_000
    const val DEFAULT_LOAD_BUDGET_MS: Int = 5_000

    /** 16 MiB of linear memory: the browser's 8 MiB QuickJS heap, plus the interpreter itself. */
    const val DEFAULT_MEMORY_PAGES: Int = 256
  }
}

/** The program overran its budget: the run stops rather than paying the budget again. */
internal class HostLogicBudgetExceeded(message: String) : RuntimeException(message)

internal class JvmHostLogicProgram(
  private val source: String,
  private val callBudgetMs: Int,
  private val loadBudgetMs: Int,
  private val memoryPages: Int,
) : HostLogicProgram {
  private var instance: Instance? = null
  private var request: String = "{}"
  private var answer: String? = null

  /** One interpreter with the program loaded, and the thread it runs on. */
  private inner class Instance {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
      Thread(task, "host-logic-${threads.incrementAndGet()}").apply { isDaemon = true }
    }
    val runner: Runner =
      Runner.builder()
        .withEngine(
          Engine.builder()
            .withMemoryFactory { limits ->
              ByteArrayMemory(MemoryLimits(limits.initialPages(), memoryPages))
            }
            .addBuiltins(
              Builtins.builder(HOST)
                .addVoidToString("request") { request }
                .addStringToVoid("answer") { answer = it }
                .build(),
            )
            .build(),
        )
        // No timeout of its own: the runner then runs on the calling thread, which is already
        // this instance's, under the deadline `withDeadline` holds.
        .build()

    fun close() {
      runner.close()
      executor.shutdownNow()
    }
  }

  fun start() {
    val fresh = Instance()
    try {
      withDeadline(fresh, loadBudgetMs) {
        run(fresh, PRELUDE, "host logic did not load")
        run(fresh, source + EPILOGUE, "host logic did not load")
        // Loaded, so it parses; now that both are exported, not only declared.
        EXPORTS.forEach { requireExported(fresh, it) }
      }
      instance = fresh
    } catch (error: Throwable) {
      fresh.close()
      throw error
    }
  }

  /**
   * Refuses a program that declares [name] without exporting it, which the browser's engine (it
   * reads the module namespace) refuses too. A module may not export one name twice, so the program
   * with `export { name };` appended compiles only when it never exported it. The probe re-exports
   * the program's own binding, which loading has shown is declared, so it adds no name the program
   * could already be using.
   */
  private fun requireExported(instance: Instance, name: String) {
    val probe = "$source\n;export { $name };\n"
    val exported =
      try {
        instance.runner.compile(probe)
        false
      } catch (error: Throwable) {
        val message = reported(error.message.orEmpty(), instance.runner.stderr()).orEmpty()
        if (!message.contains("duplicate exported name")) throw HostLogicError(message)
        true
      } finally {
        instance.runner.clearOutput()
      }
    if (!exported) throw HostLogicError("host logic must export a function named $name")
  }

  override fun call(function: String, argumentsJson: String, now: Long, seed: Int): Result<String> =
    runCatching {
      val current = instance ?: reload()
      request =
        buildJsonObject {
          put("function", function)
          put("arguments", argumentsJson)
          put("now", now)
          put("seed", seed)
        }
          .toString()
      answer = null
      withDeadline(current, callBudgetMs) { run(current, CALL, "host logic failed") }
      val reply =
        answer?.let { Json.parseToJsonElement(it) as? JsonObject }
          ?: error("host logic did not answer")
      (reply["error"] as? JsonPrimitive)?.contentOrNull?.let { throw HostLogicError(it) }
      (reply["result"] as? JsonPrimitive)?.contentOrNull ?: "null"
    }

  override fun close() {
    instance?.close()
    instance = null
  }

  private fun reload(): Instance {
    start()
    return instance!!
  }

  /**
   * Runs [block] on [current]'s thread, bounded by [budgetMs]. An overrun interrupts the thread,
   * which leaves the interpreter unusable, so [current] is closed and the next call loads afresh.
   */
  private fun <T> withDeadline(current: Instance, budgetMs: Int, block: () -> T): T {
    val future = current.executor.submit<T> { block() }
    return try {
      future.get(budgetMs.toLong(), TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      future.cancel(true)
      current.close()
      if (instance === current) instance = null
      throw HostLogicBudgetExceeded(
        "host logic ran past its $budgetMs ms budget, and was interrupted",
      )
    } catch (error: ExecutionException) {
      throw error.cause ?: error
    }
  }

  /**
   * Compiles and runs [code] on [instance], turning the interpreter's own report of a syntax error,
   * a thrown value or an overrun into one line saying what happened.
   */
  private fun run(instance: Instance, code: String, failure: String) {
    try {
      instance.runner.compileAndExec(code)
    } catch (error: HostLogicError) {
      throw error
    } catch (error: Throwable) {
      throw HostLogicError(reported(error.message.orEmpty(), instance.runner.stderr()) ?: failure)
    } finally {
      instance.runner.clearOutput()
    }
  }

  private companion object {
    val threads = AtomicInteger()

    /** The builtins module the harness reaches the host through. */
    const val HOST = "__hostLogicHost"

    /**
     * Evaluated before the program, on the same interpreter, so what it captures is the engine's
     * own. The clock and the seeded `Math.random()` are the browser engine's prelude, line for
     * line; the call itself is a non-writable global the program cannot replace.
     */
    val PRELUDE =
      """
      (() => {
        let now = 0;
        let seed = 1;
        const RealDate = Date;
        function FixedDate(...args) {
          if (!new.target) return new RealDate(now).toString();
          return args.length === 0 ? new RealDate(now) : new RealDate(...args);
        }
        FixedDate.now = () => now;
        FixedDate.parse = RealDate.parse;
        FixedDate.UTC = RealDate.UTC;
        FixedDate.prototype = RealDate.prototype;
        Object.defineProperty(RealDate.prototype, "constructor", { value: FixedDate });
        globalThis.Date = FixedDate;
        Math.random = () => {
          let t = (seed = (seed + 0x6d2b79f5) | 0);
          t = Math.imul(t ^ (t >>> 15), t | 1);
          t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
          return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
        };
        const host = globalThis.$HOST;
        const parse = JSON.parse;
        const stringify = JSON.stringify;
        const describe = (error) => {
          const kind = typeof error;
          if (kind === "string") return error;
          if (kind === "number" || kind === "boolean" || kind === "undefined")
            return "host logic threw " + String(error);
          if (error === null) return "host logic threw null";
          const name = error.name;
          const message = error.message;
          if (typeof message !== "string") return "host logic threw an object that is not an Error";
          return (typeof name === "string" ? name : "Error") + ": " + message;
        };
        let program;
        Object.defineProperty(globalThis, "__hostLogicExports", {
          value: (exports) => { if (program === undefined) program = exports; },
        });
        Object.defineProperty(globalThis, "__hostLogicCall", {
          value: () => {
            let reply;
            try {
              const request = parse(host.request());
              now = request.now;
              seed = request.seed | 0;
              const fn = program && program[request.function];
              if (typeof fn !== "function")
                throw new Error("host logic must export a function named " + request.function);
              const result = stringify(fn(...parse(request.arguments)));
              reply = { result: typeof result === "string" ? result : "null" };
            } catch (error) {
              let message;
              try { message = describe(error); } catch { message = "host logic threw something that cannot be read"; }
              reply = { error: message };
            }
            host.answer(stringify(reply));
          },
        });
      })();
      """
        .trimIndent()

    /**
     * Appended to the program, in its module scope, where its exported declarations are in scope:
     * it hands the host `init` and `onAction`, once. A program missing either fails to load, as it
     * does in the browser.
     */
    val EPILOGUE =
      """

      ;__hostLogicExports({
        init: typeof init === "function" ? init : undefined,
        onAction: typeof onAction === "function" ? onAction : undefined,
      });
      if (typeof init !== "function") throw new Error("host logic must export a function named init");
      if (typeof onAction !== "function") throw new Error("host logic must export a function named onAction");
      """
        .trimIndent()

    const val CALL = "__hostLogicCall();"

    val EXPORTS = listOf("init", "onAction")

    /** The interpreter's message for a syntax error or a throw at load: `Error: expecting ';'`. */
    fun reported(message: String, stderr: String): String? =
      (message.lineSequence() + stderr.lineSequence())
        .mapNotNull { line -> line.substringAfter("`Err` value: ", "").takeIf { it.isNotBlank() } }
        .firstOrNull()
        // The harness's own refusals are Errors it threw; the browser reports them unprefixed.
        ?.removePrefix("Error: host logic")
        ?.let { if (it.startsWith(" ")) "host logic$it" else it }
  }
}

/** What a program threw, or why it could not load, in the words the browser's engine uses. */
internal class HostLogicError(message: String) : RuntimeException(message)
