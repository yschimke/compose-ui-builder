// Chromium driven over a bare DevTools Protocol socket, for measurements Playwright would skew.
//
// Playwright enables the Network domain on every page it attaches to, and with it DevTools keeps
// each response body under its per-resource limit in the renderer: ~16 MB of the editor's scripts
// and `skiko.wasm`, and the whole `uiBuilder.wasm` once it is small enough to qualify. That is
// memory the editor never holds in a user's browser. This launcher enables only the domains a
// caller asks for, so nothing is retained on the page's behalf.
//
// It launches the same headless shell Playwright would, with Playwright's default switches, so a
// figure taken here differs from a Playwright one by what DevTools retained, not by the browser.

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// `chromium.launch()`'s switches in Playwright 1.56, read from the launched process's command line,
// less `--remote-debugging-pipe` (this connects over a port instead) and `--no-startup-window`.
export const PLAYWRIGHT_SWITCHES = [
  '--font-render-hinting=none', '--disable-lcd-text', '--force-color-profile=srgb',
  '--disable-field-trial-config', '--disable-background-networking', '--disable-background-timer-throttling',
  '--disable-backgrounding-occluded-windows', '--disable-back-forward-cache', '--disable-breakpad',
  '--disable-client-side-phishing-detection', '--disable-component-extensions-with-background-pages',
  '--disable-component-update', '--no-default-browser-check', '--disable-default-apps', '--disable-dev-shm-usage',
  '--disable-extensions',
  '--disable-features=AcceptCHFrame,AvoidUnnecessaryBeforeUnloadCheckSync,DestroyProfileOnBrowserClose,' +
    'DialMediaRouteProvider,GlobalMediaControls,HttpsUpgrades,LensOverlay,MediaRouter,PaintHolding,' +
    'ThirdPartyStoragePartitioning,Translate,AutoDeElevate,RenderDocument',
  '--enable-features=CDPScreenshotNewSurface', '--allow-pre-commit-input', '--disable-hang-monitor',
  '--disable-ipc-flooding-protection', '--disable-popup-blocking', '--disable-prompt-on-repost',
  '--disable-renderer-backgrounding', '--metrics-recording-only', '--no-first-run', '--password-store=basic',
  '--use-mock-keychain', '--no-service-autorun', '--export-tagged-pdf', '--disable-search-engine-choice-screen',
  '--unsafely-disable-devtools-self-xss-warnings', '--enable-automation', '--headless', '--hide-scrollbars',
  '--mute-audio',
  '--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=4',
  '--no-sandbox',
];

/**
 * The headless shell beside Playwright's Chromium: `chromium.launch()` runs it, while
 * `chromium.executablePath()` names the full browser.
 */
export function headlessShellPath(chromiumPath) {
  const shell = chromiumPath.replace(
    /chromium-(\d+)([/\\])chrome-linux\2chrome$/,
    (_, revision, sep) => `chromium_headless_shell-${revision}${sep}chrome-linux${sep}headless_shell`,
  );
  return shell !== chromiumPath && existsSync(shell) ? shell : chromiumPath;
}

/** One DevTools connection: browser-level calls, and flattened sessions for pages. */
class Connection {
  #socket;
  #nextId = 0;
  #pending = new Map();
  #listeners = new Map();

  constructor(socket) {
    this.#socket = socket;
    socket.addEventListener('message', (event) => {
      const message = JSON.parse(event.data);
      if (message.id !== undefined) {
        const pending = this.#pending.get(message.id);
        if (!pending) return;
        this.#pending.delete(message.id);
        if (message.error) pending.reject(new Error(`${pending.method}: ${message.error.message}`));
        else pending.resolve(message.result);
        return;
      }
      for (const listener of this.#listeners.get(`${message.sessionId ?? ''} ${message.method}`) ?? []) {
        listener(message.params);
      }
    });
    socket.addEventListener('close', () => {
      for (const { reject, method } of this.#pending.values()) reject(new Error(`${method}: connection closed`));
      this.#pending.clear();
    });
  }

  send(method, params = {}, sessionId) {
    const id = ++this.#nextId;
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject, method });
      this.#socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    });
  }

  on(method, listener, sessionId) {
    const key = `${sessionId ?? ''} ${method}`;
    if (!this.#listeners.has(key)) this.#listeners.set(key, []);
    this.#listeners.get(key).push(listener);
    return () => this.#listeners.set(key, this.#listeners.get(key).filter((l) => l !== listener));
  }

  close() {
    this.#socket.close();
  }
}

/** A page target's session on [connection]. */
export class Session {
  constructor(connection, sessionId) {
    this.connection = connection;
    this.sessionId = sessionId;
  }

  send(method, params) {
    return this.connection.send(method, params, this.sessionId);
  }

  on(method, listener) {
    return this.connection.on(method, listener, this.sessionId);
  }

  /** The value of [expression], awaited if it is a promise; throws what the page threw. */
  async evaluate(expression) {
    const { result, exceptionDetails } = await this.send('Runtime.evaluate', {
      expression, awaitPromise: true, returnByValue: true,
    });
    if (exceptionDetails) {
      throw new Error(exceptionDetails.exception?.description ?? exceptionDetails.text ?? 'evaluation failed');
    }
    return result.value;
  }

  /** Polls [expression] until it is truthy, as Playwright's `waitForFunction` does. */
  async waitFor(expression, timeoutMs, what = expression) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      if (await this.evaluate(expression).catch(() => false)) return;
      if (Date.now() > deadline) throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 100));
    }
  }
}

/**
 * Launches [executable] headless with [args] after Playwright's defaults and connects to it.
 * `stdout()` is everything the browser printed there (V8's `--trace-*` output goes to stdout).
 */
export async function launch(executable, args = []) {
  const profile = await mkdtemp(join(tmpdir(), 'ui-builder-cdp-'));
  const child = spawn(executable, [...PLAYWRIGHT_SWITCHES, ...args, `--user-data-dir=${profile}`,
    // No startup page, as Playwright: a second tab would be a second renderer in every total.
    '--remote-debugging-port=0', '--no-startup-window'], { stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => { stdout += chunk; });
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${executable} did not start:\n${stderr}`)), 30_000);
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
      const match = stderr.match(/DevTools listening on (ws:\/\/\S+)/);
      if (match) {
        clearTimeout(timer);
        resolve(match[1]);
      }
    });
    child.once('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`${executable} exited with ${code} before listening:\n${stderr}`));
    });
  });
  const socket = new WebSocket(endpoint);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', () => reject(new Error(`could not connect to ${endpoint}`)), { once: true });
  });
  const connection = new Connection(socket);
  const exited = new Promise((resolve) => child.once('exit', resolve));
  return {
    connection,
    pid: child.pid,
    stdout: () => stdout,
    /** A new page at about:blank, attached, with nothing enabled. */
    async newPage() {
      const { targetId } = await connection.send('Target.createTarget', { url: 'about:blank' });
      const { sessionId } = await connection.send('Target.attachToTarget', { targetId, flatten: true });
      return new Session(connection, sessionId);
    },
    async close() {
      await connection.send('Browser.close').catch(() => {});
      connection.close();
      const killer = setTimeout(() => child.kill('SIGKILL'), 5_000);
      await exited;
      clearTimeout(killer);
      await rm(profile, { recursive: true, force: true });
    },
  };
}
