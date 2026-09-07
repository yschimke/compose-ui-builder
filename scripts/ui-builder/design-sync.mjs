#!/usr/bin/env node

import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { candidateDocumentHash, replayCandidateOperations } from "./replay-candidate.mjs";

/**
 * Move a design between a committed operations fixture and a live `compose-preview serve` host,
 * over the server's own `/mcp` endpoint (`ui_builder_get_design` / `ui_builder_create_design`).
 *
 *   design-sync.mjs export <designId> --server <url> --out <fixture.json>
 *   design-sync.mjs import <fixture.json> --server <url> [--design-id <id>] [--title <title>]
 *
 * The bearer is read from `COMPOSE_PREVIEW_TOKEN` (or the older `COMPOSE_PREVIEW_UI_BUILDER_TOKEN`
 * this script used to read alone), never from an argument, so it cannot land in a shell history or
 * a CI log. Both names are accepted here and by `compose-preview-server design`, which is how the
 * two spellings were reconciled rather than a third being invented — see that command's
 * `DesignCommand.TOKEN_ENV`. `export` needs `ui-builder-read`; `import` needs
 * `ui-builder-write`. Import always creates: a live design's revision log is the collaboration
 * record and a file is a snapshot of one revision, so an existing id is refused by the server rather
 * than overwritten here.
 */

/** A `DesignDocumentV1` → the `compose-ui-builder-operations/v1-candidate` fixture shape. */
export function documentToOperations(document, { designId = document.id, title = document.title } = {}) {
  const operations = [
    {
      operationId: "create",
      type: "createDesign",
      title,
      catalogPin: clone(document.catalogPin),
      environment: clone(document.environment),
      stateVariables: clone(document.stateVariables ?? {}),
    },
  ];
  // Only when the design has one, the way a node's `properties` are emitted only when it has some:
  // the replay defaults an absent registry to `{}`, so a design with no assets round-trips to the
  // operations it was written as rather than growing an empty key.
  if (document.assets && Object.keys(document.assets).length > 0) {
    operations[0].assets = clone(document.assets);
  }
  // The replay inserts an anchorless node at the front of its slot, so every sibling after the
  // first names the one before it; without that a slot replays in reverse.
  const visit = (nodeId, parent, afterNodeId) => {
    const node = document.nodes[nodeId];
    if (!node) throw new Error(`document names a node it does not hold: ${nodeId}`);
    const inserted = { id: node.id, componentId: node.componentId };
    if (node.properties && Object.keys(node.properties).length > 0) inserted.properties = clone(node.properties);
    if (node.modifiers && node.modifiers.length > 0) inserted.modifiers = clone(node.modifiers);
    if (node.eventBindings && Object.keys(node.eventBindings).length > 0) {
      inserted.eventBindings = clone(node.eventBindings);
    }
    const operation = { operationId: `insert-${nodeId}`, type: "insertNode", parent, node: inserted };
    if (afterNodeId) operation.afterNodeId = afterNodeId;
    operations.push(operation);
    for (const [slot, children] of Object.entries(node.slots ?? {})) {
      children.forEach((child, index) => visit(child, { nodeId, slot }, index > 0 ? children[index - 1] : null));
    }
  };
  (document.roots ?? []).forEach((root, index) => visit(root, null, index > 0 ? document.roots[index - 1] : null));

  const fixture = {
    schema: "compose-ui-builder-operations/v1-candidate",
    documentSchema: "compose-ui-builder-document/v1-candidate",
    designId,
    operations,
  };
  const replayed = replayCandidateOperations(fixture);
  return { ...fixture, expectedDocumentHash: replayed.hash, operations };
}

/** The fixture → the whole `DesignDocumentV1` that `ui_builder_create_design` accepts. */
export function operationsToDocument(fixture, { designId = fixture.designId, title } = {}) {
  const { document, hash } = replayCandidateOperations(fixture);
  if (fixture.expectedDocumentHash && fixture.expectedDocumentHash !== hash) {
    throw new Error(`${fixture.designId}: replays to ${hash}, not expectedDocumentHash ${fixture.expectedDocumentHash}`);
  }
  const nodes = Object.fromEntries(
    Object.entries(document.nodes).map(([id, node]) => [id, { ...node, assetBindings: node.assetBindings ?? {} }]),
  );
  return { ...document, id: designId, title: title ?? document.title, revision: 0, nodes };
}

