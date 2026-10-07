#!/usr/bin/env node
// Fails when the shipped `uiBuilder.wasm` holds a function bigger than the limit.
//
//   node wasm-functions.mjs ../../ui-builder/build/wasmDist/uiBuilder.wasm [maxBytes]
//
// Binaryen inlines a function with one caller whatever its size, so generated tables (Material 3's
// locale strings, the Material icon builders) folded into single functions of 328 KB and 686 KB.
// V8 tiers a hot one up to TurboFan, which held 312 MB for six seconds on `getTranslation` alone
// and took the editor near 500 MB on opening a Material 3 design. `ui-builder/build.gradle.kts`
// keeps those tables out of line with `--no-inline` patterns; this is the check that they still
// match. The largest function today is ~146 KB (Wear's resource initialiser, run once).

import { readFile } from 'node:fs/promises';

export const DEFAULT_MAX_FUNCTION_BYTES = 200_000;

/** Every defined function's body size, by its index in the function index space. */
export function functionBodySizes(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (b[0] !== 0x00 || b[1] !== 0x61 || b[2] !== 0x73 || b[3] !== 0x6d) {
    throw new Error('not a WebAssembly module');
  }
  let i = 8;
  const leb = () => {
    let result = 0;
    let shift = 0;
    for (;;) {
      const byte = b[i++];
      result += (byte & 0x7f) * 2 ** shift;
      shift += 7;
      if (!(byte & 0x80)) return result;
    }
  };
  const skipValueType = () => {
    const type = b[i++];
    if (type === 0x63 || type === 0x64) leb(); // (ref null? <heaptype>)
  };
  const skipLimits = () => {
    const flags = b[i++];
    leb();
    if (flags & 1) leb();
  };
  let importedFunctions = 0;
  const sizes = [];
  while (i < b.length) {
    const id = b[i++];
    const size = leb();
    const end = i + size;
    if (id === 2) {
      const count = leb();
      for (let n = 0; n < count; n++) {
        // Not `i += leb()`: that reads `i` before `leb()` has moved it past the length.
        const moduleLength = leb();
        i += moduleLength;
        const fieldLength = leb();
        i += fieldLength;
        const kind = b[i++];
        if (kind === 0) {
          leb();
          importedFunctions++;
        } else if (kind === 1) {
          skipValueType();
          skipLimits();
        } else if (kind === 2) {
          skipLimits();
        } else if (kind === 3) {
          skipValueType();
          i++; // mutability
        } else if (kind === 4) {
          i++; // attribute
          leb();
        } else {
          throw new Error(`unknown import kind ${kind}`);
        }
      }
    } else if (id === 10) {
      const count = leb();
      for (let n = 0; n < count; n++) {
        const bodySize = leb();
        sizes.push({ index: importedFunctions + n, bytes: bodySize });
        i += bodySize;
      }
    }
    i = end;
  }
  return sizes;
}

/** The functions over [maxBytes], largest first. */
export function oversizedFunctions(bytes, maxBytes = DEFAULT_MAX_FUNCTION_BYTES) {
  return functionBodySizes(bytes)
    .filter((f) => f.bytes > maxBytes)
    .sort((a, b) => b.bytes - a.bytes);
}

/** The TurboFan compile that held the most memory, from V8's `--trace-wasm-compilation-times`. */
export function largestTurbofanCompile(log) {
  let largest = null;
  let count = 0;
  for (const m of log.matchAll(/#(\d+) using TurboFan, took (\d+) ms and (\d+) \/ \d+ max\/total bytes; bodysize (\d+)/g)) {
    count++;
    const compile = { functionIndex: Number(m[1]), ms: Number(m[2]), maxZoneBytes: Number(m[3]), bodyBytes: Number(m[4]) };
    if (!largest || compile.maxZoneBytes > largest.maxZoneBytes) largest = compile;
  }
  return largest && { ...largest, compiles: count };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [path, limit] = process.argv.slice(2);
  if (!path) {
    console.error('usage: node wasm-functions.mjs <module.wasm> [maxBytes]');
    process.exit(2);
  }
  const maxBytes = limit ? Number(limit) : DEFAULT_MAX_FUNCTION_BYTES;
  const bytes = await readFile(path);
  const largest = functionBodySizes(bytes).sort((a, b) => b.bytes - a.bytes).slice(0, 5);
  console.log(`largest functions in ${path}: ${largest.map((f) => `#${f.index} ${f.bytes} B`).join(', ')}`);
  const over = oversizedFunctions(bytes, maxBytes);
  if (over.length) {
    console.error(
      `${over.length} function(s) over ${maxBytes} bytes: ${over.map((f) => `#${f.index} ${f.bytes} B`).join(', ')}.\n` +
        'Binaryen has probably inlined a generated table into one function again. Rebuild with ' +
        '`wasm-opt -g` to name it (see docs/AGENT_TESTING.md, "Memory per screen") and add its ' +
        'callees to wasmNoInlinePatterns in ui-builder/build.gradle.kts.',
    );
    process.exit(1);
  }
  console.log(`ok   no function over ${maxBytes} bytes`);
}
