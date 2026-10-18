import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { chromium } from 'playwright';

// Await IndexedDB before testing its value; waitForFunction treats a pending Promise as truthy.
async function pollBrowserValue(page, read, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const value = await page.evaluate(read);
    if (value) return value;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error('timed out waiting for the published template browser store');
}

// The template has no Kotlin factory. Warm the real browser stores, cut the network, reload,
// and use the chooser to create a new design. The worker supplies the app; IndexedDB supplies
// the catalog and published starting document. Nothing is written to a server.
export async function verifyPublishedOfflineCreation(base, root, launchOptions, shots, rooted = false) {
  const catalog = JSON.parse(await readFile(join(root, 'm3-catalog-capabilities-v1.json'), 'utf8'));
  const system = 'published-only';
  catalog.benchmark.catalogSystemId = system;
  catalog.statusSemantics = {
    ...catalog.statusSemantics, platform: 'mobile',
    templates: ['ui-builder/designs/published-card.json'],
    newDesign: {label: 'Published only', primary: true, templates: [{id: 'published-card', label: 'Published offline card'}]},
  };
  const template = {
    schema: 'compose-ui-builder-document/v1-candidate', id: 'source-card', title: 'Published offline card', revision: 17,
    catalogPin: {systemId: system, catalogRevision: 'candidate', capabilityDigest: 'candidate', nativeRuntimeId: 'candidate'},
    environment: {widthDp: 320, heightDp: 480, density: 1, theme: 'dark', locale: 'en-US', fontScale: 1, layoutDirection: 'ltr', exportDevices: ['id:pixel_6']}, stateVariables: {}, roots: ['published-root'],
    nodes: {'published-root': {id: 'published-root', componentId: 'layout/column', properties: {}, modifiers: [], slots: {content: []}}},
  };
  const browser = await chromium.launch(launchOptions);
  const context = await browser.newContext({viewport: {width: 1400, height: 1000}, locale: 'en-US'});
  let page;
  let requests = 0;
  const errors = [];
  const watch = p => { p.on('pageerror', error => errors.push(String(error))); return p; };
  try {
    await context.route('**/api/ui-builder/v1/identity', route => route.fulfill({json: {actorId: 'offline-test', canWrite: false}}));
    await context.route('**/api/ui-builder/v1/requests', route => {
      const {requestId, request} = route.request().postDataJSON();
      const response = request.type === 'listCatalogs' ? {type: 'catalogs', catalogs: [catalog]}
        : {type: 'error', error: {code: 'notFound', message: 'not a fixture request'}};
      return route.fulfill({json: {schemaVersion: 1, requestId, response}});
    });
    await context.route(`**/api/ui-builder/v1/catalogs/${system}/templates`, route => {
      requests++;
      return route.fulfill({json: {
        schemaVersion: 1,
        catalogPin: {systemId: system, catalogRevision: catalog.benchmark.catalogRevision,
          capabilityDigest: 'candidate', nativeRuntimeId: catalog.benchmark.nativeRuntimeId},
        templates: {'published-card': {...template, nodes: {'published-root': {...template.nodes['published-root'], eventBindings: {}}}}},
      }});
    });
    page = watch(await context.newPage());
    const url = `${base}/${rooted ? '' : 'index.html'}?session=live&storage=local&catalog=${system}`;
    await page.goto(url);
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === 'true', null, {timeout: 120000});
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, {timeout: 120000});
    if (rooted) {
      const registration = await page.evaluate(async () => {
        const rows = await navigator.serviceWorker.getRegistrations();
        return rows.map(row => ({scope: row.scope, script: row.active?.scriptURL}));
      });
      assert.deepEqual(registration, [{scope: base + '/', script: base + '/push-sw.js'}]);
      // Notification settings registers this same URL. It must reuse the offline registration.
      assert.equal(await page.evaluate(async () => {
        const existing = await navigator.serviceWorker.getRegistration('/');
        const forPush = await navigator.serviceWorker.register('/push-sw.js', {scope: '/'});
        return existing.scope === forPush.scope && existing.active?.scriptURL === forPush.active?.scriptURL
          && (await navigator.serviceWorker.getRegistrations()).length === 1;
      }), true);
    }
    // Wait for the cache entry to reach IndexedDB, not just the page's in-memory mirror.
    await pollBrowserValue(page, () => new Promise((resolve, reject) => {
      const opening = indexedDB.open('ui-builder-local');
      opening.onerror = () => reject(opening.error);
      opening.onsuccess = () => {
        const db = opening.result;
        const tx = db.transaction('entries');
        const read = tx.objectStore('entries').getAllKeys();
        read.onsuccess = () => resolve(read.result.some(k => k.startsWith('ui-builder.local.templates.')));
        tx.oncomplete = () => db.close();
      };
    }));
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(requests, 1, 'the chooser must fetch the published set before the network goes away');
    if (shots) await page.screenshot({path: join(shots, 'published-online-chooser.png')});
    if (rooted) {
      // Clearing offline mode retains the notification worker. Re-enabling must restore the
      // full bundle without relying on an install event for the unchanged registration.
      await page.goto(url + '&sw=off');
      await page.waitForFunction(() => document.documentElement.dataset.uiBuilderOffline === 'disabled');
      await page.goto(url);
      await page.waitForFunction(() => document.documentElement.dataset.uiBuilderPrecached === 'ready', null, {timeout: 120000});
    }
    await page.close();
    // Remove the fixture responses too: an intercepted reply must not masquerade as offline data.
    await context.unrouteAll({behavior: 'wait'});
    await context.setOffline(true);
    page = watch(await context.newPage());
    const templatesUnavailable = page.waitForEvent('requestfailed', {predicate: request => request.url().includes(`/catalogs/${system}/templates`)});
    await page.goto(url);
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === 'true', null, {timeout: 120000});
    await templatesUnavailable;
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    // The home chooser is drawn on the Compose canvas, with no DOM text locators. At this fixed
    // viewport the Create control is at this point (recorded in the online screenshot).
    if (shots) await page.screenshot({path: join(shots, 'published-offline-chooser.png')});
    await page.mouse.click(245, 594);
    // The stored seed proves which template was used, even if this synthetic catalog has no
    // native renderer. The document must be new and pinned to the remembered capability.
    const created = await pollBrowserValue(page, () => new Promise((resolve, reject) => {
      const opening = indexedDB.open('ui-builder-local');
      opening.onerror = () => reject(opening.error);
      opening.onsuccess = () => {
        const db = opening.result;
        const tx = db.transaction('entries');
        const read = tx.objectStore('entries').getAll();
        read.onsuccess = () => {
          const row = read.result.map(text => { try { return JSON.parse(text); } catch { return null; } })
            .find(row => row?.seed?.nodes?.['published-root']);
          resolve(row ?? false);
        };
        tx.oncomplete = () => db.close();
      };
    }));
    const record = created;
    assert.notEqual(record.seed.id, template.id);
    assert.equal(record.seed.revision, 0);
    assert.deepEqual(record.seed.catalogPin, {
      systemId: system,
      catalogRevision: catalog.benchmark.catalogRevision,
      capabilityDigest: 'candidate',
      nativeRuntimeId: catalog.benchmark.nativeRuntimeId,
    });
    const withDefaults = nodes => Object.fromEntries(Object.entries(nodes).map(([id, node]) =>
      [id, {properties: {}, modifiers: [], slots: {}, ...node}]));
    assert.deepEqual(withDefaults(record.seed.nodes), withDefaults(template.nodes));
    assert.deepEqual(record.seed.environment, template.environment);
    assert.deepEqual(errors, []);
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === 'true', null, {timeout: 120000});
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    if (shots) {
      await page.screenshot({path: join(shots, 'published-offline-created.png')});
      await writeFile(join(shots, 'published-offline.json'), JSON.stringify({
        networkDisabled: true, fixtureRoutesRemoved: true, templateRequestsBeforeOffline: requests,
        rootHost: rooted, sharedNotificationRegistration: rooted, rootCacheRecoveryVerified: rooted,
        seed: record.seed,
      }, null, 2) + '\n');
    }
    console.log('ok   published templates: cold offline reload creates from the cached document with the network disabled');
  } catch (error) {
    console.error('published diagnostics:', errors, await page.evaluate(() => ({url: location.href, text: document.body.innerText, ready: document.documentElement.dataset.uiBuilderReady, html: document.getElementById('composeApp')?.outerHTML.slice(0, 1000)})).catch(() => null));
    if (shots && page && !page.isClosed()) await page.screenshot({path: join(shots, 'published-offline-failed.png')});
    throw error;
  } finally { await context.close(); await browser.close(); }
}