async function mcpCall(server, tool, args, token) {
  const response = await fetch(new URL("/mcp", server), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json, text/event-stream",
      "MCP-Protocol-Version": "2025-06-18",
      ...(token ? { Authorization: `Bearer ${token}`, "X-Compose-Preview-Token": token } : {}),
    },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: tool, arguments: args } }),
  });
  let raw = await response.text();
  if (raw.startsWith("event:") || raw.startsWith("data:")) {
    raw = raw.split("\n").find((line) => line.startsWith("data:")).slice(5);
  }
  const message = JSON.parse(raw);
  if (message.error) throw new Error(`${tool}: ${JSON.stringify(message.error)}`);
  const text = message.result.content.find((part) => part.type === "text")?.text ?? "";
  if (message.result.isError) throw new Error(`${tool}: ${text}`);
  const envelope = JSON.parse(text);
  const reply = envelope.response ?? envelope;
  if (reply.type === "error") throw new Error(`${tool}: ${reply.error?.message ?? text}`);
  return reply;
}

function flag(argv, name) {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : undefined;
}

/**
 * Write the convention directory a catalog project publishes designs from:
 * `<out>/index.json` plus one `DesignDocumentV1` per fixture. The server reads exactly this
 * (`ServeUiBuilderDesignLibrary`), so a project generates it into its `design-artifacts` branch
 * beside everything else it publishes.
 */
export function publishDirectory(fixtures) {
  const designs = [];
  const files = [];
  for (const { fixture, description } of fixtures) {
    const document = operationsToDocument(fixture);
    const file = `${document.id}.json`;
    designs.push({
      id: document.id,
      title: document.title,
      file,
      ...(description ? { description } : {}),
    });
    files.push({ file, body: `${JSON.stringify(document, null, 2)}\n` });
  }
  return {
    index: `${JSON.stringify({ schema: "compose-ui-builder-design-index/v1", designs }, null, 2)}\n`,
    files,
  };
}

async function main(argv) {
  const [verb, target] = argv;
  const server = flag(argv, "--server");
  const token = process.env.COMPOSE_PREVIEW_TOKEN || process.env.COMPOSE_PREVIEW_UI_BUILDER_TOKEN;
  if (verb === "publish" ? !target : !verb || !target || !server) {
    console.error(
      "usage: design-sync.mjs export <designId> --server <url> --out <fixture.json>\n" +
        "       design-sync.mjs import <fixture.json> --server <url> [--design-id <id>] [--title <title>]\n" +
        "       design-sync.mjs publish <fixtures dir> --out <dir>",
    );
    return 2;
  }
  if (verb === "export") {
    const out = flag(argv, "--out");
    if (!out) throw new Error("export needs --out <fixture.json>");
    const reply = await mcpCall(server, "ui_builder_get_design", { designId: target }, token);
    const document = reply.snapshot?.state?.document ?? reply.document;
    if (!document) throw new Error(`no document in the reply for ${target}`);
    const fixture = documentToOperations(document, { designId: flag(argv, "--design-id") ?? target });
    writeFileSync(out, `${JSON.stringify(fixture, null, 2)}\n`);
    console.log(`${out}: ${target} at revision ${document.revision}, ${Object.keys(document.nodes).length} nodes, ${fixture.expectedDocumentHash.slice(0, 12)}`);
    return 0;
  }
  if (verb === "publish") {
    // `target` is the fixture directory; `--out` is where the published convention lands.
    const out = flag(argv, "--out");
    if (!out) throw new Error("publish needs --out <dir>");
    const { readdirSync, mkdirSync } = await import("node:fs");
    const names = readdirSync(target).filter((name) => name.endsWith(".json")).sort();
    const fixtures = names.map((name) => ({
      fixture: JSON.parse(readFileSync(`${target}/${name}`, "utf8")),
    }));
    const published = publishDirectory(fixtures);
    mkdirSync(out, { recursive: true });
    writeFileSync(`${out}/index.json`, published.index);
    for (const { file, body } of published.files) writeFileSync(`${out}/${file}`, body);
    console.log(`${out}: ${published.files.length} designs and an index`);
    return 0;
  }
  if (verb === "import") {
    const fixture = JSON.parse(readFileSync(target, "utf8"));
    const designId = flag(argv, "--design-id") ?? fixture.designId;
    const document = operationsToDocument(fixture, { designId, title: flag(argv, "--title") });
    await mcpCall(server, "ui_builder_create_design", { designId, title: document.title, document }, token);
    console.log(`${server}: created ${designId} from ${target}, ${Object.keys(document.nodes).length} nodes`);
    return 0;
  }
  throw new Error(`unknown verb ${verb}`);
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(
    (code) => {
      process.exitCode = code;
    },
    (error) => {
      console.error(error.message);
      process.exitCode = 1;
    },
  );
}

export { candidateDocumentHash };
