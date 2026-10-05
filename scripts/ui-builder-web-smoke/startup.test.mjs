import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createContext, runInContext } from 'node:vm';
import { test } from 'node:test';

// Execute the JS bridge that ships, including the hidden-tab fallback.
async function bridge(file, name, globals) {
  const source = await readFile(new URL(`../../ui-builder/src/wasmJsMain/kotlin/ee/schimke/composeai/uibuilder/${file}`, import.meta.url), 'utf8');
  const blocks = [...source.matchAll(/@JsFun\(\s*"""((?:(?!""")[\s\S])*)"""\s*\)\s*private external fun (\w+)/g)];
  const body = blocks.find(([, , candidate]) => candidate === name)?.[1];
  assert.ok(body, `missing bridge ${name}`);
  return runInContext(`(${body})`, createContext(globals));
}

test('optional initialization yields through two frames and completes once', async () => {
  const frames = [];
  let timer;
  let calls = 0;
  const afterPaint = await bridge('Main.kt', 'afterBrowserPaint', {
    requestAnimationFrame: callback => frames.push(callback),
    setTimeout: callback => { timer = callback; return 1; },
    clearTimeout: () => {},
  });
  afterPaint(() => calls++);
  assert.equal(calls, 0);
  frames.shift()();
  assert.equal(calls, 0);
  frames.shift()();
  assert.equal(calls, 1);
  timer();
  assert.equal(calls, 1);
});

test('hidden tabs finish even when animation frames are suspended', async () => {
  let timer;
  let calls = 0;
  const afterPaint = await bridge('Main.kt', 'afterBrowserPaint', {
    requestAnimationFrame: () => {},
    setTimeout: callback => { timer = callback; return 1; },
    clearTimeout: () => {},
  });
  afterPaint(() => calls++);
  timer();
  assert.equal(calls, 1);
});

