import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { checkMemory, checkSizes, globToRegExp } from './budgets.mjs';

const MB = 1024 * 1024;

test('globs match within a segment with * and across segments with **', () => {
  assert.ok(globToRegExp('uiBuilder.wasm').test('uiBuilder.wasm'));
  assert.ok(!globToRegExp('uiBuilder.wasm').test('uiBuilderXwasm'));
  assert.ok(globToRegExp('*.mjs').test('skiko.mjs'));
  assert.ok(!globToRegExp('*.mjs').test('icons/a.mjs'));
  assert.ok(globToRegExp('**').test('icons/icons-0.json'));
  assert.ok(globToRegExp('icons/**').test('icons/icons-0.json'));
});

test('a size group fails over either limit, or when it matches nothing', () => {
  const files = [
    { path: 'uiBuilder.wasm', bytes: 15 * MB, gzipBytes: 4 * MB },
    { path: 'icons/icons-0.json', bytes: 1 * MB, gzipBytes: 0.1 * MB },
  ];
  const [fits, raw, gzip, missing, all] = checkSizes(files, [
    { name: 'fits', files: 'uiBuilder.wasm', maxMb: 15.5, maxGzipMb: 4.1 },
    { name: 'raw', files: 'uiBuilder.wasm', maxMb: 14.9 },
    { name: 'gzip', files: 'uiBuilder.wasm', maxGzipMb: 3.9 },
    { name: 'missing', files: 'skiko.wasm', maxMb: 9 },
    { name: 'all', files: '**', maxMb: 16.5 },
  ]);
  assert.deepEqual(fits.problems, []);
  assert.equal(raw.problems.length, 1);
  assert.equal(gzip.problems.length, 1);
  assert.deepEqual(missing.problems, ['no file matches skiko.wasm']);
  assert.equal(all.files, 2);
  assert.equal(all.bytes, 16 * MB);
  assert.deepEqual(all.problems, []);
});

test('a memory budget fails over its cap, or when its screen was not measured', () => {
  const report = {
    results: [{ viewport: 'desktop', scenario: 'designs', settled: { rendererPss: 170 * MB, jsHeapUsed: 24 * MB } }],
  };
  const [fits, over, unmeasured] = checkMemory(report, [
    { viewport: 'desktop', scenario: 'designs', maxMb: { rendererPss: 180, jsHeapUsed: 30 } },
    { viewport: 'desktop', scenario: 'designs', maxMb: { rendererPss: 160 } },
    { viewport: 'mobile', scenario: 'designs', maxMb: { rendererPss: 200 } },
  ]);
  assert.deepEqual(fits.problems, []);
  assert.deepEqual(over.problems, ['rendererPss 170.00 MB > 160 MB']);
  assert.deepEqual(unmeasured.problems, ['mobile designs was not measured']);
});

test('the committed budgets name a size limit and only metrics memory.mjs reports', async () => {
  const budgets = JSON.parse(await readFile(new URL('./budgets.json', import.meta.url), 'utf8'));
  for (const size of budgets.sizes) assert.ok(size.maxMb !== undefined || size.maxGzipMb !== undefined, size.name);
  const reported = new Set(['jsHeapUsed', 'rendererPss', 'gpuPss', 'totalPss']);
  for (const memory of budgets.memory) {
    for (const metric of Object.keys(memory.maxMb)) assert.ok(reported.has(metric), metric);
  }
});
