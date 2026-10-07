import assert from 'node:assert/strict';
import { test } from 'node:test';
import { functionBodySizes, largestTurbofanCompile, oversizedFunctions } from './wasm-functions.mjs';

const uleb = (value) => {
  const out = [];
  do {
    let byte = value & 0x7f;
    value >>>= 7;
    if (value) byte |= 0x80;
    out.push(byte);
  } while (value);
  return out;
};
const section = (id, body) => [id, ...uleb(body.length), ...body];
const name = (text) => [...uleb(text.length), ...Buffer.from(text)];
// A body of exactly [size] bytes: no locals, nops, `end`.
const body = (size) => [0x00, ...Array(size - 2).fill(0x01), 0x0b];

// Two imported functions and an imported `(ref null extern)` global before three defined
// functions, so the defined ones are #2, #3 and #4 and the global's heap type must be skipped.
function module(sizes) {
  const bytes = [0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00];
  bytes.push(...section(1, [1, 0x60, 0, 0]));
  bytes.push(
    ...section(2, [
      3,
      ...name('env'), ...name('a'), 0x00, 0,
      ...name('env'), ...name('g'), 0x03, 0x63, 0x6f, 0x00,
      ...name('env'), ...name('b'), 0x00, 0,
    ]),
  );
  bytes.push(...section(3, [sizes.length, ...sizes.map(() => 0)]));
  const bodies = sizes.flatMap((size) => [...uleb(size), ...body(size)]);
  bytes.push(...section(10, [sizes.length, ...bodies]));
  bytes.push(...section(0, [...name('name'), 0x00]));
  return new Uint8Array(bytes);
}

test('reads body sizes in the function index space, after the imported functions', () => {
  const sizes = functionBodySizes(module([4, 300, 20]));
  assert.deepEqual(sizes, [
    { index: 2, bytes: 4 },
    { index: 3, bytes: 300 },
    { index: 4, bytes: 20 },
  ]);
});

test('reports only the functions over the limit, largest first', () => {
  const over = oversizedFunctions(module([250, 4, 400]), 200);
  assert.deepEqual(over, [
    { index: 4, bytes: 400 },
    { index: 2, bytes: 250 },
  ]);
  assert.deepEqual(oversizedFunctions(module([4, 20]), 200), []);
});

test('rejects bytes that are not a module', () => {
  assert.throws(() => functionBodySizes(new Uint8Array([1, 2, 3, 4, 1, 0, 0, 0])), /not a WebAssembly module/);
});

// Lines as Chromium prints them with `--js-flags=--trace-wasm-compilation-times`.
const compileLog = [
  'Compiled function 0x3f1c006b4998#2276 using Liftoff, took 0 ms and 19232 bytes; bodysize 16 codesize 88',
  'Compiled function 0x3f1c006b4998#2276 using TurboFan, took 0 ms and 61552 / 158864 max/total bytes; bodysize 16 codesize 124 name wasm-function#2276',
  'Compiled function 0x3f1c006b4998#31953 using TurboFan, took 5763 ms and 327586632 / 884303696 max/total bytes; bodysize 327764 codesize 2710044 name wasm-function#31953',
  'Compiled function 0x3f1c006b4998#28754 using TurboFan, took 1034 ms and 164210736 / 373193928 max/total bytes; bodysize 686175 codesize 1304768 name wasm-function#28754',
].join('\n');

test('finds the TurboFan compile that held the most memory, ignoring Liftoff', () => {
  assert.deepEqual(largestTurbofanCompile(compileLog), {
    functionIndex: 31953,
    ms: 5763,
    maxZoneBytes: 327586632,
    bodyBytes: 327764,
    compiles: 3,
  });
  assert.equal(largestTurbofanCompile('Compiled function 0x1#0 using Liftoff, took 0 ms and 1 bytes; bodysize 4 codesize 8'), null);
});
