import assert from 'node:assert/strict';
import { test } from 'node:test';
import { isDesignReply, parseArgs, replaceDocument } from './editor-shot-lib.mjs';

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

test('replaces the design document wherever it is nested, keeping the host revision', () => {
  const sent = { schema: SCHEMA, id: 'tipsy-goose', revision: 23, nodes: { a: {} } };
  const other = { schema: SCHEMA, id: 'other', revision: 4, nodes: {} };
  const body = { response: { snapshot: { state: { document: sent } }, others: [other] } };
  const past = { schema: SCHEMA, id: 'tipsy-goose', revision: 20, nodes: { canvas: {} } };

  assert.equal(replaceDocument(body, past), 1);
  const replaced = body.response.snapshot.state.document;
  assert.deepEqual(replaced.nodes, { canvas: {} });
  assert.equal(replaced.revision, 23);
  assert.equal(body.response.others[0], other);
});
