// A minimal MCP App host for the Compose UI Builder editor, for a static page.
//
// The editor archive ships an MCP App shell (`mcp-app/ui-builder-mcp-app.html`) that a chat client
// renders in a frame and talks to over JSON-RPC `postMessage`: it reads a `.uid` design through
// `resources/read` and saves it through `openai/resources/write`. This plays the chat client's
// side with the file held in memory, so the real editor runs on a GitHub Pages site with no
// server. See docs/design/UI_BUILDER_MCP_APP_HOST.md for the protocol.

/**
 * Mounts the editor into `frame` on `text`, a `.uid` design.
 *
 * `editorBase` is the URL of the unpacked editor archive (ending in `/`); `layout` is `focused` or
 * `full`. `onSave(text)` is called with every save; `onMessage(text)` with each comment the person
 * sends from the editor. Returns `{ text(), replace(text) }`.
 */
export async function mountEditor({ frame, editorBase, fileName, text, layout = 'focused', onSave, onMessage }) {
  const base = new URL(editorBase, location.href).href;
  const shell = (await (await fetch(new URL('mcp-app/ui-builder-mcp-app.html', base))).text())
    .replaceAll('__COMPOSE_UI_BUILDER_ASSET_BASE__', base)
    .replaceAll('__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__', layout);

  const uri = 'host-resource://design';
  const state = { text, version: 1 };
  const etag = () => 'v' + state.version;
  const send = (message) => frame.contentWindow?.postMessage({ jsonrpc: '2.0', ...message }, '*');

  const handlers = {
    'ui/initialize': () => ({
      protocolVersion: '2026-01-26',
      hostInfo: { name: 'compose-ui-builder-site', version: '1' },
      hostCapabilities: {
        experimental: { 'openai/resource': {}, 'openai/modelContext': {}, 'openai/message': {} },
        updateModelContext: { text: {}, image: {}, structuredContent: {} },
        message: onMessage ? { text: {} } : undefined,
        openLinks: {},
      },
      hostContext: { theme: 'light', displayMode: 'fullscreen' },
    }),
    'resources/read': () => ({
      contents: [{ uri, mimeType: 'application/json', text: state.text,
        _meta: { 'openai/resource': { etag: etag(), writable: true } } }],
    }),
    'resources/subscribe': () => ({}),
    'resources/unsubscribe': () => ({}),
    'openai/resources/write': (params) => {
      if (params.ifMatch && params.ifMatch !== etag()) return { outcome: 'conflict', etag: etag() };
      state.text = params.text;
      state.version++;
      onSave?.(state.text);
      return { outcome: 'saved', etag: etag() };
    },
    'ui/update-model-context': () => ({}),
    'ui/message': (params) => {
      onMessage?.(params.content?.[0]?.text ?? '', params);
      return {};
    },
    'ui/open-link': (params) => {
      if (params?.url) window.open(params.url, '_blank', 'noopener');
      return {};
    },
    ping: () => ({}),
  };

  window.addEventListener('message', (event) => {
    if (event.source !== frame.contentWindow) return;
    const message = event.data;
    if (!message || message.jsonrpc !== '2.0') return;
    if (message.method === 'ui/notifications/initialized') {
      send({ method: 'ui/notifications/tool-input',
        params: { arguments: { file: { name: fileName, resourceUri: uri } } } });
      return;
    }
    if (!message.method || message.id == null) return;
    const handler = handlers[message.method];
    try {
      if (!handler) throw new Error('unsupported ' + message.method);
      send({ id: message.id, result: handler(message.params ?? {}) });
    } catch (error) {
      send({ id: message.id, error: { code: -32603, message: String(error.message ?? error) } });
    }
  });

  frame.srcdoc = shell;
  return {
    text: () => state.text,
    /** Replaces the file, as an agent editing it would; the editor reloads it. */
    replace(next) {
      state.text = next;
      state.version++;
      send({ method: 'notifications/resources/updated', params: { uri } });
    },
  };
}
