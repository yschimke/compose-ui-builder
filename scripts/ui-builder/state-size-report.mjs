#!/usr/bin/env node

import {
  closeSync,
  existsSync,
  openSync,
  readFileSync,
  readSync,
  readdirSync,
  statSync,
} from "node:fs";
import { createHash } from "node:crypto";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

/**
 * What is actually inside a UI-builder state store, by design and by section — the measurement
 * [`docs/design/UI_BUILDER_STATE_STORAGE.md`](../../docs/design/UI_BUILDER_STATE_STORAGE.md)
 * argues from. Run it before and after a migration.
 *
 * Sizes are the serialized UTF-8 length of each subtree. They sum to slightly less than the file;
 * `overheadBytes` carries the envelope, key names and punctuation.
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

/** What `FileUiBuilderStateStorage` refuses a write at: the ceiling of the single-file store. */
const DEFAULT_MAXIMUM_BYTES = 128 * 1024 * 1024;
/**
 * What `UiBuilderStoreLimits.maximumBytes` gauges the per-design store against. The default
 * follows the store format the path holds; `--maximum-bytes` overrides both.
 */
const DEFAULT_STORE_MAXIMUM_BYTES = 1024 * 1024 * 1024;
const DEFAULT_WARN_PERCENT = 80;
/** `FileUiBuilderDesignStore.DELETED_DIRECTORY`: where a deleted design waits to be unlinked. */
const DELETED_DIRECTORY = ".deleted";
/** `UiBuilderStoreLimits.maximumDesignBytes`: what the store refuses to read a single file above. */
const MAXIMUM_DESIGN_BYTES = 64 * 1024 * 1024;

/** `FileUiBuilderDesignStore.slug`: the directory a design id addresses, and its only address. */
function slugOf(designId) {
  return createHash("sha256").update(designId ?? "", "utf8").digest("hex").slice(0, 32);
}

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
 * The `designs` map of either envelope format: v1 has the service in `payload`, v2 in
 * `payload.service` beside the catalog-pin manifest.
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
 * [totalBytes] is the real file size when known; otherwise the re-encoded length (differs by
 * whitespace).
 */
