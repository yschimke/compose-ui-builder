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
const figmaImportUrl = new URL(
  "../../docs/design/fixtures/ui-builder/jetcaster-discover-figma-import-v1.json",
  import.meta.url,
);

function capabilities() {
  return JSON.parse(readFileSync(capabilityUrl, "utf8"));
}

function operations() {
  return JSON.parse(readFileSync(operationsUrl, "utf8"));
}

function figmaImport() {
  return JSON.parse(readFileSync(figmaImportUrl, "utf8"));
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
      ["verified", "unverified", "raster-fallback-required", "unsupported"].includes(
        component.svg.status,
      ),
    );
    if (component.svg.status === "verified") {
      assert.equal(component.svg.fallback, "none");
      assert.equal(component.svg.blocksExport, false);
    }
    if (component.svg.status === "raster-fallback-required") {
      assert.equal(component.svg.fallback, "embedded-raster");
      assert.equal(component.svg.blocksExport, false);
    }

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

  assert.equal(document.revision, 108);
  assert.equal(Object.keys(document.nodes).length, 108);
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
  assert.deepEqual(document.nodes["detail-follow"].slots.content, [
    "detail-follow-icon",
    "detail-follow-label",
  ]);
  assert.deepEqual(document.nodes["detail-episode-140-copy"].slots.children, [
    "detail-episode-140-title",
    "detail-episode-140-podcast",
    "detail-episode-140-summary",
  ]);
  assert.deepEqual(document.nodes["detail-episode-140-footer"].slots.children, [
    "detail-episode-140-play",
    "detail-episode-140-meta",
    "detail-episode-140-queue",
    "detail-episode-140-more",
  ]);
  assert.deepEqual(document.nodes["detail-episode-139-copy"].slots.children, [
    "detail-episode-139-title",
    "detail-episode-139-podcast",
    "detail-episode-139-summary",
  ]);
  assert.deepEqual(document.nodes["detail-episode-139-footer"].slots.children, [
    "detail-episode-139-play",
    "detail-episode-139-meta",
    "detail-episode-139-queue",
    "detail-episode-139-more",
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

test("real Figma import evidence remains an explicit no-go until raster parity passes", () => {
  const evidence = figmaImport();
  const { document, hash } = replayCandidateOperations(operations());
  const authoredImages = Object.values(document.nodes).filter(
    ({ componentId }) => componentId === "asset/image",
  );
  const local = evidence.latestLocalExport;
  const completed = evidence.lastCompletedFigmaImport;

  assert.equal(evidence.schemaVersion, 2);
  assert.equal(evidence.status, "no-go");
  assert.equal(local.revision, document.revision);
  assert.equal(local.documentContentSha256, hash);
  assert.equal(local.width, document.environment.widthDp);
  assert.equal(local.height, document.environment.heightDp);
  assert.equal(local.typography.textFragmentCount, 38);
  assert.equal(
    local.typography.authoredTextNodeCount,
    Object.values(document.nodes).filter(({ componentId }) => componentId === "m3/text").length,
  );
  assert.equal(local.typography.family, "Inter");
  assert.equal(local.typography.familySource, "figma-inter-adapter-v1");
  assert.equal(local.typography.materialTokenSource, "material3-token-v1");
  assert.equal(
    Object.values(local.typography.weightCounts).reduce((sum, count) => sum + count, 0),
    local.typography.textFragmentCount,
  );
  assert.equal(local.typography.nodeProvenanceCount, local.typography.textFragmentCount);
  assert.notEqual(local.svgSha256, completed.sourceExport.svgSha256);
  assert.equal(completed.figmaImport.rootWidth, completed.sourceExport.width);
  assert.equal(completed.figmaImport.rootHeight, completed.sourceExport.height);
  assert.equal(completed.figmaImport.imagePaintCount, authoredImages.length);
  assert.equal(
    Object.values(completed.figmaImport.fontFamilyCounts).reduce((sum, count) => sum + count, 0),
    completed.figmaImport.typeCounts.TEXT,
  );
  assert.equal(
    Object.values(completed.figmaImport.fontStyleCounts).reduce((sum, count) => sum + count, 0),
    completed.figmaImport.typeCounts.TEXT,
  );
  assert.ok(completed.figmaImport.typeCounts.VECTOR > 0);
  assert.deepEqual(completed.figmaImport.fontFamilyCounts, { Inter: 37 });
  assert.deepEqual(completed.figmaImport.fontStyleCounts, { Regular: 37 });
  assert.equal(completed.figmaImport.missingFontCount, 0);
  assert.equal(
    completed.comparison.mismatchRatio,
    completed.comparison.mismatchPixels / completed.comparison.totalPixels,
  );
  assert.ok(completed.comparison.mismatchRatio > 0.03);
  assert.equal(evidence.privateImportGate.status, "pending-explicit-upload-authorization");
  assert.equal(evidence.privateImportGate.sourceSvgSha256, local.svgSha256);
  assert.equal(evidence.privateImportGate.draftMutated, false);
});
