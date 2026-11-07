// The host-logic engine (assets/host-logic/engine.mjs) as the editor uses it: a design's program
// runs, and what the program does cannot outlast its budget, reach the engine's own machinery or
// read the real clock. The editor calls it on its main thread, so a case that hangs here would
// freeze the editor.
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { callHostLogic, disposeHostLogic, loadHostLogic } from '../../assets/host-logic/engine.mjs';

const BUDGET = '{"timeMs":100}';
const NOW = 1715860800000; // 2024-05-16T12:00:00Z, a design's fixed time

async function init(body, args = '[{}]') {
  const session = await loadHostLogic(`export function init(env) { ${body} } export function onAction() {}`, BUDGET);
  try {
    return JSON.parse(callHostLogic(session, 'init', args, NOW, 1));
  } finally {
    disposeHostLogic(session);
  }
}

function within(ms, block) {
  const start = Date.now();
  return block().finally(() => assert.ok(Date.now() - start < ms, `took ${Date.now() - start} ms`));
}

test('a reducer answers each action from the state it returned', async () => {
  const session = await loadHostLogic(
    `export function init(env) { return { state: { n: 0 }, values: { count: "0" } }; }
     export function onAction(state, action) {
       const n = state.n + (action.value ?? 1);
       return { state: { n }, values: { count: String(n) } };
     }`,
    '',
  );
  let { state } = JSON.parse(callHostLogic(session, 'init', '[{}]', NOW, 1));
  for (const value of [1, 2, 3]) {
    ({ state } = JSON.parse(
      callHostLogic(session, 'onAction', JSON.stringify([state, { name: 'add', value }, {}]), NOW, 1),
    ));
  }
  assert.deepEqual(state, { n: 6 });
  disposeHostLogic(session);
});

test('a program that cannot run says why', async () => {
  await assert.rejects(loadHostLogic('export function init() {}', ''), /export a function named onAction/);
  await assert.rejects(loadHostLogic('this is not ( javascript', ''), /SyntaxError/);
  await assert.rejects(init('return fetch("https://example.com")'), /'fetch' is not defined/);
  await assert.rejects(init('throw new TypeError("bad")'), /TypeError: bad/);
  await assert.rejects(init('throw "boom"'), /boom/);
});

test('nothing a program does outlasts its budget', async () => {
  await within(1000, () => assert.rejects(init('for (;;) {}'), /interrupted/));
  await within(1000, () => assert.rejects(init('return { toJSON() { for (;;) {} } }'), /interrupted/));
  await within(1000, () => assert.rejects(init('throw { get message() { for (;;) {} } }'), /not an Error/));
  await within(1000, () =>
    assert.rejects(
      loadHostLogic('for (;;) {} export function init() {} export function onAction() {}', BUDGET),
      /interrupted/,
    ),
  );
});

test("a program cannot redefine what the engine relies on", async () => {
  // The engine keeps the module namespace and JSON as its own handles, so neither a looping getter
  // on a global nor a replaced JSON reaches it.
  const loop = 'get() { for (;;) {} }, set() {}, configurable: true';
  await within(1000, async () => {
    const session = await loadHostLogic(
      `Object.defineProperty(globalThis, "__hostLogic", { ${loop} });
       Object.defineProperty(globalThis, "JSON", { ${loop} });
       export function init() { return { values: { ok: 1 } }; }
       export function onAction() {}`,
      BUDGET,
    );
    assert.deepEqual(JSON.parse(callHostLogic(session, 'init', '[{}]', NOW, 1)), { values: { ok: 1 } });
    disposeHostLogic(session);
  });
});

test("every way of reading the time reads the design's clock, and randomness is seeded", async () => {
  const clock = await init(
    'return { now: Date.now(), made: +new Date(), viaCtor: +new (new Date().constructor)(), ' +
      'text: Date().slice(0, 15), epoch: new Date(0).getTime() }',
  );
  assert.deepEqual(clock, { now: NOW, made: NOW, viaCtor: NOW, text: 'Thu May 16 2024', epoch: 0 });
  assert.deepEqual(await init('return [Math.random(), Math.random()]'), await init('return [Math.random(), Math.random()]'));
});

test('a load that fails releases what it allocated', async () => {
  // Each attempt allocates a megabyte before failing, in the Wasm heap every session shares; the
  // engine still answers afterwards.
  for (let i = 0; i < 100; i++) {
    await assert.rejects(loadHostLogic("const a = 'x'.repeat(1 << 20); export function init() {}", ''));
  }
  assert.deepEqual(await init('return { ok: true }'), { ok: true });
});
