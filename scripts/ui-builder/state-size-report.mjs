#!/usr/bin/env node

import { readFileSync, statSync } from "node:fs";
import { pathToFileURL } from "node:url";

/**
 * What is actually inside a `ui-builder-service-v1.json`, by design and by section.
 *
 * The store keeps every design in one file and rewrites the whole thing on every accepted edit, so
 * the only number an operator had was the total — 23.4 MB against the ceiling, with no way to see
 * which design or which retained list was spending it
 * ([#568](https://github.com/yschimke/compose-preview-server/issues/568)). This answers that, and
 * it is the measurement the per-design store in
 * [`docs/design/UI_BUILDER_STATE_STORAGE.md`](../../docs/design/UI_BUILDER_STATE_STORAGE.md)
 * argues from: run it before the migration and after it.
 *
 * Run against the live store it immediately overturned the guess it was written to check. The bytes
 * are not in the revision snapshots: 36 designs sat at revision 1-20, and undo bookkeeping
 * (`acceptedOperations`, `tombstones`, `operationOutcomes`, `history`) held 55% of the file against
 * the snapshots' 35% and the live documents' 4%. One design held 53% of the store on its own. That
 * is the whole argument for measuring rather than modelling.
 *
 * Sizes are the serialized length of each subtree in UTF-8 bytes. They sum to slightly less than
 * the file, because the envelope, the key names above a section and the punctuation between
 * sections belong to no section; `overheadBytes` carries that remainder.
 */

/** Sections of a stored design, in the order the report prints them. */
export const DESIGN_SECTIONS = [
  "document",
  "revisionSnapshots",
  "positionSnapshots",
  "history",
  "audit",
  "operationOutcomes",
  "acceptedOperations",
  "tombstones",
  "positions",
  "access",
];

const DEFAULT_MAXIMUM_BYTES = 128 * 1024 * 1024;
const DEFAULT_WARN_PERCENT = 80;

function byteLength(value) {
  if (value === undefined) return 0;
  return Buffer.byteLength(JSON.stringify(value), "utf8");
}

function countOf(value) {
  if (Array.isArray(value)) return value.length;
  if (value && typeof value === "object") return Object.keys(value).length;
  return value === undefined ? 0 : 1;
}

/**
 * The `designs` map of either envelope format. v1 puts the service directly in `payload`, v2 wraps
 * it as `payload.service` beside the catalog-pin manifest; both are read here because a deployment
 * that never ran `--ui-builder-migrate-state` is still on v1.
 */
function serviceOf(root) {
  if (!root || typeof root !== "object") {
    throw new Error("state file is not a JSON object");
  }
  const payload = root.payload;
  if (!payload || typeof payload !== "object") {
    throw new Error("state file has no payload");
  }
  const service = payload.service ?? payload;
  if (!service.designs || typeof service.designs !== "object") {
    throw new Error("state payload has no designs map");
  }
  return service;
}

/**
 * Break [root] — a parsed state envelope — into per-design and per-section byte counts.
 *
 * [totalBytes] is the size of the file when one was read, and the length of the re-encoded envelope
 * otherwise; the two differ by whitespace, so pass the real size when reporting on a real file.
 */
export function analyzeUiBuilderState(root, { totalBytes } = {}) {
  const service = serviceOf(root);
  const format = typeof root.format === "string" ? root.format : "unknown";
  const total = totalBytes ?? byteLength(root);

  const designs = Object.entries(service.designs).map(([id, design]) => {
    const sections = {};
    for (const section of DESIGN_SECTIONS) {
      sections[section] = { bytes: byteLength(design[section]), count: countOf(design[section]) };
    }
    const sectionBytes = Object.values(sections).reduce((sum, it) => sum + it.bytes, 0);
    const designBytes = byteLength(design);
    return {
      id,
      bytes: designBytes,
      revision: design.document?.revision ?? null,
      nodes: countOf(design.document?.nodes),
      sections,
      // Everything the design object holds that is not one of the named sections: the scalar
      // fields, and the braces and key names around the sections themselves.
      otherBytes: designBytes - sectionBytes,
    };
  });
  designs.sort((left, right) => right.bytes - left.bytes);

  const designBytes = designs.reduce((sum, design) => sum + design.bytes, 0);
  const sectionTotals = {};
  for (const section of DESIGN_SECTIONS) {
    sectionTotals[section] = designs.reduce((sum, design) => sum + design.sections[section].bytes, 0);
  }

  return {
    format,
    totalBytes: total,
    designCount: designs.length,
    designBytes,
    overheadBytes: total - designBytes,
    sectionTotals,
    designs,
  };
}

/**
 * What a shallower revision retention would actually save, measured per design.
 *
 * This used to scale the two snapshot sections by `keep / retained`, assuming every design sat at
 * the configured retention depth. Against a real store that was badly wrong: the live file's
 * designs are at revision 1-20, nowhere near the 1,025 cap, so nothing would have been dropped and
 * the reported saving of 7.66 MB did not exist. A design only gives bytes back for the snapshots it
 * holds **beyond** [keep], so the count is what the arithmetic has to come from — and a store whose
 * designs are all shallower than [keep] correctly projects a saving of zero.
 */
