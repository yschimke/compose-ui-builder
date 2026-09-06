import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";
import { documentToOperations, operationsToDocument, publishDirectory } from "./design-sync.mjs";

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

test("the published directory is the shape the server's design library reads", () => {
  const fixtures = files.map((name) => ({
    fixture: JSON.parse(readFileSync(new URL(name, designs), "utf8")),
  }));
  const published = publishDirectory(fixtures);
  const index = JSON.parse(published.index);

  // The schema string is the one ServeUiBuilderDesignLibrary requires; a mismatch there means the
  // server refuses the whole index rather than reading part of it.
  assert.equal(index.schema, "compose-ui-builder-design-index/v1");
  assert.equal(index.designs.length, files.length);

  const byFile = new Map(published.files.map((f) => [f.file, f.body]));
  for (const entry of index.designs) {
    assert.ok(entry.id && entry.title && entry.file, `incomplete index entry ${JSON.stringify(entry)}`);
    assert.match(entry.id, /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/, `${entry.id} is not a usable design id`);
    assert.match(entry.file, /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}\.json$/, `${entry.file} is not a usable file`);

    const document = JSON.parse(byFile.get(entry.file));
    assert.equal(document.id, entry.id, `${entry.file} holds a different design`);
    assert.equal(document.revision, 0, "a published design is offered at revision 0");
    assert.ok(document.catalogPin?.systemId, `${entry.file} has no catalog pin`);
    // The export gate refuses a document without one, so a published design that lacks it would
    // load and then be unable to produce Kotlin.
    assert.ok(document.environment?.density > 0, `${entry.file} pins no density`);
  }
});
