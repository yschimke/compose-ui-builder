import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  catalogPinKey,
  isDesignReply,
  needsGrant,
  parseArgs,
  replaceDocument,
} from './editor-shot-lib.mjs';

const SCHEMA = 'compose-ui-builder-document/v1-candidate';

test('parses the positionals and options', () => {
  const options = parseArgs([
    'https://ui.coo.ee/tipsy-goose',
    'out.png',
    '--viewport',
    '412x915',
    '--document',
    'rev20.json',
    '--steps',
    '[["click",1,2]]',
  ]);
  assert.equal(options.url, 'https://ui.coo.ee/tipsy-goose');
  assert.equal(options.output, 'out.png');
  assert.deepEqual(options.viewport, { width: 412, height: 915 });
  assert.equal(options.document, 'rev20.json');
  assert.deepEqual(options.steps, [['click', 1, 2]]);
  assert.equal(options.settle, 10_000);
});

test('refuses a missing output and an unknown option', () => {
  assert.throws(() => parseArgs(['https://ui.coo.ee/x']), /usage/);
  assert.throws(() => parseArgs(['u', 'o', '--zoom', '2']), /unknown option --zoom/);
  assert.throws(() => parseArgs(['u', 'o', '--viewport', 'big']), /WxH/);
});

test('matches the design read and protocol requests only', () => {
  assert.ok(isDesignReply('https://ui.coo.ee/api/ui-builder/v1/designs/tipsy-goose'));
  assert.ok(isDesignReply('https://ui.coo.ee/api/ui-builder/v1/requests'));
  assert.ok(!isDesignReply('https://ui.coo.ee/api/ui-builder/v1/designs/tipsy-goose/comments'));
  assert.ok(!isDesignReply('https://ui.coo.ee/tipsy-goose'));
});

test('sends the grant to the API and MCP only, never to the editor files', () => {
  assert.ok(needsGrant('https://ui.coo.ee/api/ui-builder/v1/designs/tipsy-goose'));
  assert.ok(needsGrant('https://ui.coo.ee/mcp'));
  assert.ok(!needsGrant('https://ui.coo.ee/tipsy-goose'));
  assert.ok(!needsGrant('https://ui.coo.ee/ui-builder/v/f0ca518c13521eb3/uiBuilder.wasm'));
  assert.ok(!needsGrant('https://ui.coo.ee/ui-builder/runtime/remote-m3-p3/runtime-manifest.json'));
  assert.ok(!needsGrant('https://ui.coo.ee/mcpx'));
});

test('replaces the design document wherever it is nested, keeping the host revision', () => {
  const pin = { systemId: 'remote-m3', catalogRevision: 'sha256:new' };
  const sent = { schema: SCHEMA, id: 'tipsy-goose', revision: 23, catalogPin: pin, nodes: { a: {} } };
  const other = { schema: SCHEMA, id: 'other', revision: 4, nodes: {} };
  const body = { response: { snapshot: { state: { document: sent } }, others: [other] } };
  const past = {
    schema: SCHEMA,
    id: 'tipsy-goose',
    revision: 20,
    catalogPin: { systemId: 'remote-m3', catalogRevision: 'sha256:old' },
    nodes: { canvas: {} },
  };

  const result = replaceDocument(body, past);
  assert.equal(result.replaced, 1);
  // The caller compares these with the replacement's pin to warn of a catalog mismatch.
  assert.deepEqual(result.hostPins, ['remote-m3@sha256:new']);
  assert.notEqual(catalogPinKey(past), result.hostPins[0]);
  const replaced = body.response.snapshot.state.document;
  assert.deepEqual(replaced.nodes, { canvas: {} });
  assert.equal(replaced.revision, 23);
  assert.equal(body.response.others[0], other);
});
