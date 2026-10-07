#!/usr/bin/env node
// Collects candidate design rules from the Android Knowledge Base: the offline copy of
// developer.android.com that the `android` CLI downloads for `android docs search`.
//
//   android docs search "wear"            # downloads ~/.android/cli/docs/kbzip/dac.zip once
//   node scripts/guidelines/extract-guidance.mjs [--zip <dac.zip>] [--prefix <path>] [--out <file>]
//
// Every normative sentence ("must", "should", "don't", "avoid", "at least", ...) under the design
// guides becomes a candidate with the page it came from. Candidates are raw material, not rules:
// a person rewrites the ones worth checking as entries in docs/guidelines/android-design-guidelines.json,
// each with a yes/no `check` and a `kind` (structure or visual).

import { execFileSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const DEFAULT_PREFIXES = [
  "android/design/ui/wear/guides/",
  "android/design/ui/ai-glasses/guides/",
  "android/design/ui/mobile/guides/",
  "android/agents/skills/wear/",
];
const NORMATIVE =
  /\b(must|should|avoid|don't|do not|never|always|at least|minimum|maximum|no more than|recommend)\b/i;

function parseArgs(argv) {
  const args = {
    zip: join(homedir(), ".android/cli/docs/kbzip/dac.zip"),
    prefixes: [],
    out: null,
    includeLegacy: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i];
    if (flag === "--zip") args.zip = argv[++i];
    else if (flag === "--prefix") args.prefixes.push(argv[++i]);
    else if (flag === "--out") args.out = argv[++i];
    else if (flag === "--include-legacy") args.includeLegacy = true;
    else throw new Error(`unknown argument ${flag}`);
  }
  if (args.prefixes.length === 0) args.prefixes = DEFAULT_PREFIXES;
  return args;
}

function listEntries(zip) {
  return execFileSync("unzip", ["-Z1", zip], { maxBuffer: 64 << 20 })
    .toString()
    .split("\n")
    .filter(Boolean);
}

function readEntry(zip, entry) {
  return execFileSync("unzip", ["-p", zip, entry], { maxBuffer: 16 << 20 }).toString();
}

/** Markdown to plain sentences: drop images, links' targets, code and table rules. */
export function sentences(markdown) {
  const text = markdown
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/!\[[^\]]*\]\([^)]*\)/g, " ")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/<[^>]+>/g, " ")
    .replace(/[*_`#>|]/g, " ")
    .replace(/\s+/g, " ");
  return text
    .split(/(?<=[.!?])\s+(?=[A-Z])/)
    .map((s) => s.trim())
    .filter((s) => s.length >= 20 && s.length <= 320);
}

export function candidates(markdown, page) {
  return sentences(markdown)
    .filter((s) => NORMATIVE.test(s))
    .map((text) => ({ page, text }));
}

/** `android/design/ui/wear/guides/x.md.txt` → its developer.android.com address. */
export function sourceUrl(entry) {
  const path = entry.replace(/\.md\.txt$/, "").replace(/\/index$/, "");
  if (path.startsWith("android/agents/")) return `kb://${path}`;
  return `https://developer.android.com/${path.replace(/^android\//, "")}`;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const entries = listEntries(args.zip).filter(
    (entry) =>
      entry.endsWith(".md.txt") &&
      args.prefixes.some((prefix) => entry.startsWith(prefix)) &&
      // Wear's Material 2.5 guides describe the previous design language.
      (args.includeLegacy || !entry.includes("/m2-5/")),
  );
  const found = entries.flatMap((entry) => candidates(readEntry(args.zip, entry), sourceUrl(entry)));
  const unique = [...new Map(found.map((c) => [`${c.page}|${c.text}`, c])).values()];
  const output = JSON.stringify(
    { schema: "compose-ui-builder/guidance-candidates/v1", pages: entries.length, candidates: unique },
    null,
    2,
  );
  if (args.out) {
    writeFileSync(args.out, output + "\n");
    console.error(`${unique.length} candidates from ${entries.length} pages → ${args.out}`);
  } else {
    process.stdout.write(output + "\n");
  }
}

if (import.meta.url === `file://${process.argv[1]}`) main();
