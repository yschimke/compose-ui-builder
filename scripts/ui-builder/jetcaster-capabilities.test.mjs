import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { replayCandidateOperations } from "./replay-candidate.mjs";

const capabilityUrl = new URL(
  "../../docs/design/fixtures/ui-builder/jetcaster-discover-capabilities-v1.json",
  import.meta.url,
);
const operationsUrl = new URL(
  "../../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json",
  import.meta.url,
);

function capabilities() {
  return JSON.parse(readFileSync(capabilityUrl, "utf8"));
}

function operations() {
  return JSON.parse(readFileSync(operationsUrl, "utf8"));
}

test("Jetcaster benchmark capabilities are deterministic and generic", () => {
  const manifest = capabilities();
  const ids = manifest.components.map(({ componentId }) => componentId);

  assert.equal(manifest.schema, "compose-ui-builder-capabilities/v1-candidate");
  assert.equal(manifest.benchmark.id, "jetcaster-discover-expanded");
  assert.equal(new Set(ids).size, ids.length, "component ids must be unique");
  assert.deepEqual(ids, [...ids].sort(), "component ids must remain sorted");
  assert.equal(
    ids.some((id) => /jetcaster/i.test(id)),
    false,
    "the benchmark must not introduce a screen-shaped Jetcaster component",
  );
});

test("every capability declares slots, Wasm support, code mapping, and SVG policy", () => {
  for (const component of capabilities().components) {
    assert.ok(["Scaffold", "Container", "Leaf"].includes(component.role));
    assert.ok(Array.isArray(component.slots));
    assert.ok(
      typeof component.wasm.platformSupported === "boolean" ||
        component.wasm.platformSupported === "unverified",
    );
    assert.ok(["supported", "planned", "unsupported"].includes(component.wasm.adapterStatus));
    assert.equal(typeof component.code.symbol, "string");
    assert.ok(component.code.symbol.length > 0);
    assert.ok(Array.isArray(component.code.imports));
    assert.ok(
      ["unverified", "raster-fallback-required", "unsupported"].includes(
        component.svg.status,
      ),
    );

    for (const slot of component.slots) {
      assert.equal(typeof slot.name, "string");
      assert.ok(slot.name.length > 0);
      assert.ok(slot.cardinality.min >= 0);
      if (slot.cardinality.max !== null) {
        assert.ok(slot.cardinality.max >= slot.cardinality.min);
      }
    }
  }
});

test("unimplemented Jetcaster gaps remain visible instead of silently substituted", () => {
  const manifest = capabilities();
  const gaps = manifest.components
    .filter(({ wasm }) => wasm.adapterStatus !== "supported")
    .map(({ componentId }) => componentId);

  assert.ok(gaps.includes("layout/supporting-pane-scaffold"));
  assert.ok(gaps.includes("layout/lazy-grid"));
  assert.ok(gaps.includes("layout/horizontal-carousel"));
  assert.ok(gaps.includes("asset/image"));
  assert.ok(gaps.includes("m3/search-bar"));
  assert.ok(gaps.includes("m3/horizontal-floating-toolbar"));
});

test("public operations build the Jetcaster expanded two-pane semantic tree", () => {
  const input = operations();
  const { document, hash } = replayCandidateOperations(input);

  assert.equal(document.revision, 99);
  assert.equal(Object.keys(document.nodes).length, 99);
  assert.deepEqual(document.roots, ["root-surface"]);
  assert.deepEqual(document.nodes["pane-scaffold"].slots, {
    mainPane: ["main-background"],
    supportingPane: ["detail-scaffold"],
  });
  assert.deepEqual(document.nodes["main-scaffold"].slots, {
    topBar: ["search-bar"],
    snackbarHost: ["snackbar-host"],
    content: ["main-content"],
  });
  assert.deepEqual(document.nodes["main-content"].slots.children, [
    "discover-grid",
    "floating-toolbar",
  ]);
  assert.equal(hash, input.expectedDocumentHash);
});

test("every Jetcaster node resolves through the pinned capability manifest", () => {
  const { document } = replayCandidateOperations(operations());
  const known = new Set(capabilities().components.map(({ componentId }) => componentId));
  const unresolved = [...new Set(Object.values(document.nodes).map(({ componentId }) => componentId))]
    .filter((componentId) => !known.has(componentId))
    .sort();

  assert.deepEqual(unresolved, []);
});

test("Jetcaster operation fixture is deterministic and contains no external asset URL", () => {
  const input = operations();
  const once = replayCandidateOperations(input);
  const retried = replayCandidateOperations({
    ...input,
    operations: [...input.operations, ...input.operations],
  });

  assert.deepEqual(retried, once);
  assert.equal(/https?:\/\//i.test(JSON.stringify(input)), false);
});
