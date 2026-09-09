import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  cpSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  DESIGN_SECTIONS,
  analyzeUiBuilderState,
  analyzeUiBuilderStore,
  defaultMaximumBytes,
  formatReport,
  isDesignStore,
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

test("a growing design's saving is the prefix that goes, not an average of the whole", () => {
  // Retention removes the OLDEST snapshots. On a design that grew, those are the small ones, so
  // scaling the whole section by `keep / count` charged the cut for bytes it would never reclaim —
  // and the overstatement is worst on exactly the designs a cut is aimed at.
  const growing = design("growing", { retained: 20 });
  growing.revisionSnapshots = Array.from({ length: 20 }, (_, index) => ({
    // One node at revision 0 up to twenty at revision 19.
    document: document("growing", index, index + 1),
    sequence: index,
  }));
  const report = analyzeUiBuilderState(envelopeV2({ growing }));
  const section = report.designs[0].sections.revisionSnapshots;

  const projection = projectRetention(report, { keep: 15 });

  // The five oldest revision snapshots, summed exactly, plus the five position snapshots (which
  // are uniform, so both readings agree there).
  const droppedRevisions = section.entryBytes
    .slice(0, 5)
    .reduce((sum, it) => sum + it, 0);
  const positions = report.designs[0].sections.positionSnapshots;
  const droppedPositions = positions.entryBytes.slice(0, 5).reduce((sum, it) => sum + it, 0);
  assert.equal(projection.savedBytes, droppedRevisions + droppedPositions);

  // And it is materially less than what scaling by the average claimed, which is the whole point.
  const averaged =
    Math.round(section.bytes * (1 - 15 / 20)) + Math.round(positions.bytes * (1 - 15 / 20));
  assert.ok(
    projection.savedBytes < averaged * 0.8,
    `prefix ${projection.savedBytes} should be well under the averaged ${averaged}`,
  );
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

/** `FileUiBuilderDesignStore.slug`: the directory a design id addresses. */
function slugOf(id) {
  return createHash("sha256").update(id).digest("hex").slice(0, 32);
}

/** A per-design store on disk, written the way `FileUiBuilderDesignStore` writes one. */
function storeDirectory(designs) {
  const root = mkdtempSync(join(tmpdir(), "ui-builder-store-"));
  writeFileSync(join(root, "store.json"), JSON.stringify({ format: "ui-builder-store-v3" }));
  for (const [id, spec] of Object.entries(designs)) {
    const slug = slugOf(id);
    const designDirectory = join(root, "designs", slug);
    mkdirSync(join(designDirectory, "revisions"), { recursive: true });
    const part = (name, payload) => {
      writeFileSync(join(designDirectory, name), JSON.stringify({ checksumSha256: "x", payload }));
      return name;
    };
    const documentFile = part("document-aaaa.json", spec.document);
    const positionsFile = part("positions-aaaa.json", { positions: {} });
    const revisionFiles = {};
    spec.revisions.forEach((revision, index) => {
      const name = `revisions/${index}-aaaa.json`;
      part(name, { revision: index, sequence: index, document: revision, positions: {} });
      revisionFiles[String(index)] = name;
    });
    // Two records where the second supersedes the first: the slack a compaction would reclaim.
    const record = (entry) => `${JSON.stringify({ checksumSha256: "x", entry })}\n`;
    const journal =
      record({ outcomesPut: { "op-1": { fingerprint: "a", outcome: spec.outcome } } }) +
      record({ outcomesPut: { "op-1": { fingerprint: "b", outcome: spec.outcome } } }) +
      (spec.journalTail ?? "");
    writeFileSync(join(designDirectory, "journal-1.jsonl"), journal);
    writeFileSync(
      join(designDirectory, "design.json"),
      JSON.stringify({
        checksumSha256: "x",
        payload: {
          designId: id,
          title: id,
          revision: spec.document.revision,
          lastSequence: spec.document.revision,
          access: { accessRevision: 0, ownerActorId: "owner" },
          catalogPin: { systemId: "m3" },
          createdAtEpochMillis: 0,
          updatedAtEpochMillis: 0,
          documentFile,
          positionsFile,
          revisionFiles,
          journalFile: "journal-1.jsonl",
          journalBytes: Buffer.byteLength(journal, "utf8"),
          journalCompactedBytes: 64,
        },
      }),
    );
  }
  return root;
}

test("the per-design store reports the same sections the one file did", () => {
  const root = storeDirectory({
    checkout: {
      document: document("checkout", 12, 20),
      revisions: [document("checkout", 10, 20), document("checkout", 11, 20)],
      outcome: { operationId: "op-1", revision: 12 },
    },
    settings: {
      document: document("settings", 2, 3),
      revisions: [document("settings", 1, 3)],
      outcome: { operationId: "op-1", revision: 2 },
    },
  });

  assert.ok(isDesignStore(root), "a directory with a marker is read as the store");
  const report = analyzeUiBuilderStore(root);

  assert.equal(report.format, "ui-builder-store-v3");
  assert.equal(report.designCount, 2);
  assert.equal(report.designs[0].id, "checkout", "the largest design is reported first");
  assert.equal(report.designs[0].sections.revisionSnapshots.count, 2);
  assert.equal(report.designs[0].sections.positionSnapshots.count, 2);
  assert.equal(
    report.designs[0].sections.operationOutcomes.count,
    1,
    "the journal is replayed, so a superseded record is not counted twice",
  );
  assert.ok(
    report.designs[0].otherBytes > 0,
    "and what the journal spends beyond what it still says is visible as slack",
  );
  rmSync(root, { recursive: true, force: true });
});

test("the store's own revisions are measured per entry, oldest first", () => {
  // The per-design store is the production format, so a projection that falls back to the average
  // here is one that is wrong everywhere it is actually run.
  const root = storeDirectory({
    growing: {
      document: document("growing", 6, 24),
      // Five revisions, growing: the oldest are the small ones retention would drop.
      revisions: [
        document("growing", 1, 2),
        document("growing", 2, 6),
        document("growing", 3, 12),
        document("growing", 4, 18),
        document("growing", 5, 24),
      ],
      outcome: { operationId: "op-1", revision: 6 },
    },
  });

  const report = analyzeUiBuilderStore(root);
  const section = report.designs[0].sections.revisionSnapshots;

  assert.equal(section.entryBytes.length, 5, "one measurement per retained revision");
  assert.deepEqual(
    [...section.entryBytes].sort((left, right) => left - right),
    section.entryBytes,
    "recorded oldest-first, so the prefix a cut drops is the front of this list",
  );
  assert.equal(
    section.entryBytes.reduce((sum, it) => sum + it, 0),
    section.bytes,
    "and the per-entry sizes still add up to the section",
  );

  // Keeping three drops the two smallest, which is well under two fifths of the section.
  const projection = projectRetention(report, { keep: 3 });
  const dropped = section.entryBytes[0] + section.entryBytes[1];
  const positions = report.designs[0].sections.positionSnapshots;
  assert.equal(
    projection.savedBytes,
    dropped + positions.entryBytes[0] + positions.entryBytes[1],
  );
  assert.ok(
    projection.savedBytes < Math.round((section.bytes + positions.bytes) * (2 / 5)),
    "the averaged reading would have claimed two fifths of both sections",
  );
  rmSync(root, { recursive: true, force: true });
});

test("a state directory with no marker is not mistaken for the store", () => {
  const root = mkdtempSync(join(tmpdir(), "ui-builder-store-"));
  assert.equal(isDesignStore(root), false);
  rmSync(root, { recursive: true, force: true });
});

test("a store with a marker and no designs yet reports an empty deployment", () => {
  const root = mkdtempSync(join(tmpdir(), "ui-builder-store-"));
  writeFileSync(join(root, "store.json"), JSON.stringify({ format: "ui-builder-store-v3" }));

  const report = analyzeUiBuilderStore(root);

  assert.equal(report.designCount, 0);
  assert.equal(report.designBytes, 0);
  rmSync(root, { recursive: true, force: true });
});

test("a history trimmed to nothing is counted as nothing", () => {
  // An asset write clears the history: the store encodes that as an empty append with keep 0, and
  // both are falsy — read as booleans the report would go on counting every superseded record.
  const root = storeDirectory({
    checkout: {
      document: document("checkout", 3, 2),
      revisions: [],
      outcome: { operationId: "op-1", revision: 3 },
      journalTail:
        `${JSON.stringify({
          checksumSha256: "x",
          entry: { historySet: [{ big: "x".repeat(500) }] },
        })}\n` +
        `${JSON.stringify({ checksumSha256: "x", entry: { historyAppend: [], historyKeep: 0 } })}\n`,
    },
  });

  const report = analyzeUiBuilderStore(root);

  assert.equal(report.designs[0].sections.history.count, 0);
  assert.equal(report.designs[0].sections.history.bytes, 2, "an empty list, and nothing else");
  rmSync(root, { recursive: true, force: true });
});

test("each store is measured against its own ceiling", () => {
  // 128 MiB is what the single file refused a write at; the per-design store is gauged at 1 GiB, and
  // reporting one against the other calls an ordinary deployment 80% full.
  assert.equal(defaultMaximumBytes({ format: "ui-builder-store-v3" }), 1024 * 1024 * 1024);
  assert.equal(
    defaultMaximumBytes({ format: "compose-preview-ui-builder-service/v2" }),
    128 * 1024 * 1024,
  );
});

test("a tombstone is charged for its bytes and not tabulated as a design", () => {
  const root = storeDirectory({
    checkout: {
      document: document("checkout", 2, 4),
      revisions: [],
      outcome: { operationId: "op-1", revision: 2 },
    },
  });
  // A delete commits by renaming the design out of the way; the unlink that follows is cleanup and
  // can be interrupted. What is left is not a design — the store retries the unlink on the next
  // open — so counting it as one would double a recreated id and report deleted designs as live.
  const live = join(root, "designs", readdirSync(join(root, "designs"))[0]);
  const tombstone = join(root, "designs", ".deleted", "checkout-1700000000000");
  cpSync(live, tombstone, { recursive: true });
  writeFileSync(join(tombstone, "leftover.json"), "x".repeat(4096));

  const report = analyzeUiBuilderStore(root);

  assert.equal(report.designCount, 1, "the tombstone is not a design");
  assert.deepEqual(
    report.designs.map((design) => design.id),
    ["checkout"],
    "and the id it holds is not reported twice",
  );
  assert.ok(
    report.totalBytes > report.designBytes + 4000,
    "but the disk it still holds is charged",
  );
  rmSync(root, { recursive: true, force: true });
});

test("a quarantined design is counted but not tabulated, header or no header", () => {
  const root = storeDirectory({
    checkout: {
      document: document("checkout", 2, 4),
      revisions: [],
      outcome: { operationId: "op-1", revision: 2 },
    },
    settings: {
      document: document("settings", 1, 2),
      revisions: [],
      outcome: { operationId: "op-2", revision: 1 },
    },
  });
  // The usual quarantine is a missing or corrupt part under a header that still reads: the store
  // excludes any directory carrying the record, so a report that only checked the header would
  // tabulate a design the host does not serve, with whatever sections survived.
  const broken = join(root, "designs", slugOf("settings"));
  writeFileSync(join(broken, "quarantine.json"), JSON.stringify({ reason: "document missing" }));

  const report = analyzeUiBuilderStore(root);

  assert.deepEqual(
    report.designs.map((design) => design.id),
    ["checkout"],
    "the quarantined design is not a row",
  );
  assert.ok(report.overheadBytes > 0, "but its bytes are still charged");
  rmSync(root, { recursive: true, force: true });
});

test("a design under a name that is not its address is not a second row", () => {
  const root = storeDirectory({
    checkout: {
      document: document("checkout", 2, 4),
      revisions: [],
      outcome: { operationId: "op-1", revision: 2 },
    },
  });
  // The slug is the address: the store quarantines a design restored or copied under another
  // basename, and decides that from the name rather than by writing a record. Trusting the header
  // here would report the same designId twice, once from a directory the host does not serve.
  const canonical = join(root, "designs", slugOf("checkout"));
  cpSync(canonical, join(root, "designs", "restored-checkout"), { recursive: true });

  const report = analyzeUiBuilderStore(root);

  assert.deepEqual(
    report.designs.map((design) => design.id),
    ["checkout"],
    "one design, from the directory that addresses it",
  );
  assert.ok(report.overheadBytes > 0, "and the copy's bytes are still charged");
  rmSync(root, { recursive: true, force: true });
});

test("a header claiming a journal larger than a design may be is not allocated", () => {
  const root = storeDirectory({
    checkout: {
      document: document("checkout", 2, 4),
      revisions: [],
      outcome: { operationId: "op-1", revision: 2 },
    },
  });
  // The length comes out of a header, so a corrupt or hand-edited one can ask for more memory than
  // there is. The store bounds it against the per-design limit before reading; a diagnostic that
  // died on a store the host quarantines calmly would be no diagnostic.
  const designDirectory = join(root, "designs", slugOf("checkout"));
  const headerPath = join(designDirectory, "design.json");
  const stored = JSON.parse(readFileSync(headerPath, "utf8"));
  stored.payload.journalBytes = 64 * 1024 * 1024 + 1;
  writeFileSync(headerPath, JSON.stringify(stored));

  const report = analyzeUiBuilderStore(root);

  assert.equal(report.designCount, 1, "the design is still reported");
  assert.equal(
    report.designs[0].sections.history.count,
    0,
    "with nothing replayed out of a journal it will not read",
  );
  rmSync(root, { recursive: true, force: true });
});

test("a design whose header will not parse is still counted", () => {
  const root = storeDirectory({
    checkout: {
      document: document("checkout", 2, 4),
      revisions: [],
      outcome: { operationId: "op-1", revision: 2 },
    },
  });
  const broken = join(root, "designs", "0".repeat(32));
  mkdirSync(broken, { recursive: true });
  writeFileSync(join(broken, "design.json"), "not json");
  writeFileSync(join(broken, "document-aaaa.json"), "x".repeat(4096));

  const report = analyzeUiBuilderStore(root);

  assert.equal(report.designCount, 1, "it has no sections to tabulate");
  assert.ok(
    report.totalBytes > report.designBytes + 4000,
    "but its bytes are on the disk and are counted",
  );
  rmSync(root, { recursive: true, force: true });
});
