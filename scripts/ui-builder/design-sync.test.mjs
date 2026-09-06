import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";
import { documentToOperations, operationsToDocument } from "./design-sync.mjs";

const designs = new URL("../../docs/design/fixtures/ui-builder/designs/", import.meta.url);
const files = readdirSync(designs).filter((name) => name.endsWith(".json")).sort();

test("every committed design round-trips through a document and back to its own hash", () => {
  assert.ok(files.length > 0, "no designs committed");
  for (const name of files) {
    const fixture = JSON.parse(readFileSync(new URL(name, designs), "utf8"));
    assert.equal(fixture.designId, name.replace(/\.json$/, ""), `${name} is not named after its design`);
    const document = operationsToDocument(fixture);
    const again = documentToOperations(document);
    assert.equal(again.expectedDocumentHash, fixture.expectedDocumentHash, `${name} changed on the round trip`);
    assert.deepEqual(again.operations, fixture.operations, `${name} operations changed on the round trip`);
  }
});

test("a tampered hash is refused before anything reaches a server", () => {
  const fixture = JSON.parse(readFileSync(new URL(files[0], designs), "utf8"));
  assert.throws(() => operationsToDocument({ ...fixture, expectedDocumentHash: "0".repeat(64) }), /expectedDocumentHash/);
});
