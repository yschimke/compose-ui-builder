import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const html = readFileSync(new URL("../../ui-builder-renderer/src/wasmJsMain/resources/index.html", import.meta.url), "utf8");
const match = html.match(/<script id="renderer-cache-capability">([\s\S]*?)<\/script>/);
assert.ok(match, "renderer shell must guard Cache Storage before Compose starts");
assert.ok(match.index < html.indexOf('rel="modulepreload"'));

const available = { open() {} };
const regular = vm.createContext({ caches: available });
vm.runInContext(match[1], regular);
assert.equal(regular.caches, available, "available Cache Storage must remain intact");

const opaque = vm.createContext({});
vm.runInContext(`Object.defineProperty(globalThis, "caches", {
  configurable: true,
  get() { throw Object.assign(new Error("opaque origin"), { name: "SecurityError" }); },
});`, opaque);
assert.throws(() => vm.runInContext("globalThis.caches", opaque));
vm.runInContext(match[1], opaque);
assert.equal(vm.runInContext("Boolean(globalThis.caches)", opaque), false);

const missing = vm.createContext({});
vm.runInContext(match[1], missing);
assert.equal(missing.caches, undefined);

const unexpected = vm.createContext({});
vm.runInContext(`Object.defineProperty(globalThis, "caches", { get() { throw new Error("unexpected failure"); } });`, unexpected);
assert.throws(() => vm.runInContext(match[1], unexpected), /unexpected failure/);
console.log("Renderer Cache Storage capability: 4 cases passed");
