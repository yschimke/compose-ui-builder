// The parts of runtime-coverage.mjs that need no browser, so they are tested without one.

const USAGE =
  'usage: runtime-coverage.mjs <wasmDist> <runtimeDir> <designs...> [--allow componentId]...';

export function parseArgs(argv) {
  const positional = [];
  const allow = new Set();
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--allow') {
      const value = argv[++i];
      if (!value) throw new Error(`--allow needs a component id\n${USAGE}`);
      allow.add(value);
    } else if (argv[i].startsWith('--')) {
      throw new Error(`unknown option ${argv[i]}\n${USAGE}`);
    } else {
      positional.push(argv[i]);
    }
  }
  if (positional.length < 3) throw new Error(USAGE);
  const [wasmDist, runtimeDir, ...designs] = positional;
  return { wasmDist, runtimeDir, designs, allow };
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
  const unsupported = undrawn.filter(
    (node) => node.reason === 'unsupported' && !allow.has(node.componentId),
  );
  const standIns = undrawn.filter((node) => node.reason === 'stand-in');
  return {
    design,
    ok: unsupported.length === 0,
    reason:
      unsupported.length === 0
        ? null
        : `the authoring canvas cannot draw ${[...new Set(unsupported.map((it) => it.componentId))].sort().join(', ')}`,
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