export function analyzeUiBuilderState(root, { totalBytes } = {}) {
  const service = serviceOf(root);
  const format = typeof root.format === "string" ? root.format : "unknown";
  const total = totalBytes ?? byteLength(root);

  const designs = Object.entries(service.designs).map(([id, design]) => {
    const sections = {};
    for (const section of DESIGN_SECTIONS) {
      sections[section] = {
        bytes: byteLength(design[section]),
        count: countOf(design[section]),
        // Per-entry sizes for the array sections retention trims, so `projectRetention` can sum the
        // exact prefix it would drop. Undefined for object sections.
        entryBytes: Array.isArray(design[section])
          ? design[section].map((entry) => byteLength(entry))
          : undefined,
      };
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
 * What a shallower revision retention would save, measured per design: a design only gives back
 * the snapshots it holds beyond [keep], so designs shallower than [keep] save nothing.
 */
export function projectRetention(report, { keep = 64 } = {}) {
  let snapshotBytes = 0;
  let savedBytes = 0;
  for (const design of report.designs) {
    for (const section of ["revisionSnapshots", "positionSnapshots"]) {
      const { bytes, count, entryBytes } = design.sections[section];
      snapshotBytes += bytes;
      if (count <= keep) continue;
      // Retention drops the oldest entries, and snapshot sizes vary, so sum the actual prefix
      // rather than scaling.
      savedBytes += entryBytes
        ? entryBytes.slice(0, count - keep).reduce((sum, it) => sum + it, 0)
        : Math.round(bytes * (1 - keep / count));
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

/**
 * The same report, read from the per-design store, with the same sections so before/after reads
 * as one table. Undo-state collections are measured by replaying the journal, which also exposes
 * the journal's slack.
 */
export function analyzeUiBuilderStore(directory) {
  const designsDirectory = join(directory, "designs");
  // An opened but never-written store has no `designs/`; that is an empty deployment, not an error.
  const entries = existsSync(designsDirectory)
    ? readdirSync(designsDirectory, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => entry.name)
    : [];
  // The store's `.deleted` directory holds tombstones whose unlink did not finish. Not tabulated
  // (they are not live designs) but their bytes still count, as overhead.
  const slugs = entries.filter((name) => name !== DELETED_DIRECTORY);

  let totalBytes = fileBytes(join(directory, "store.json"));
  if (entries.includes(DELETED_DIRECTORY)) {
    totalBytes += directoryBytes(join(designsDirectory, DELETED_DIRECTORY));
  }
  const designs = [];
  for (const slug of slugs) {
    const designDirectory = join(designsDirectory, slug);
    // A `quarantine.json` means the design does not load; don't tabulate it as live.
    const quarantined = existsSync(join(designDirectory, "quarantine.json"));
    const parsed = quarantined ? null : payloadOf(join(designDirectory, "design.json"));
    // The slug is the address: a design copied under another basename is quarantined at load, so
    // trusting its header would list the same `designId` twice.
    const header = parsed && slugOf(parsed.designId) === slug ? parsed : null;
    if (!header) {
      // Still on disk and counted by the store, so counted here (not tabulated: no sections).
      totalBytes += directoryBytes(designDirectory);
      continue;
    }
    const sections = {};
    for (const section of DESIGN_SECTIONS) sections[section] = { bytes: 0, count: 0 };

    const document = payloadOf(join(designDirectory, header.documentFile));
    sections.document = { bytes: byteLength(document), count: 1 };
    sections.positions = {
      bytes: byteLength(payloadOf(join(designDirectory, header.positionsFile))?.positions),
      count: countOf(payloadOf(join(designDirectory, header.positionsFile))?.positions),
    };
    sections.access = { bytes: byteLength(header.access), count: 1 };

    sections.revisionSnapshots.entryBytes = [];
    sections.positionSnapshots.entryBytes = [];
    // Sorted by revision as `readDesign` does, since `projectRetention` drops a prefix.
    const revisionFiles = Object.entries(header.revisionFiles ?? {}).sort(
      ([left], [right]) => (Number(left) || 0) - (Number(right) || 0),
    );
    for (const [, file] of revisionFiles) {
      const retained = payloadOf(join(designDirectory, file));
      if (!retained) continue;
      if (retained.document) {
        const bytes = byteLength(retained.document);
        sections.revisionSnapshots.bytes += bytes;
        sections.revisionSnapshots.count += 1;
        // Per entry as well as in total: retention drops the oldest, and on a design that grew
        // those are the small ones, so the average this used to scale by overstated the saving.
        sections.revisionSnapshots.entryBytes.push(bytes);
      }
      if (retained.positions) {
        const bytes = byteLength(retained.positions);
        sections.positionSnapshots.bytes += bytes;
        sections.positionSnapshots.count += 1;
        sections.positionSnapshots.entryBytes.push(bytes);
      }
    }

    const journal = replayJournal(designDirectory, header);
    for (const [section, value] of Object.entries(journal.live)) {
      sections[section] = {
        bytes: byteLength(value),
        count: countOf(value),
        entryBytes: Array.isArray(value) ? value.map((entry) => byteLength(entry)) : undefined,
      };
    }

    const designBytes = directoryBytes(designDirectory);
    const sectionBytes = Object.values(sections).reduce((sum, it) => sum + it.bytes, 0);
    totalBytes += designBytes;
    designs.push({
      id: header.designId ?? slug,
      bytes: designBytes,
      revision: header.revision ?? null,
      nodes: countOf(document?.nodes),
      sections,
      // The journal records a later one superseded, the checksums and the JSON around every part:
      // what the design costs on disk beyond what it still says. Compaction is what reclaims it.
      otherBytes: designBytes - sectionBytes,
    });
  }
  designs.sort((left, right) => right.bytes - left.bytes);

  const designBytes = designs.reduce((sum, design) => sum + design.bytes, 0);
  const sectionTotals = {};
  for (const section of DESIGN_SECTIONS) {
    sectionTotals[section] = designs.reduce((sum, design) => sum + design.sections[section].bytes, 0);
  }
  return {
    format: markerFormat(directory),
    totalBytes,
    designCount: designs.length,
    designBytes,
    overheadBytes: totalBytes - designBytes,
    sectionTotals,
    designs,
  };
}

/** True when [path] is a directory holding the per-design store rather than a v1/v2 file. */
export function isDesignStore(path) {
  try {
    return statSync(path).isDirectory() && statSync(join(path, "store.json")).isFile();
  } catch {
    return false;
  }
}

function markerFormat(directory) {
  try {
    return JSON.parse(readFileSync(join(directory, "store.json"), "utf8")).format ?? "unknown";
  } catch {
    return "unknown";
  }
}

function fileBytes(path) {
  try {
    return statSync(path).size;
  } catch {
    return 0;
  }
}

function directoryBytes(directory) {
  let total = 0;
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    total += entry.isDirectory() ? directoryBytes(path) : fileBytes(path);
  }
  return total;
}

function payloadOf(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8")).payload;
  } catch {
    return null;
  }
}

/**
 * The live collections a design's journal describes, replayed as the store does: only the bytes
 * the header commits to.
 */
function replayJournal(designDirectory, header) {
  const live = {
    history: [],
    audit: [],
    operationOutcomes: {},
    acceptedOperations: {},
    tombstones: {},
  };
  if (!header.journalFile) return { live };
  let committed;
  try {
    // Only the committed prefix, and bounded before allocating, as the store reads it: a runaway or
    // corrupt header must not exhaust the heap here.
    const length = header.journalBytes ?? 0;
    if (length > MAXIMUM_DESIGN_BYTES) return { live };
    committed = Buffer.alloc(length);
    const handle = openSync(join(designDirectory, header.journalFile), "r");
    try {
      readSync(handle, committed, 0, length, 0);
    } finally {
      closeSync(handle);
    }
  } catch {
    return { live };
  }
  for (const line of committed.toString("utf8").split("\n")) {
    if (!line.trim()) continue;
    let entry;
    try {
      // Each record is stored beside a checksum of itself; the report reads the record and leaves
      // verifying it to the store, which quarantines the design rather than reporting on it.
      entry = JSON.parse(line).entry;
    } catch {
      continue;
    }
    if (!entry) continue;
    // Presence, not truthiness: a trim to nothing is an empty append with `keep: 0`.
    if (entry.historySet !== undefined) live.history = entry.historySet;
    if (entry.historyAppend !== undefined) {
      live.history = live.history.concat(entry.historyAppend);
      if (entry.historyKeep !== undefined) {
        live.history = entry.historyKeep === 0 ? [] : live.history.slice(-entry.historyKeep);
      }
    }
    if (entry.auditSet !== undefined) live.audit = entry.auditSet;
    if (entry.auditAppend !== undefined) {
      live.audit = live.audit.concat(entry.auditAppend);
      if (entry.auditKeep !== undefined) {
        live.audit = entry.auditKeep === 0 ? [] : live.audit.slice(-entry.auditKeep);
      }
    }
    applyMapDelta(live.operationOutcomes, entry.outcomesPut, entry.outcomesRemoved);
    applyMapDelta(live.acceptedOperations, entry.acceptedPut, entry.acceptedRemoved);
    applyMapDelta(live.tombstones, entry.tombstonesPut, entry.tombstonesRemoved);
  }
  return { live };
}

function applyMapDelta(target, put, removed) {
  for (const [key, value] of Object.entries(put ?? {})) target[key] = value;
  for (const key of removed ?? []) delete target[key];
}

function megabytes(bytes) {
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function percent(bytes, of) {
  return `${((bytes / of) * 100).toFixed(1)}%`;
}

/** The ceiling a report is measured against when the caller names none: the one its store has. */
export function defaultMaximumBytes(report) {
  return report.format === "ui-builder-store-v3" ? DEFAULT_STORE_MAXIMUM_BYTES : DEFAULT_MAXIMUM_BYTES;
}

export function formatReport(report, { maximumBytes = defaultMaximumBytes(report), top = 10 } = {}) {
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
  const options = {
    path: null,
    maximumBytes: null,
    warnPercent: DEFAULT_WARN_PERCENT,
    top: 10,
  };
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
      "usage: state-size-report.mjs <ui-builder-state-dir | ui-builder-service-v1.json> " +
        "[--maximum-bytes N] [--warn-percent N] [--top N]",
    );
  }
  return options;
}

function main(argv) {
  const options = parseArguments(argv);
  const store = isDesignStore(options.path);
  if (options.maximumBytes === null) {
    options.maximumBytes = store ? DEFAULT_STORE_MAXIMUM_BYTES : DEFAULT_MAXIMUM_BYTES;
  }
  const report = store
    ? analyzeUiBuilderStore(options.path)
    : analyzeUiBuilderState(JSON.parse(readFileSync(options.path, "utf8")), {
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
