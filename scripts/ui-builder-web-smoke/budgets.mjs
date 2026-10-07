#!/usr/bin/env node
// Size and memory budgets for the shipped editor, from `budgets.json`.
//
//   node budgets.mjs <wasmDist> [budgets.json]     fails when a file group is over its size budget
//
// memory.mjs applies the memory half when MEMORY_BUDGETS names the file. CI runs both on `main`
// only: pull requests package the module without Binaryen, so their sizes and memory are not the
// shipped ones. Raise a budget in the same pull request that knowingly spends it, and say why.

import { readdir, readFile, stat } from 'node:fs/promises';
import { join, relative, resolve } from 'node:path';
import { gzipSync } from 'node:zlib';

const MB = 1024 * 1024;
const mb = (bytes) => (bytes / MB).toFixed(2);

/** `*` matches within a path segment, `**` across them; anything else is literal. */
export function globToRegExp(glob) {
  const source = glob
    .split('**')
    .map((part) => part.split('*').map((s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&')).join('[^/]*'))
    .join('.*');
  return new RegExp(`^${source}$`);
}

async function listFiles(dir, root = dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await listFiles(path, root)));
    else out.push(relative(root, path).split('\\').join('/'));
  }
  return out;
}

/**
 * Each size budget against [files] (`{ path, bytes, gzipBytes }`): the group's totals and whether
 * either is over. A group matching no file is a failure too, so a renamed file cannot slip out.
 */
export function checkSizes(files, budgets) {
  return budgets.map((budget) => {
    const pattern = globToRegExp(budget.files);
    const matched = files.filter((f) => pattern.test(f.path));
    const bytes = matched.reduce((sum, f) => sum + f.bytes, 0);
    const gzipBytes = matched.reduce((sum, f) => sum + f.gzipBytes, 0);
    const problems = [];
    if (!matched.length) problems.push(`no file matches ${budget.files}`);
    if (budget.maxMb !== undefined && bytes > budget.maxMb * MB) problems.push(`${mb(bytes)} MB > ${budget.maxMb} MB`);
    if (budget.maxGzipMb !== undefined && gzipBytes > budget.maxGzipMb * MB) {
      problems.push(`${mb(gzipBytes)} MB gzipped > ${budget.maxGzipMb} MB`);
    }
    return { name: budget.name, files: matched.length, bytes, gzipBytes, budget, problems };
  });
}

/**
 * Each memory budget against memory.mjs's [report]: the settled median of every metric it caps,
 * per viewport and scenario. A budget whose scenario was not measured is a failure.
 */
export function checkMemory(report, budgets) {
  return budgets.map((budget) => {
    const result = report.results.find((r) => r.viewport === budget.viewport && r.scenario === budget.scenario);
    const problems = [];
    const measured = {};
    if (!result) {
      problems.push(`${budget.viewport} ${budget.scenario} was not measured`);
    } else {
      for (const [metric, maxMb] of Object.entries(budget.maxMb)) {
        const value = result.settled[metric];
        measured[metric] = value;
        if (value === undefined) problems.push(`no ${metric} in the report`);
        else if (value > maxMb * MB) problems.push(`${metric} ${mb(value)} MB > ${maxMb} MB`);
      }
    }
    return { budget, measured, problems };
  });
}

export async function readBudgets(path) {
  return JSON.parse(await readFile(path, 'utf8'));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const [distArg, budgetsArg] = process.argv.slice(2);
  if (!distArg) {
    console.error('usage: node budgets.mjs <wasmDist> [budgets.json]');
    process.exit(2);
  }
  const dist = resolve(distArg);
  const budgets = await readBudgets(budgetsArg ?? new URL('./budgets.json', import.meta.url).pathname);
  const files = [];
  for (const path of await listFiles(dist)) {
    const bytes = await readFile(join(dist, path));
    files.push({ path, bytes: (await stat(join(dist, path))).size, gzipBytes: gzipSync(bytes, { level: 9 }).length });
  }
  const rows = checkSizes(files, budgets.sizes);
  for (const row of rows) {
    const limits = [row.budget.maxMb && `${row.budget.maxMb} MB`, row.budget.maxGzipMb && `${row.budget.maxGzipMb} MB gzipped`]
      .filter(Boolean).join(', ');
    console.log(
      `${row.problems.length ? 'OVER' : 'ok  '} ${row.name.padEnd(24)} ${mb(row.bytes).padStart(7)} MB ` +
        `(${mb(row.gzipBytes)} MB gzipped, ${row.files} files) budget ${limits}`,
    );
  }
  const over = rows.filter((r) => r.problems.length);
  if (over.length) {
    console.error(`\nOver budget: ${over.map((r) => `${r.name}: ${r.problems.join('; ')}`).join('. ')}.`);
    console.error('Raise the budget in scripts/ui-builder-web-smoke/budgets.json only if the growth is intended.');
    process.exitCode = 1;
  }
}
