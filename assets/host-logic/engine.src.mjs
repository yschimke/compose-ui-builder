// The host-logic engine: QuickJS compiled to Wasm, so a design's program runs as data for an
// interpreter rather than as code the page evaluates. The editor's CSP allows 'wasm-unsafe-eval'
// and not 'unsafe-eval', which is the whole reason this is an interpreter and not `new Function`.
// It also gives the program no ambient authority: QuickJS has no fetch, DOM, timers or storage.
//
// Every value crosses as a JSON string, so the Kotlin side sees strings only.
import { newQuickJSWASMModuleFromVariant, shouldInterruptAfterDeadline } from "quickjs-emscripten-core";
import variant from "@jitl/quickjs-singlefile-browser-release-sync";

let engine;
const quickjs = () => (engine ??= newQuickJSWASMModuleFromVariant(variant));

const DEFAULTS = { memoryBytes: 8 << 20, stackBytes: 512 << 10, timeMs: 200 };

// Deterministic stand-ins for the clock and randomness, reset before every call from env.
const PRELUDE = `
  globalThis.__seed = 1;
  Math.random = () => {
    let t = (globalThis.__seed = (globalThis.__seed + 0x6d2b79f5) | 0);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  Date.now = () => globalThis.__now;
`;

class HostLogicSession {
  constructor(QuickJS, source, limits) {
    this.limits = { ...DEFAULTS, ...limits };
    this.runtime = QuickJS.newRuntime();
    this.runtime.setMemoryLimit(this.limits.memoryBytes);
    this.runtime.setMaxStackSize(this.limits.stackBytes);
    this.vm = this.runtime.newContext();
    this.#guarded(() => {
      this.#unwrap(this.vm.evalCode(PRELUDE, "prelude.js")).dispose();
      const namespace = this.#unwrap(this.vm.evalCode(source, "host-logic.mjs", { type: "module" }));
      this.vm.setProp(this.vm.global, "__hostLogic", namespace);
      namespace.dispose();
    });
    for (const name of ["init", "onAction"]) {
      if (this.#evalJson(`typeof globalThis.__hostLogic.${name}`) !== "function")
        throw new Error(`host logic must export a function named ${name}`);
    }
  }

  // Calls the program's [name] with [argsJson] (a JSON array), under the time budget. [envJson]
  // seeds the clock and randomness. Returns the result as JSON, or throws with the program's error.
  call(name, argsJson, now, seed) {
    return this.#guarded(() => {
      const args = this.vm.newString(argsJson);
      this.vm.setProp(this.vm.global, "__args", args);
      args.dispose();
      const time = this.vm.newNumber(now);
      this.vm.setProp(this.vm.global, "__now", time);
      time.dispose();
      const s = this.vm.newNumber(seed | 0);
      this.vm.setProp(this.vm.global, "__seed", s);
      s.dispose();
      return this.#evalJson(
        `JSON.stringify(globalThis.__hostLogic[${JSON.stringify(name)}](...JSON.parse(globalThis.__args)) ?? null)`,
      );
    });
  }

  dispose() {
    this.vm.dispose();
    this.runtime.dispose();
  }

  #guarded(block) {
    this.runtime.setInterruptHandler(shouldInterruptAfterDeadline(Date.now() + this.limits.timeMs));
    try {
      return block();
    } finally {
      this.runtime.removeInterruptHandler();
    }
  }

  #evalJson(code) {
    const handle = this.#unwrap(this.vm.evalCode(code, "call.js"));
    try {
      return this.vm.dump(handle);
    } finally {
      handle.dispose();
    }
  }

  #unwrap(result) {
    if (result.error) {
      const error = this.vm.dump(result.error);
      result.error.dispose();
      const message =
        error && typeof error === "object"
          ? `${error.name ?? "Error"}: ${error.message ?? JSON.stringify(error)}`
          : String(error);
      throw new Error(message);
    }
    return result.value;
  }
}

// Loads [source] as a host-logic module. Resolves to a session, or rejects with why it cannot run.
export async function loadHostLogic(source, limitsJson) {
  const QuickJS = await quickjs();
  return new HostLogicSession(QuickJS, source, limitsJson ? JSON.parse(limitsJson) : {});
}

export function callHostLogic(session, name, argsJson, now, seed) {
  return session.call(name, argsJson, now, seed);
}

export function disposeHostLogic(session) {
  session.dispose();
}
