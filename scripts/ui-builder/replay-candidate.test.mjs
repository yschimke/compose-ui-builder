import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { replayCandidateOperations } from "./replay-candidate.mjs";

const fixtureUrl = new URL(
  "../../docs/design/fixtures/ui-builder/confetti-header-filter-operations-v1.json",
  import.meta.url,
);

function fixture() {
  return JSON.parse(readFileSync(fixtureUrl, "utf8"));
}

test("public operations build the Confetti header and filter semantic tree", () => {
  const input = fixture();
  const { document, hash } = replayCandidateOperations(input);

  assert.equal(document.revision, 10);
  assert.deepEqual(document.roots, ["screen"]);
  assert.deepEqual(document.nodes.screen.slots, {
    topBar: ["top-bar"],
    content: ["content"],
  });
  assert.deepEqual(document.nodes["track-filters"].slots.items, ["chip-all", "chip-droidcon"]);
  assert.equal(
    document.nodes["chip-droidcon"].properties.selected.variable,
    "selectedTrack",
  );
  assert.equal(document.nodes["chip-droidcon"].eventBindings.click[0].type, "selectOrClear");
  assert.equal(hash, input.expectedDocumentHash);
});

test("retries are idempotent and keep the same document hash", () => {
  const input = fixture();
  const once = replayCandidateOperations(input);
  const retried = replayCandidateOperations({
    ...input,
    operations: [...input.operations, ...input.operations],
  });

  assert.deepEqual(retried, once);
});

test("a stale insertion anchor is rejected instead of changing order", () => {
  const input = fixture();
  input.operations.push({
    operationId: "bad-anchor",
    type: "insertNode",
    parent: { nodeId: "track-filters", slot: "items" },
    afterNodeId: "missing-chip",
    node: { id: "bad", componentId: "m3/filter-chip" },
  });

  assert.throws(() => replayCandidateOperations(input), /unknown insertion anchor missing-chip/);
});
