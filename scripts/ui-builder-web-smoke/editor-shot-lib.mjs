// The parts of editor-shot.mjs that need no browser, so they are tested without one.

const DOCUMENT_SCHEMA = 'compose-ui-builder-document/v1-candidate';

const USAGE =
  'usage: editor-shot.mjs <design-url> <out.png> [--document FILE] [--viewport WxH] ' +
  '[--settle MS] [--steps JSON]';

export function parseArgs(argv) {
  const positional = [];
  const options = { viewport: { width: 1400, height: 900 }, settle: 10_000, steps: [] };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      positional.push(arg);
      continue;
    }
    const value = argv[++i];
    if (value === undefined) throw new Error(`${arg} needs a value\n${USAGE}`);
    switch (arg) {
      case '--document':
        options.document = value;
        break;
      case '--viewport': {
        const match = /^(\d+)x(\d+)$/.exec(value);
        if (!match) throw new Error(`--viewport is WxH, not ${value}`);
        options.viewport = { width: Number(match[1]), height: Number(match[2]) };
        break;
      }
      case '--settle':
        options.settle = Number(value);
        break;
      case '--steps':
        options.steps = JSON.parse(value);
        break;
      default:
        throw new Error(`unknown option ${arg}\n${USAGE}`);
    }
  }
  if (positional.length !== 2) throw new Error(USAGE);
  [options.url, options.output] = positional;
  return options;
}

/** Whether a request may carry the design's document: the design read, or a protocol request. */
export function isDesignReply(url) {
  return /\/api\/ui-builder\/v1\/(designs\/[^/]+|requests)$/.test(new URL(url).pathname);
}

/**
 * Whether a request carries the grant: the host's API and MCP only. The editor's own files are
 * public, and keeping the token off them keeps it off any CDN or login page they redirect to.
 */
export function needsGrant(url) {
  const path = new URL(url).pathname;
  return path.startsWith('/api/') || path === '/mcp' || path.startsWith('/mcp/');
}

/** A document's catalog pin as one comparable string. */
export function catalogPinKey(document) {
  const pin = document?.catalogPin ?? {};
  return `${pin.systemId}@${pin.catalogRevision}`;
}

/**
 * Replace every document in [body] that is [document]'s design with [document], keeping the
 * revision the host sent so the editor's sequencing still agrees with the server. Returns how many
 * were replaced and the catalog pins of the documents they replaced, so a caller can say when the
 * editor will draw [document] against a catalog it was not pinned to.
 */
export function replaceDocument(body, document) {
  let replaced = 0;
  const hostPins = [];
  const visit = (value) => {
    if (!value || typeof value !== 'object') return;
    for (const key of Object.keys(value)) {
      const child = value[key];
      if (child && typeof child === 'object' && child.schema === DOCUMENT_SCHEMA && child.id === document.id) {
        hostPins.push(catalogPinKey(child));
        value[key] = { ...document, revision: child.revision };
        replaced++;
      } else {
        visit(child);
      }
    }
  };
  visit(body);
  return { replaced, hostPins };
}
