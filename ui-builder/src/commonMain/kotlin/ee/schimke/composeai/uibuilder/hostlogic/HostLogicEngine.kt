package ee.schimke.composeai.uibuilder.hostlogic

import ee.schimke.composeai.uibuilder.export.HostLogicProgram

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

/** This platform's engine, or null where host logic does not run (the JVM hosts, for now). */
internal expect val platformHostLogicEngine: HostLogicEngine?
