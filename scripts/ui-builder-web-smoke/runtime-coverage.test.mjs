import assert from 'node:assert/strict';
import { test } from 'node:test';
import { classify, expectUnsupported, parseArgs, report } from './runtime-coverage-lib.mjs';

test('parses the build, the runtime, the designs and the allowances', () => {
  const options = parseArgs(['dist', 'runtime', 'a.uid', 'b.uid', '--allow', 'x/y']);
  assert.equal(options.wasmDist, 'dist');
  assert.equal(options.runtimeDir, 'runtime');
  assert.deepEqual(options.designs, ['a.uid', 'b.uid']);
  assert.deepEqual([...options.allow], ['x/y']);
  assert.throws(() => parseArgs(['dist', 'runtime']), /usage/);
  assert.throws(() => parseArgs(['dist', 'runtime', 'a.uid', '--wat']), /unknown option/);
});

test('an unsupported node fails, a stand-in passes, an allowance passes', () => {
  const canvas = { nodeId: 'c', componentId: 'draw/canvas', reason: 'unsupported' };
  const standIn = { nodeId: 's', componentId: 'pack/thing', reason: 'stand-in' };

  const failed = classify('dial.uid', [canvas, standIn]);
  assert.equal(failed.ok, false);
  assert.match(failed.reason, /cannot draw draw\/canvas/);
  assert.equal(failed.standIns.length, 1);

  assert.equal(classify('dial.uid', [standIn]).ok, true);
  assert.equal(classify('dial.uid', [canvas], new Set(['draw/canvas'])).ok, true);
});

test('a runtime that does not report fails, so an old runtime cannot pass silently', () => {
  const result = classify('dial.uid', undefined);
  assert.equal(result.ok, false);
  assert.match(result.reason, /did not report/);
});

test('the report lists failures first', () => {
  const text = report([
    classify('a.uid', []),
    classify('b.uid', [{ nodeId: 'c', componentId: 'draw/canvas', reason: 'unsupported' }]),
  ]);
  assert.deepEqual(text.split('\n').map((line) => line.slice(0, 10)), ['FAIL b.uid', 'ok   a.uid']);
});

test('an unknown reason fails rather than passing silently', () => {
  const result = classify('dial.uid', [{ nodeId: 'c', componentId: 'x/y', reason: 'unsupportd' }]);
  assert.equal(result.ok, false);
  assert.match(result.reason, /unknown reasons "unsupportd"/);
});

test('a negative control passes only on exactly the expected component', () => {
  const probe = { nodeId: 'mystery', componentId: 'nowhere/mystery', reason: 'unsupported' };
  const expected = new Set(['nowhere/mystery']);

  assert.deepEqual(expectUnsupported([classify('probe.uid', [probe])], expected), []);
  // A runtime that did not report, or refused the design, is not the detection being tested.
  assert.match(expectUnsupported([classify('probe.uid', undefined)], expected).join(), /did not report nowhere\/mystery/);
  const other = { nodeId: 'z', componentId: 'other/thing', reason: 'unsupported' };
  assert.match(expectUnsupported([classify('probe.uid', [probe, other])], expected).join(), /also reported other\/thing/);
  assert.equal(parseArgs(['d', 'r', 'p.uid', '--expect-unsupported', 'nowhere/mystery']).expect.has('nowhere/mystery'), true);
});
