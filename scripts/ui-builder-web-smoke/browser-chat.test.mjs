import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../ui-builder/src/wasmJsMain/kotlin/ee/schimke/composeai/uibuilder/BrowserChatHost.kt', import.meta.url), 'utf8');
const guidelineSource = readFileSync(new URL('../../ui-builder/src/wasmJsMain/kotlin/ee/schimke/composeai/uibuilder/BrowserGuidelineHost.kt', import.meta.url), 'utf8');

function jsFunction(name, globals, code = source) {
  const declaration = code.indexOf(`external fun ${name}(`);
  assert.ok(declaration >= 0, name);
  const annotation = code.slice(code.lastIndexOf('@JsFun(', declaration), declaration);
  const body = annotation.match(/@JsFun\(\s*"""([\s\S]*?)"""\s*\)/)?.[1];
  assert.ok(body, `JavaScript body for ${name}`);
  return vm.runInNewContext(`(${body})`, globals);
}

async function request(fetchResponse) {
  let call;
  let timer;
  let controller;
  const result = await new Promise((resolve) => {
    const fn = jsFunction('openBrowserChatRequest', {
      fetch: async (url, options) => { call = { url, options }; return fetchResponse; },
      AbortController, TextDecoder,
      setTimeout: (callback) => { timer = callback; return 1; },
      clearTimeout: () => {},
    });
    controller = fn('{"messages":[]}', 'test-provider-key',
      (text) => resolve({ text }), () => resolve({ error: true }));
  });
  return { call, result, timer, controller };
}

test('inference uses a fixed provider destination, omits cookies and refuses redirects', async () => {
  const { call, result } = await request(new Response('{"choices":[]}'));
  assert.equal(call.url, 'https://openrouter.ai/api/v1/chat/completions');
  assert.equal(call.options.credentials, 'omit');
  assert.equal(call.options.redirect, 'error');
  assert.equal(call.options.headers.Authorization, 'Bearer test-provider-key');
  assert.equal(call.options.headers.Cookie, undefined);
  assert.equal(call.options.headers['X-Compose-Preview-Token'], undefined);
  assert.equal(result.text, '{"choices":[]}');
});

test('provider failures never forward response bodies to the UI', async () => {
  const { result } = await request(new Response('sensitive-provider-response', { status: 401 }));
  assert.deepEqual(result, { error: true });
});

test('oversized provider replies are aborted', async () => {
  const { result, controller } = await request(new Response('x'.repeat(128001)));
  assert.deepEqual(result, { error: true });
  assert.equal(controller.signal.aborted, true);
});

test('stop aborts the actual request, rather than only ignoring its answer', async () => {
  let options;
  const fn = jsFunction('openBrowserChatRequest', {
    fetch: (_url, value) => { options = value; return new Promise(() => {}); },
    AbortController, TextDecoder, setTimeout: () => 1, clearTimeout: () => {},
  });
  const controller = fn('{}', 'key', () => {}, () => {});
  const abort = vm.runInNewContext('(request) => request.abort()');
  abort(controller);
  assert.equal(options.signal.aborted, true);
});

test('chat OAuth returns are exchanged separately from guideline sign-in', async () => {
  const storage = new Map([['chat-verifier', 'verifier']]);
  let cleaned;
  let call;
  const globals = {
    URL,
    globalThis: {
      location: { href: 'https://editor.example/ui-builder/design?openrouter-chat-callback=1&code=code' },
      history: { state: null, replaceState: (_state, _title, url) => { cleaned = url; } },
    },
    sessionStorage: { getItem: (key) => storage.get(key), removeItem: (key) => storage.delete(key) },
    fetch: async (url, options) => {
      call = { url, options };
      return new Response('{"key":"new-key"}');
    },
  };
  const finish = jsFunction('finishOpenRouterSignIn', globals, guidelineSource);
  const unrelated = JSON.parse(await finish('guidelines-verifier', 'openrouter-callback'));
  assert.equal(unrelated.state, 'none');
  assert.equal(call, undefined);
  const result = JSON.parse(await finish('chat-verifier', 'openrouter-chat-callback'));
  assert.equal(result.state, 'ok');
  assert.equal(call.url, 'https://openrouter.ai/api/v1/auth/keys');
  assert.equal(JSON.parse(call.options.body).code_verifier, 'verifier');
  assert.equal(new URL(cleaned).search, '');
  assert.equal(storage.has('chat-verifier'), false);
});

test('OAuth callback URLs never carry application tokens to OpenRouter', async () => {
  let redirect;
  const done = new Promise(resolve => {
    const location = {
      get href() { return 'https://editor.example/ui-builder/design?token=private-token&revision=7&node=n#thread=t'; },
      set href(value) { redirect = value; resolve(); },
    };
    const begin = jsFunction('beginOpenRouterSignIn', {
      URL, Uint8Array, TextEncoder, btoa,
      crypto: {
        getRandomValues: bytes => bytes.fill(42),
        subtle: { digest: async () => new Uint8Array(32).buffer },
      },
      sessionStorage: { setItem: () => {} },
      globalThis: { location },
    }, guidelineSource);
    begin('chat-verifier', 'openrouter-chat-callback');
  });
  await done;
  const callback = new URL(new URL(redirect).searchParams.get('callback_url'));
  assert.equal(callback.searchParams.has('token'), false);
  assert.equal(callback.searchParams.get('revision'), '7');
  assert.equal(callback.searchParams.get('node'), 'n');
  assert.equal(callback.searchParams.get('openrouter-chat-callback'), '1');
  assert.equal(callback.hash, '');
});