export function projectRetention(report, { keep = 64 } = {}) {
  let snapshotBytes = 0;
  let savedBytes = 0;
  for (const design of report.designs) {
    for (const section of ["revisionSnapshots", "positionSnapshots"]) {
      const { bytes, count } = design.sections[section];
      snapshotBytes += bytes;
      if (count > keep) savedBytes += Math.round(bytes * (1 - keep / count));
    }
  }
  return {
    keep,
    snapshotBytes,
    projectedTotalBytes: report.totalBytes - savedBytes,
    savedBytes,
    // How many designs are actually deep enough for the cut to reach.
    affectedDesigns: report.designs.filter((design) =>
      ["revisionSnapshots", "positionSnapshots"].some(
        (section) => design.sections[section].count > keep,
      ),
    ).length,
  };
}

function megabytes(bytes) {
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function percent(bytes, of) {
  return `${((bytes / of) * 100).toFixed(1)}%`;
}

export function formatReport(report, { maximumBytes = DEFAULT_MAXIMUM_BYTES, top = 10 } = {}) {
  const lines = [];
  lines.push(
    `format ${report.format}  ${megabytes(report.totalBytes)} of ${megabytes(maximumBytes)} ` +
      `(${percent(report.totalBytes, maximumBytes)})  ${report.designCount} designs`,
  );

  lines.push("");
  lines.push("by section (all designs)");
  const sections = Object.entries(report.sectionTotals)
    .filter(([, bytes]) => bytes > 0)
    .sort((left, right) => right[1] - left[1]);
  for (const [section, bytes] of sections) {
    lines.push(
      `  ${section.padEnd(20)} ${megabytes(bytes).padStart(10)}  ${percent(bytes, report.totalBytes).padStart(6)}`,
    );
  }
  if (report.overheadBytes > 0) {
    lines.push(`  ${"(envelope)".padEnd(20)} ${megabytes(report.overheadBytes).padStart(10)}`);
  }

  lines.push("");
  lines.push(`by design (top ${Math.min(top, report.designs.length)})`);
  for (const design of report.designs.slice(0, top)) {
    const worst = Object.entries(design.sections).sort((left, right) => right[1].bytes - left[1].bytes)[0];
    lines.push(
      `  ${design.id.padEnd(24).slice(0, 24)} ${megabytes(design.bytes).padStart(10)}  ` +
        `${percent(design.bytes, report.totalBytes).padStart(6)}  rev ${design.revision ?? "?"}  ` +
        `${design.nodes} nodes  largest: ${worst[0]} (${megabytes(worst[1].bytes)}, ${worst[1].count} entries)`,
    );
  }

  const projection = projectRetention(report);
  lines.push("");
  if (projection.affectedDesigns === 0) {
    lines.push(
      `every design already retains fewer than ${projection.keep} revisions, so cutting revision ` +
        `retention would save nothing here — the bytes are elsewhere in the table above`,
    );
  } else {
    lines.push(
      `capping retention at ${projection.keep} revisions would reach ${projection.affectedDesigns} ` +
        `design(s) and store about ${megabytes(projection.projectedTotalBytes)} ` +
        `(${percent(projection.projectedTotalBytes, maximumBytes)} of the ceiling), ` +
        `saving ${megabytes(projection.savedBytes)}`,
    );
  }
  return lines.join("\n");
}

function parseArguments(argv) {
  const options = { path: null, maximumBytes: DEFAULT_MAXIMUM_BYTES, warnPercent: DEFAULT_WARN_PERCENT, top: 10 };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--maximum-bytes") options.maximumBytes = Number(argv[++index]);
    else if (argument === "--warn-percent") options.warnPercent = Number(argv[++index]);
    else if (argument === "--top") options.top = Number(argv[++index]);
    else if (argument.startsWith("-")) throw new Error(`unknown option ${argument}`);
    else options.path = argument;
  }
  if (!options.path) {
    throw new Error(
      "usage: state-size-report.mjs <ui-builder-service-v1.json> [--maximum-bytes N] [--warn-percent N] [--top N]",
    );
  }
  return options;
}

function main(argv) {
  const options = parseArguments(argv);
  const report = analyzeUiBuilderState(JSON.parse(readFileSync(options.path, "utf8")), {
    totalBytes: statSync(options.path).size,
  });
  process.stdout.write(`${formatReport(report, options)}\n`);
  const used = (report.totalBytes / options.maximumBytes) * 100;
  if (used >= options.warnPercent) {
    process.stderr.write(
      `state-size-report: ${used.toFixed(1)}% of the ceiling is at or past the ${options.warnPercent}% warning line\n`,
    );
    return 1;
  }
  return 0;
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    process.exitCode = main(process.argv.slice(2));
  } catch (failure) {
    process.stderr.write(`state-size-report: ${failure.message}\n`);
    process.exitCode = 2;
  }
}
