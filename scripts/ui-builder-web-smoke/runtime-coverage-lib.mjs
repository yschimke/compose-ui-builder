// The parts of runtime-coverage.mjs that need no browser, so they are tested without one.

const USAGE =
  'usage: runtime-coverage.mjs <wasmDist> <runtimeDir> <designs...> [--allow componentId]... ' +
  '[--expect-unsupported componentId]...';

/** The reasons a canvas reports; anything else is a runtime this gate cannot vouch for. */
const KNOWN_REASONS = new Set(['unsupported', 'stand-in']);

export function parseArgs(argv) {
  const positional = [];
  const allow = new Set();
  const expect = new Set();
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--allow' || argv[i] === '--expect-unsupported') {
      const flag = argv[i];
      const value = argv[++i];
      if (!value) throw new Error(`${flag} needs a component id\n${USAGE}`);
      (flag === '--allow' ? allow : expect).add(value);
    } else if (argv[i].startsWith('--')) {
      throw new Error(`unknown option ${argv[i]}\n${USAGE}`);
    } else {
      positional.push(argv[i]);
    }
  }
  if (positional.length < 3) throw new Error(USAGE);
  const [wasmDist, runtimeDir, ...designs] = positional;
  return { wasmDist, runtimeDir, designs, allow, expect };
}

/**
 * One design's verdict from what the runtime reported (`undrawn`, or undefined when the runtime
 * said nothing). Unsupported nodes fail unless their component is in [allow]; stand-ins are listed
 * but pass, since they are a canvas saying honestly that it draws a placeholder.
 */
export function classify(design, undrawn, allow = new Set()) {
  if (!Array.isArray(undrawn)) {
    return {
      design,
      ok: false,
      reason:
        'the runtime did not report undrawn nodes: build it against a compose-ui-builder ' +
        'renderer SDK that sends them',
      unsupported: [],
      standIns: [],
    };
  }
  // An unknown reason fails: a canvas that says it did not draw a node, for a reason this gate
  // does not know, has not been shown to be honest about it.
  const unknown = undrawn.filter((node) => !KNOWN_REASONS.has(node.reason));
  const unsupported = undrawn.filter(
    (node) => node.reason === 'unsupported' && !allow.has(node.componentId),
  );
  const standIns = undrawn.filter((node) => node.reason === 'stand-in');
  const problems = [];
  if (unsupported.length > 0) {
    const names = [...new Set(unsupported.map((it) => it.componentId))].sort().join(', ');
    problems.push(`the authoring canvas cannot draw ${names}`);
  }
  if (unknown.length > 0) {
    const reasons = [...new Set(unknown.map((it) => JSON.stringify(it.reason)))].sort().join(', ');
    problems.push(`undrawn nodes with unknown reasons ${reasons}`);
  }
  return {
    design,
    ok: problems.length === 0,
    reason: problems.length === 0 ? null : problems.join('; '),
    unsupported,
    standIns,
  };
}

/** A short report: one line per design, failures first. */
export function report(results) {
  const lines = [];
  for (const result of [...results].sort((a, b) => Number(a.ok) - Number(b.ok))) {
    const standIns = result.standIns.length
      ? ` (${result.standIns.length} stand-in${result.standIns.length === 1 ? '' : 's'})`
      : '';
    lines.push(`${result.ok ? 'ok  ' : 'FAIL'} ${result.design}${standIns}${result.reason ? ` — ${result.reason}` : ''}`);
  }
  return lines.join('\n');
}

/**
 * The verdict when the run is a negative control: it passes only if every design failed solely
 * because the expected components were unsupported, so a timeout, a refusal or a runtime that
 * does not report cannot pass for the detection being tested.
 */
export function expectUnsupported(results, expected) {
  const problems = [];
  for (const result of results) {
    const found = new Set(result.unsupported.map((it) => it.componentId));
    const missing = [...expected].filter((id) => !found.has(id));
    const unexpected = [...found].filter((id) => !expected.has(id));
    const other = result.reason?.replace(/the authoring canvas cannot draw [^;]*(; )?/, '') ?? '';
    if (missing.length) problems.push(`${result.design}: did not report ${missing.join(', ')}`);
    if (unexpected.length) problems.push(`${result.design}: also reported ${unexpected.join(', ')}`);
    if (other) problems.push(`${result.design}: ${other}`);
  }
  return problems;
}
