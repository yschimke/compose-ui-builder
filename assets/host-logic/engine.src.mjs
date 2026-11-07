// The host-logic engine: QuickJS compiled to Wasm, so a design's program runs as data for an
// interpreter rather than as code the page evaluates. The editor's CSP allows 'wasm-unsafe-eval'
// and not 'unsafe-eval', which is the whole reason this is an interpreter and not `new Function`.
// It also gives the program no ambient authority: QuickJS has no fetch, DOM, timers or storage.
//
// The program is untrusted, and runs on the editor's main thread, so two rules hold throughout:
// - Everything that can run the program's code — loading it, reading its exports, calling it,
//   serialising what it returns, reading what it threw — runs under the interrupt deadline.
// - Nothing the host relies on is reachable by the program: the module namespace, the original
//   `JSON.parse` / `JSON.stringify` and the clock setter are handles this file holds, never globals
//   the program could redefine with a getter or a setter.
//
// Every value crosses as a JSON string, so the Kotlin side sees strings only.
import { newQuickJSWASMModuleFromVariant, shouldInterruptAfterDeadline } from "quickjs-emscripten-core";
import variant from "@jitl/quickjs-singlefile-browser-release-sync";

let engine;
const quickjs = () => (engine ??= newQuickJSWASMModuleFromVariant(variant));

const DEFAULTS = { memoryBytes: 8 << 20, stackBytes: 512 << 10, timeMs: 200 };

// Evaluated before the program, so what it captures is the engine's own. It fixes every way of
// reading the time to the design's clock (`Date.now()`, `new Date()`, `Date()`) and seeds
// `Math.random()`, and hands back the setter the host calls before each call, with the original
// JSON functions. Nothing it defines is left on the global object.
const PRELUDE = `(() => {
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
  return {
    clock: (n, s) => { now = n; seed = s | 0; },
    parse: JSON.parse,
    stringify: JSON.stringify,
    json: JSON,
  };
})()`;

class HostLogicSession {
  constructor(QuickJS, source, limits) {
    this.limits = { ...DEFAULTS, ...limits };
    this.runtime = QuickJS.newRuntime();
    this.handles = [];
    try {
      this.runtime.setMemoryLimit(this.limits.memoryBytes);
      this.runtime.setMaxStackSize(this.limits.stackBytes);
      this.vm = this.runtime.newContext();
      this.#guarded(() => {
        const prelude = this.#keep(this.#unwrap(this.vm.evalCode(PRELUDE, "prelude.js")));
        this.clock = this.#keep(this.vm.getProp(prelude, "clock"));
        this.parse = this.#keep(this.vm.getProp(prelude, "parse"));
        this.stringify = this.#keep(this.vm.getProp(prelude, "stringify"));
        this.json = this.#keep(this.vm.getProp(prelude, "json"));
        const namespace = this.#keep(
          this.#unwrap(this.vm.evalCode(source, "host-logic.mjs", { type: "module" })),
        );
        // A module namespace's exports are bindings, not properties a program can give a getter.
        this.functions = {};
        for (const name of ["init", "onAction"]) {
          const fn = this.#keep(this.vm.getProp(namespace, name));
          if (this.vm.typeof(fn) !== "function")
            throw new Error(`host logic must export a function named ${name}`);
          this.functions[name] = fn;
        }
      });
    } catch (error) {
      this.dispose();
      throw error;
    }
  }

  // Calls the program's [name] with [argsJson] (a JSON array) under the deadline, with the clock
  // at [now] and randomness seeded with [seed]. Returns the result as JSON, or throws with what the
  // program threw.
  call(name, argsJson, now, seed) {
    const fn = this.functions[name];
    if (!fn) throw new Error(`host logic has no function named ${name}`);
    return this.#guarded(() => {
      const scratch = [];
      const keep = (handle) => (scratch.push(handle), handle);
      try {
        this.#unwrap(
          this.vm.callFunction(
            this.clock,
            this.vm.undefined,
            keep(this.vm.newNumber(now)),
            keep(this.vm.newNumber(seed | 0)),
          ),
        ).dispose();
        const args = keep(
          this.#unwrap(
            this.vm.callFunction(this.parse, this.json, keep(this.vm.newString(argsJson))),
          ),
        );
        const count = this.vm.getNumber(keep(this.vm.getProp(args, "length")));
        const spread = [];
        for (let i = 0; i < count; i++) spread.push(keep(this.vm.getProp(args, i)));
        const result = keep(this.#unwrap(this.vm.callFunction(fn, this.vm.undefined, ...spread)));
        const json = keep(this.#unwrap(this.vm.callFunction(this.stringify, this.json, result)));
        return this.vm.typeof(json) === "string" ? this.vm.getString(json) : "null";
      } finally {
        scratch.forEach((handle) => handle.alive && handle.dispose());
      }
    });
  }

  dispose() {
    for (const handle of this.handles) if (handle.alive) handle.dispose();
    this.handles = [];
    if (this.vm?.alive) this.vm.dispose();
    if (this.runtime?.alive) this.runtime.dispose();
  }

  #keep(handle) {
    this.handles.push(handle);
    return handle;
  }

  #guarded(block) {
    this.runtime.setInterruptHandler(shouldInterruptAfterDeadline(Date.now() + this.limits.timeMs));
    try {
      return block();
    } finally {
      this.runtime.removeInterruptHandler();
    }
  }

  // Called inside #guarded, so a thrown object whose `name` or `message` is a looping getter is
  // interrupted like any other code the program wrote.
  #unwrap(result) {
    if (!result.error) return result.value;
    const error = result.error;
    let message;
    try {
      const read = (key) => {
        const handle = this.vm.getProp(error, key);
        try {
          return this.vm.typeof(handle) === "string" ? this.vm.getString(handle) : undefined;
        } finally {
          handle.dispose();
        }
      };
      const kind = this.vm.typeof(error);
      if (kind === "string") message = this.vm.getString(error);
      else if (kind === "number" || kind === "boolean" || kind === "undefined")
        message = `host logic threw ${String(this.vm.dump(error))}`;
      else {
        // A getter that throws or runs out of time leaves no string here, not an exception: what
        // `getProp` hands back is then the error itself, so only a real string is trusted.
        const name = read("name");
        const text = read("message");
        message =
          text !== undefined
            ? `${name ?? "Error"}: ${text}`
            : "host logic threw an object that is not an Error";
      }
    } catch {
      message = "host logic threw something that cannot be read";
    } finally {
      error.dispose();
    }
    throw new Error(message);
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
