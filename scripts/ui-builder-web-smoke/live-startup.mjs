import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { chromium } from 'playwright';

// Exercise the saved-design path, not VisualFixtureApp: the open carries its own catalog.
// Fail the optional catalog listing and require the already-rendered design to stay usable.
export async function verifyDesignFirstStartup(base, root, launchOptions, shots) {
  const fixture = JSON.parse(await readFile(join(root, 'jetcaster-discover-operations-v1.json'), 'utf8'));
  const catalog = JSON.parse(await readFile(join(root, 'm3-catalog-capabilities-v1.json'), 'utf8'));
  const create = fixture.operations[0];
  const document = {
    schema: fixture.documentSchema, id: fixture.designId, title: create.title, revision: 108,
    catalogPin: create.catalogPin, environment: create.environment,
    stateVariables: create.stateVariables, roots: [], nodes: {},
  };
  for (const operation of fixture.operations.slice(1)) {
    assert.equal(operation.type, 'insertNode');
    const node = structuredClone(operation.node);
    node.slots ??= {};
    document.nodes[node.id] = node;
    if (operation.parent) {
      const slots = document.nodes[operation.parent.nodeId].slots;
      (slots[operation.parent.slot] ??= []).push(node.id);
    } else document.roots.push(node.id);
  }
  const browser = await chromium.launch(launchOptions);
  const page = await browser.newPage({viewport: {width: 1400, height: 900}, locale: 'en-US'});
  let releaseFont;
  let releaseIdentity;
  let closing = false;
  const fontGate = new Promise(resolve => { releaseFont = resolve; });
  const identityGate = new Promise(resolve => { releaseIdentity = resolve; });
  try {
    const errors = [];
    const calls = [];
    let catalogMarks;
    let catalogFailureHandled = false;
    page.on('console', message => { if (message.text().includes('could not load the New design catalogs')) catalogFailureHandled = true; });
    page.on('pageerror', error => { errors.push(String(error)); console.error('live page error:', String(error)); });
    let identityRequests = 0;
    let identityStatus = 200;
    let identityResponse = {actorId: 'startup-test', canWrite: false};
    let fontRequests = 0;
    await page.route('**/fonts/RobotoFlex.ttf', async route => {
      fontRequests++;
      await fontGate;
      if (!closing) await route.continue();
    });
    await page.route('**/identity', async route => {
      identityRequests++;
      await identityGate;
      if (!closing) await route.fulfill({status: identityStatus, json: identityResponse});
    });
    await page.route('**/api/ui-builder/v1/requests', async route => {
      const {requestId, request} = route.request().postDataJSON();
      calls.push(request.type);
      if (process.env.SMOKE_DEBUG) console.log('live request:', request.type);
      if (request.type === 'listCatalogs') {
        catalogMarks = await page.evaluate(() => ({...globalThis.__uiBuilderStartup.marks}));
        await route.fulfill({status: 503, body: 'catalog list unavailable'});
        return;
      }
      const response = request.type === 'openDesign' ? {
        type: 'snapshot', snapshot: {
          designId: document.id, state: {lastSequence: 108, document}, catalog, retainedFromSequence: 0,
        },
      } : {type: 'error', error: {code: 'notFound', message: 'not part of this fixture'}};
      await route.fulfill({json: {schemaVersion: 1, requestId, response}});
    });
    await page.goto(`${base}/index.html?session=live&designId=${document.id}`, {waitUntil: 'commit'});
    const overlapDeadline = Date.now() + 10000;
    while ((!identityRequests || !fontRequests) && Date.now() < overlapDeadline) {
      await new Promise(resolve => setTimeout(resolve, 25));
    }
    assert.equal(fontRequests, 1, 'the Wear font must start loading');
    assert.equal(identityRequests, 1, 'identity lookup must start while the font response is held');
    await page.waitForFunction(() => globalThis.__uiBuilderStartup?.marks['storage-ready'] !== undefined, null, {timeout: 10000});
    assert.deepEqual(calls, [], 'design requests must wait for identity');
    const beforeFont = await page.evaluate(() => globalThis.__uiBuilderStartup.marks);
    assert.equal(beforeFont['compose-start'], undefined, 'composition must wait for font registration');
    releaseFont();
    await page.waitForFunction(() => globalThis.__uiBuilderStartup?.marks['fonts-ready'] !== undefined);
    assert.deepEqual(calls, [], 'finishing fonts must not bypass identity');
    releaseIdentity();
    await page.waitForFunction(() => globalThis.__uiBuilderStartup?.marks['editor-paint-opportunity'] !== undefined, null, {timeout: 30000});
    await page.waitForFunction(() => globalThis.__uiBuilderInspection?.generation?.completed, null, {timeout: 30000});
    await page.waitForFunction(() => performance.getEntriesByType('resource').some(e => e.name.includes('/api/ui-builder/v1/requests')), null, {timeout: 10000});
    // Poll from the harness; the failed catalog response must have reached the application.
    const deadline = Date.now() + 10000;
    while (!catalogMarks && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 25));
    assert.ok(catalogMarks, `no catalog listing; calls: ${calls}`);
    assert.ok(catalogMarks['editor-paint-opportunity'] !== undefined, 'catalog listing competed with the initial design paint');
    assert.ok(calls.indexOf('openDesign') < calls.indexOf('listCatalogs'), calls.join(', '));
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === 'true');
    while (!catalogFailureHandled && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 25));
    assert.ok(catalogFailureHandled, 'catalog failure was not handled independently');
    assert.equal(identityRequests, 1, 'composition must reuse the startup identity lookup');
    assert.deepEqual(errors, []);
    if (shots) await page.screenshot({path: join(shots, 'live-startup.png')});
    // A completed 401 must still stop the open, even though the request began during font loading.
    identityStatus = 401;
    identityResponse = {signInUrl: '/login'};
    calls.length = 0;
    await page.goto(`${base}/index.html?session=live&designId=${document.id}`);
    await page.waitForFunction(() => globalThis.__uiBuilderStartup?.marks['identity-ready'] !== undefined && globalThis.__uiBuilderStartup?.marks['boot-hidden'] !== undefined);
    assert.deepEqual(calls, [], 'a sign-in requirement must prevent design requests');
    assert.deepEqual(errors, []);
    console.log('ok   live startup: font/storage/identity overlap; auth/font gates preserved; catalogs follow paint');
  } catch (error) {
    if (shots) await page.screenshot({path: join(shots, 'live-startup-failed.png')});
    console.error(await page.evaluate(() => ({startup: globalThis.__uiBuilderStartup, status: document.documentElement.dataset})));
    throw error;
  } finally { closing = true; releaseFont(); releaseIdentity(); await browser.close(); }
}
