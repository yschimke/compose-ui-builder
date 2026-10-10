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

for (const [marker, storageKey] of [
  ['openrouter-callback', 'ui-builder.guidelines.pkce-verifier'],
  ['openrouter-chat-callback', 'ui-builder.chat.pkce-verifier'],
]) {
  test(`${marker} restores editor configuration locally before boot`, async () => {
    const original = new URL('https://editor.example/ui-builder/?token=private-token&code=stale&actor=viewer&storage=local&designId=local-design&catalog=wear&rendererRuntimeId=runtime&mode=editor&session=s&clientId=c&displayName=Viewer&color=blue&endpoint=https%3A%2F%2Fhost.example%2Fd&updatesEndpoint=wss%3A%2F%2Fhost.example%2Fu&revision=7&node=n#thread=t');
    const storage = new Map();
    let redirected;
    let href = original.toString();
    let complete;
    const navigation = new Promise(resolve => { complete = resolve; });
    const globals = {
      URL, Uint8Array, TextEncoder, btoa,
      crypto: {
        getRandomValues: bytes => bytes.fill(42),
        subtle: { digest: async () => new Uint8Array(32).buffer },
      },
      sessionStorage: {
        setItem: (key, value) => storage.set(key, value),
        getItem: key => storage.get(key),
        removeItem: key => storage.delete(key),
      },
      globalThis: {
        location: {
          get href() { return href; },
          set href(value) { redirected = value; complete(); },
        },
        history: { state: {}, replaceState: (_state, _title, value) => { href = value; } },
      },
    };
    jsFunction('beginOpenRouterSignIn', globals, guidelineSource)(storageKey, marker);
    await navigation;
    const callback = new URL(new URL(redirected).searchParams.get('callback_url'));
    assert.equal(callback.searchParams.has('actor'), false);
    assert.equal(callback.searchParams.has('storage'), false);
    assert.equal(callback.searchParams.has('token'), false);
    assert.equal(callback.hash, '');
    assert.equal(new URL(storage.get(storageKey + '.page')).searchParams.has('token'), false);
    callback.searchParams.set('code', 'fresh-code');
    href = callback.toString();
    jsFunction('restoreOpenRouterPageAtBoot', globals, guidelineSource)();
    const restored = new URL(href);
    for (const [key, value] of original.searchParams) {
      if (key === 'token' || key === 'code') continue;
      assert.equal(restored.searchParams.get(key), value, key);
    }
    assert.equal(restored.searchParams.has('token'), false);
    assert.equal(restored.searchParams.get('code'), 'fresh-code');
    assert.equal(restored.searchParams.get(marker), '1');
    assert.equal(restored.hash, '#thread=t');
    assert.equal(storage.has(storageKey + '.page'), false);
  });
}

test('OAuth page restoration ignores state for another origin or design path', () => {
  for (const saved of ['https://other.example/ui-builder/design?actor=other',
    'https://editor.example/ui-builder/other?actor=other']) {
    let href = 'https://editor.example/ui-builder/design?openrouter-chat-callback=1&code=code';
    const restore = jsFunction('restoreOpenRouterPageAtBoot', {
      URL,
      sessionStorage: { getItem: () => saved, removeItem: () => {} },
      globalThis: {
        location: { get href() { return href; } },
        history: { state: null, replaceState: (_state, _title, value) => { href = value; } },
      },
    }, guidelineSource);
    restore();
    assert.equal(new URL(href).searchParams.has('actor'), false);
  }
});

test('abandoning a chat OAuth return removes its code and verifier without a provider request', () => {
  const storage = new Map([
    ['ui-builder.chat.pkce-verifier', 'verifier'],
    ['ui-builder.chat.pkce-verifier.page', 'page'],
  ]);
  let cleaned;
  const discard = jsFunction('discardChatSignInReturn', {
    URL,
    sessionStorage: { removeItem: key => storage.delete(key) },
    fetch: () => { assert.fail('an abandoned return must not mint a key'); },
    globalThis: {
      location: { href: 'https://editor.example/ui-builder/design?actor=viewer&openrouter-chat-callback=1&code=code#thread=t' },
      history: { state: null, replaceState: (_state, _title, url) => { cleaned = url; } },
    },
  });
  discard();
  assert.equal(storage.size, 0);
  const url = new URL(cleaned);
  assert.equal(url.searchParams.has('code'), false);
  assert.equal(url.searchParams.has('openrouter-chat-callback'), false);
  assert.equal(url.searchParams.get('actor'), 'viewer');
  assert.equal(url.hash, '#thread=t');
});
