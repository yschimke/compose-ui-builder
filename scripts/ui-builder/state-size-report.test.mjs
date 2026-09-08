import assert from "node:assert/strict";
import test from "node:test";

import {
  DESIGN_SECTIONS,
  analyzeUiBuilderState,
  formatReport,
  projectRetention,
} from "./state-size-report.mjs";

function document(id, revision, nodeCount) {
  const nodes = {};
  for (let index = 0; index < nodeCount; index += 1) {
    nodes[`node-${index}`] = {
      id: `node-${index}`,
      component: "androidx.compose.material3.Text",
      properties: { text: { type: "string", value: `label ${index}` } },
      slots: {},
    };
  }
  return { schema: "ui-builder/v1", id, title: id, revision, roots: ["node-0"], nodes, assets: {} };
}

/** A stored design shaped like `PersistedDesignV1`: a current document and the retained lists. */
function design(id, { revision = 40, nodeCount = 12, retained = 40 } = {}) {
  const current = document(id, revision, nodeCount);
  return {
    document: current,
    lastSequence: revision,
    access: { ownerId: "owner", entries: [] },
    history: [],
    revisionSnapshots: Array.from({ length: retained }, (_, index) => ({
      document: document(id, index, nodeCount),
      sequence: index,
    })),
    operationOutcomes: {},
    acceptedOperations: {},
    tombstones: {},
    positions: {},
    positionSnapshots: Array.from({ length: retained }, (_, index) => ({
      revision: index,
      positions: { "node-0": { key: { path: [0], tieBreaker: "node-0" } } },
    })),
    createdAtEpochMillis: 1,
    updatedAtEpochMillis: 2,
    audit: [],
  };
}

function envelopeV2(designs) {
  return {
    format: "ui-builder-persistence-v2",
    checksumSha256: "0".repeat(64),
    payload: { service: { designs }, catalogPins: {} },
  };
}

test("the report attributes bytes to the design and the section that spend them", () => {
  const root = envelopeV2({
    big: design("big", { nodeCount: 40, retained: 60 }),
    small: design("small", { nodeCount: 2, retained: 3 }),
  });

  const report = analyzeUiBuilderState(root);

  assert.equal(report.format, "ui-builder-persistence-v2");
  assert.equal(report.designCount, 2);
  assert.deepEqual(
    report.designs.map((it) => it.id),
    ["big", "small"],
    "designs are reported largest first",
  );
  assert.equal(report.designs[0].nodes, 40);
  assert.equal(report.designs[0].sections.revisionSnapshots.count, 60);

  // Retained snapshots, not the live document, are where a design's bytes go.
  assert.ok(
    report.sectionTotals.revisionSnapshots > report.sectionTotals.document * 10,
    "retained documents should dominate the live one by an order of magnitude",
  );

  const summed = report.designs.reduce((sum, it) => sum + it.bytes, 0);
  assert.equal(report.designBytes, summed);
  assert.ok(report.overheadBytes > 0, "the envelope around the designs is accounted for");
});

test("every named section is measured, and unnamed fields land in otherBytes", () => {
  const report = analyzeUiBuilderState(envelopeV2({ one: design("one") }));
  const measured = report.designs[0];

  for (const section of DESIGN_SECTIONS) {
    assert.ok(section in measured.sections, `${section} is measured`);
  }
  const sectionBytes = Object.values(measured.sections).reduce((sum, it) => sum + it.bytes, 0);
  assert.equal(measured.otherBytes, measured.bytes - sectionBytes);
  assert.ok(measured.otherBytes > 0, "the scalar fields and key names are not silently dropped");
});

test("a v1 envelope puts the service directly in payload and is read the same way", () => {
  const designs = { one: design("one") };
  const v1 = {
    format: "ui-builder-persistence-v1",
    checksumSha256: "0".repeat(64),
    payload: { designs },
  };

  const report = analyzeUiBuilderState(v1);

  assert.equal(report.format, "ui-builder-persistence-v1");
  assert.equal(report.designCount, 1);
  assert.equal(report.designs[0].id, "one");
});

test("the retention projection scales only the two snapshot lists", () => {
  const report = analyzeUiBuilderState(envelopeV2({ one: design("one", { retained: 100 }) }));

  const projection = projectRetention(report, { keep: 10 });

  assert.equal(
    projection.snapshotBytes,
    report.sectionTotals.revisionSnapshots + report.sectionTotals.positionSnapshots,
  );
  // Nine tenths of the snapshots go; everything else is untouched.
  assert.equal(projection.projectedTotalBytes, report.totalBytes - projection.savedBytes);
  assert.equal(projection.affectedDesigns, 1);
  assert.ok(projection.savedBytes > projection.snapshotBytes * 0.8);
  assert.ok(projection.savedBytes < projection.snapshotBytes);
});

test("a store whose designs are all shallower than the cut saves nothing", () => {
  // The bug this replaced: the projection scaled by keep/retained on the assumption that every
  // design sat at the configured depth, and reported a 7.66 MB saving against a live store whose
  // designs were at revision 1-20 and would have given back nothing.
  const report = analyzeUiBuilderState(
    envelopeV2({ one: design("one", { retained: 4 }), two: design("two", { retained: 20 }) }),
  );

  const projection = projectRetention(report, { keep: 64 });

  assert.equal(projection.savedBytes, 0);
  assert.equal(projection.projectedTotalBytes, report.totalBytes);
  assert.equal(projection.affectedDesigns, 0);
});

test("only the designs deeper than the cut contribute to the saving", () => {
  const report = analyzeUiBuilderState(
    envelopeV2({ deep: design("deep", { retained: 80 }), shallow: design("shallow", { retained: 5 }) }),
  );

  const projection = projectRetention(report, { keep: 40 });

  assert.equal(projection.affectedDesigns, 1, "the shallow design is untouched by the cut");
  const deep = report.designs.find((it) => it.id === "deep");
  const deepSnapshots =
    deep.sections.revisionSnapshots.bytes + deep.sections.positionSnapshots.bytes;
  // Half of the deep design's snapshots, and none of the shallow one's.
  assert.ok(projection.savedBytes > deepSnapshots * 0.4);
  assert.ok(projection.savedBytes < deepSnapshots * 0.6);
});

test("the printed report names the ceiling, the sections and the worst design", () => {
  const report = analyzeUiBuilderState(envelopeV2({ big: design("big", { retained: 50 }) }));

  const text = formatReport(report, { maximumBytes: 32 * 1024 * 1024 });

  assert.match(text, /of 32\.00 MB/);
  assert.match(text, /revisionSnapshots/);
  assert.match(text, /big\s+\d+\.\d\d MB/);
  assert.match(text, /already retains fewer than 64 revisions, so cutting revision retention/);
});

test("a file that is not a state envelope is refused by name", () => {
  assert.throws(() => analyzeUiBuilderState({ hello: "world" }), /no payload/);
  assert.throws(() => analyzeUiBuilderState({ payload: {} }), /no designs map/);
  assert.throws(() => analyzeUiBuilderState(null), /not a JSON object/);
});
