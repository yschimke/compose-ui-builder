package ee.schimke.composeai.uibuilder.hostlogic

/**
 * Runs a design's host logic ([ee.schimke.composeai.uibuilder.export.HostLogic]). An engine only
 * evaluates: what it is handed and what it hands back are checked by `HostLogic`, so engines cannot
 * disagree about what a program may do.
 *
 * The browser's engine is QuickJS compiled to Wasm (`assets/host-logic`), because the editor's page
 * allows `'wasm-unsafe-eval'` and not `'unsafe-eval'`: the program is data for an interpreter, and
 * it has no fetch, DOM, timers or storage to reach for.
 */
interface HostLogicEngine {
  /** Loads [source] as a host-logic module: the program, or why it cannot run. */
  suspend fun load(source: String): Result<HostLogicProgram>
}

/** One loaded program, holding its own interpreter until [close]. */
interface HostLogicProgram {
  /**
   * Calls the program's [function] (`init` or `onAction`) with [argumentsJson], a JSON array the
   * engine spreads. [now] is what the program's `Date.now()` reads and [seed] seeds its
   * `Math.random()`, so a call is reproducible. Returns the result as JSON, or why the call failed
   * — the program threw, ran past its time budget, or ran out of memory.
   */
  fun call(function: String, argumentsJson: String, now: Long, seed: Int): Result<String>

  fun close()
}

/** This platform's engine, or null where host logic does not run (the JVM hosts, for now). */
internal expect val platformHostLogicEngine: HostLogicEngine?
