#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

/**
 * Wave-0-only reducer for exercising candidate public operation fixtures before their wire shapes
 * move to compose-preview-contracts. This is deliberately not the production reducer.
 */
export function replayCandidateOperations(input) {
  let document = null;
  const outcomes = new Map();

  for (const command of input.operations ?? []) {
    requireText(command.operationId, "operationId");
    const previous = outcomes.get(command.operationId);
    if (previous) continue;

    if (command.type === "createDesign") {
      if (document) throw new Error("createDesign may only be accepted once");
      document = {
        schema: input.documentSchema,
        id: input.designId,
        title: command.title,
        revision: 0,
        catalogPin: clone(command.catalogPin),
        environment: clone(command.environment),
        stateVariables: clone(command.stateVariables ?? {}),
        roots: [],
        nodes: {},
      };
      outcomes.set(command.operationId, { revision: 0 });
      continue;
    }

    if (!document) throw new Error(`${command.type} requires createDesign first`);
    if (command.type !== "insertNode") {
      throw new Error(`unsupported candidate operation: ${command.type}`);
    }

    const node = normalizedNode(command.node);
    if (document.nodes[node.id]) throw new Error(`node already exists: ${node.id}`);
    if (command.parent === null) {
      insertAfter(document.roots, node.id, command.afterNodeId ?? null, "roots");
    } else {
      const parent = document.nodes[command.parent?.nodeId];
      if (!parent) throw new Error(`unknown parent: ${command.parent?.nodeId}`);
      requireText(command.parent.slot, "parent.slot");
      const children = (parent.slots[command.parent.slot] ??= []);
      insertAfter(children, node.id, command.afterNodeId ?? null, command.parent.slot);
    }
    document.nodes[node.id] = node;
    document.revision += 1;
    outcomes.set(command.operationId, { revision: document.revision });
  }

  if (!document) throw new Error("operation fixture did not create a design");
  return { document, hash: candidateDocumentHash(document) };
}

export function candidateDocumentHash(document) {
  const canonical = JSON.stringify(canonicalize(document));
  return createHash("sha256").update(canonical).digest("hex");
}

function normalizedNode(node) {
  if (!node || typeof node !== "object") throw new Error("insertNode requires node");
  requireText(node.id, "node.id");
  requireText(node.componentId, "node.componentId");
  return {
    id: node.id,
    componentId: node.componentId,
    properties: clone(node.properties ?? {}),
    modifiers: clone(node.modifiers ?? []),
    slots: clone(node.slots ?? {}),
    eventBindings: clone(node.eventBindings ?? {}),
  };
}

function insertAfter(target, value, afterNodeId, label) {
  if (afterNodeId === null) {
    target.unshift(value);
    return;
  }
  const index = target.indexOf(afterNodeId);
  if (index < 0) throw new Error(`unknown insertion anchor ${afterNodeId} in ${label}`);
  target.splice(index + 1, 0, value);
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, canonicalize(value[key])]),
    );
  }
  return value;
}

function requireText(value, label) {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${label} must be non-empty text`);
  }
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const fixturePath = process.argv[2];
  if (!fixturePath) {
    console.error("usage: node scripts/ui-builder/replay-candidate.mjs <operations.json>");
    process.exitCode = 2;
  } else {
    const input = JSON.parse(readFileSync(fixturePath, "utf8"));
    process.stdout.write(`${JSON.stringify(replayCandidateOperations(input), null, 2)}\n`);
  }
}
