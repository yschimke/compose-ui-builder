/** Real packaged editor + harc host fixture. No Home Assistant writes.
 * node lovelace-dashboard.mjs WASM_DIST HARC_CHECKOUT CAPABILITIES_JSON [EVIDENCE_DIR]
 * Catalog capabilities must be composed by PublishedUiBuilderCatalog, not hand-transcribed.
 */
import assert from 'node:assert/strict';
import http from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';
const [dist, harc, capabilities, evidence = 'build/lovelace-browser'] = process.argv.slice(2);
if (!capabilities) throw new Error('Provide Wasm dist, harc checkout and composed catalog capabilities');
const fixture = JSON.parse(await readFile(path.join(harc, 'design-server/src/main/resources/dashboard/fixture.json')));
const catalog = await readFile(capabilities);
const rcDirectory = process.env.LOVELACE_RC_FIXTURES;
const rc = (await readFile(path.join(harc, 'design-server/src/test/resources/tile-light.rc'))).toString('base64');
let renders = 0, failPreviews = false;
const renderRequests = [], rawRenderRequests = [];
let remoteDashboard = structuredClone(fixture), writes = 0, admin = true, mode = 'storage', haSocket, subscription, subscribedEntities = [];
const haStates = [{ entity_id: 'light.sofa', state: 'on', attributes: {friendly_name:'Sofa'} }, { entity_id:'sensor.room', state:'21.5', attributes:{device_class:'temperature'} }, {entity_id:'climate.boiler',state:'heat',attributes:{}}];
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    let file, type;
    if (url.pathname === '/api/studio/dashboard/catalog') { res.setHeader('Content-Type', 'application/json'); return res.end(catalog); }
    if (url.pathname === '/api/studio/dashboard/access') { res.setHeader('Content-Type', 'application/json'); return res.end('{"authorized":true}'); }
    if (url.pathname === '/api/studio/dashboard/preview') {
      let body = ''; for await (const chunk of req) body += chunk;
      rawRenderRequests.push(body);
      if (failPreviews) { res.writeHead(422); return res.end('Fixture renderer unavailable'); }
      const request = JSON.parse(body); assert.ok(request.card.type); assert.ok(request.snapshot.states);
      renderRequests.push(request); renders++; res.setHeader('Content-Type', 'application/json');
      let document = rc;
      if (rcDirectory) {
        const name = request.card.type === 'gauge' ? 'gauge' : request.card.type.startsWith('custom:') ? 'custom' : request.card.entity === 'climate.boiler' ? 'climate' : request.style === 'braun' && request.dark ? 'tile-braun-dark' : request.snapshot.states['light.sofa']?.state === 'off' ? 'tile-off' : 'tile-on';
        document = (await readFile(path.join(rcDirectory,`${name}.rc`))).toString('base64');
      }
      return res.end(JSON.stringify({ properties: { documentBase64: { type: 'string', value: document } } }));
    }
    if (url.pathname.startsWith('/studio/dashboard/assets/')) file = path.join(dist,url.pathname.slice('/studio/dashboard/assets/'.length));
    else if (url.pathname.startsWith('/studio/dashboard')) {
      const name = url.pathname === '/studio/dashboard' ? 'index.html' : path.basename(url.pathname);
      file = path.join(harc, 'design-server/src/main/resources/dashboard', name);
      if (name === 'templates.json') file = path.join(harc, 'lovelace-catalog/templates.json');
    } else if (url.pathname === '/embed/v1/ha-card.js') file = path.join(harc, 'embed/ha-card.js');
    else if (url.pathname.startsWith('/ui-builder/')) file = path.join(dist, url.pathname.slice('/ui-builder/'.length));
    else { res.writeHead(404); return res.end(); }
    type = file.endsWith('.wasm') ? 'application/wasm' : /\.(mjs|js)$/.test(file) ? 'text/javascript' : file.endsWith('.html') ? 'text/html' : file.endsWith('.json') ? 'application/json' : 'application/octet-stream';
    res.setHeader('Content-Type', type); res.end(await readFile(file));
  } catch (error) { res.writeHead(500); res.end(String(error)); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ headless: true, args: ['--no-sandbox', '--enable-unsafe-webgpu'] });
let page;
try {
  page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  const errors = []; page.on('pageerror', error => { errors.push(error.message); console.error('Browser error:',error.message); });
  await page.routeWebSocket('wss://dashboard-fixture.invalid/api/websocket', socket => {
    haSocket = socket;
    socket.onMessage(text => {
      const message = JSON.parse(text);
      if (message.type === 'auth') { socket.send(JSON.stringify({type:'auth_ok'})); return; }
      let result;
      if (message.type === 'lovelace/dashboards/list') result = [{url_path:'fixture',title:'Automated test dashboard',mode}];
      else if (message.type === 'auth/current_user') result = {is_admin:admin};
      else if (message.type === 'get_states') result = haStates;
      else if (message.type === 'lovelace/config') result = remoteDashboard;
      else if (message.type === 'lovelace/config/save') { assert.equal(message.url_path,'fixture'); remoteDashboard = structuredClone(message.config); writes++; result = null; }
      else if (message.type === 'subscribe_entities') { subscription = message.id; subscribedEntities = message.entity_ids; result = null; }
      else if (message.type === 'unsubscribe_events') { if (message.subscription === subscription) subscribedEntities = []; result = null; }
      else throw new Error(`Unexpected test HA request ${message.type}`);
      socket.send(JSON.stringify({id:message.id,type:'result',success:true,result}));
    });
    socket.send(JSON.stringify({type:'auth_required'}));
  });
  await page.addInitScript(() => {
    window.dashboardMessages = [];
    window.dashboardCanonical = value => Array.isArray(value) ? `[${value.map(window.dashboardCanonical).join(',')}]` : value && typeof value === 'object' ? `{${Object.keys(value).sort().map(key=>`${JSON.stringify(key)}:${window.dashboardCanonical(value[key])}`).join(',')}}` : JSON.stringify(value);
    window.addEventListener('message', e => { if (e.source === document.getElementById('editor')?.contentWindow) window.dashboardMessages.push(e.data); });
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/studio/dashboard`);
  await page.waitForFunction(() => window.dashboardMessages.some(m => m.type === 'compose-ui-builder/ready'), null, { timeout: 180000 });
  await page.locator('#fixture').click();
  const exported = () => page.locator('#json').inputValue().then(JSON.parse);
  await page.waitForFunction(() => window.dashboardMessages.some(m => m.type === 'compose-ui-builder/lovelace-config'));
  assert.deepEqual(await exported(), fixture);
  const embedded = page.frames().find(frame => frame.url().includes('/studio/dashboard/editor.html'));
  assert.ok(embedded);
  // Host controls use the editor's public chrome contract; canvas gestures stay for editing.
  await page.locator('#chrome').getByRole('button', {name:'Legacy',exact:true}).click();
  await embedded.waitForFunction(() => Object.values(globalThis.__uiBuilderUndrawn ?? {}).flat().some(node=>node.componentId==='lovelace/opaque-view'));
  await page.locator('#chrome').getByRole('button', {name:'Home',exact:true}).click();
  await embedded.waitForFunction(() => !Object.values(globalThis.__uiBuilderUndrawn ?? {}).flat().some(node=>node.componentId==='lovelace/opaque-view'));
  const document = await page.evaluate(() => JSON.parse(window.dashboardMessages.filter(m => m.type === 'compose-ui-builder/changed').at(-1).document));
  const cardId = Object.keys(document.nodes).find(id => document.nodes[id].properties.entity?.value === 'light.sofa');
  assert.ok(cardId);
  await page.locator('#chrome').getByRole('button', {name:'Legacy',exact:true}).click();
  await page.evaluate(id => document.getElementById('editor').contentWindow.postMessage({type:'compose-ui-builder/select',nodeId:id}, location.origin), cardId);
  await page.waitForFunction(() => !document.getElementById('columns').disabled);
  await embedded.waitForFunction(() => !Object.values(globalThis.__uiBuilderUndrawn ?? {}).flat().some(node=>node.componentId==='lovelace/opaque-view'));
  assert.equal(await page.locator('#chrome').getByRole('button',{name:'Home',exact:true}).getAttribute('aria-pressed'), 'true');
  if (rcDirectory) {
    await embedded.waitForFunction(() => {
      const undrawn=globalThis.__uiBuilderUndrawn?.['host-dashboard@0'];
      return Array.isArray(undrawn) && !undrawn.some(node=>node.componentId.startsWith('lovelace/card/'));
    });
    await mkdir(evidence, {recursive:true});
    const canvas = embedded.locator('canvas').first();
    const classic = await canvas.screenshot({path:path.join(evidence,'cards-classic.png')});
    await page.locator('#style').selectOption('braun'); await page.locator('#dark').check();
    let styled;
    const deadline = Date.now()+10000;
    do {
      styled = await canvas.screenshot();
      if (!classic.equals(styled)) break;
    } while (Date.now()<deadline);
    assert.ok(!classic.equals(styled), 'actual RC card styling must change the rendered canvas');
    await writeFile(path.join(evidence,'cards-braun-dark.png'),styled);
    await page.locator('#style').selectOption('classic'); await page.locator('#dark').uncheck();
  }
  await page.locator('#columns').selectOption('12');
  await page.waitForFunction(() => JSON.parse(document.getElementById('json').value).views[0].sections[0].cards[0].grid_options.columns === 12);
  const resized = await exported();
  assert.deepEqual(resized.views[1], fixture.views[1]); assert.deepEqual(resized.views[2], fixture.views[2]);
  assert.deepEqual(resized.views[0].sections[0].cards[2], fixture.views[0].sections[0].cards[2]);
  await page.locator('#undo-edit').click();
  await page.waitForFunction(expected => window.dashboardCanonical(JSON.parse(document.getElementById('json').value)) === window.dashboardCanonical(expected), fixture);
  const customId = Object.keys(document.nodes).find(id => document.nodes[id].componentId === 'lovelace/card/custom');
  await page.evaluate(id => document.getElementById('editor').contentWindow.postMessage({type:'compose-ui-builder/select',nodeId:id},location.origin),customId);
  await page.waitForFunction(id => window.dashboardMessages.filter(m=>m.type==='compose-ui-builder/selection').at(-1)?.nodeId === id,customId);
  await page.frameLocator('#editor').locator('canvas').first().focus(); await page.keyboard.press('Delete');
  await page.waitForFunction(() => JSON.parse(document.getElementById('json').value).views[0].sections[0].cards.length === 2);
  await page.locator('#chrome').getByRole('button',{name:'Undo',exact:true}).click();
  await page.waitForFunction(expected => window.dashboardCanonical(JSON.parse(document.getElementById('json').value)) === window.dashboardCanonical(expected),fixture);
  const templates = JSON.parse(await readFile(path.join(harc, 'lovelace-catalog/templates.json')));
  for (const template of templates) {
    await page.locator('#template').selectOption(template.id);
    const before = await exported();
    await page.locator('#place').click();
    await page.waitForFunction(previous => document.getElementById('json').value !== previous, JSON.stringify(before,null,2));
    const after = await exported();
    if (template.kind === 'View') assert.equal(after.views.length, before.views.length + 1);
    else assert.equal(after.views[0].sections.length, before.views[0].sections.length + 1);
    assert.ok(!JSON.stringify(after).includes('climate.s0'));
  }
  await page.evaluate(() => document.getElementById('editor').contentWindow.postMessage({type:'compose-ui-builder/select',nodeId:'dashboard-0-0'},location.origin));
  await page.waitForFunction(() => window.dashboardMessages.filter(m=>m.type==='compose-ui-builder/selection').at(-1)?.nodeId === 'dashboard-0-0');
  const templateDownload = page.waitForEvent('download'); await page.locator('#capture-template').click();
  const captured = JSON.parse(await readFile(await (await templateDownload).path()));
  assert.equal(captured.kind,'Section'); assert.ok(captured.slots.some(slot=>slot.domain==='sensor' && slot.deviceClass==='temperature'));
  assert.equal(captured.config.cards[2].type,'custom:mushroom-template-card');
  await page.locator('#template-file').setInputFiles({name:'section-template.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(captured))});
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('Template imported'));
  const sectionsBefore = (await exported()).views[0].sections.length;
  await page.locator('#place').click();
  await page.waitForFunction(count => JSON.parse(document.getElementById('json').value).views[0].sections.length === count+1,sectionsBefore);
  assert.deepEqual((await exported()).views[0].sections.at(-1).cards[2],fixture.views[0].sections[0].cards[2]);
  await page.waitForFunction(() => window.dashboardMessages.some(m => m.type === 'compose-ui-builder/chrome'));
  assert.ok(renders > 0, 'host requested actual RC fixture playback');
  const download = page.waitForEvent('download'); await page.locator('#export').click();
  const artifact = await download;
  assert.deepEqual(JSON.parse(await readFile(await artifact.path())), await exported());
  await mkdir(evidence, {recursive:true}); await page.screenshot({path:path.join(evidence,'dashboard.png')});
  // Exercise the real host's HA transport against a fixture-only WebSocket endpoint.
  await page.evaluate(() => localStorage.setItem('ha-card-auth:https://dashboard-fixture.invalid', JSON.stringify({hassUrl:'https://dashboard-fixture.invalid',access_token:'fixture-only',refresh_token:'fixture-only',expires:Date.now()+3600000})));
  await page.getByText('Connect to your Home Assistant', {exact:true}).click();
  await page.locator('#hass').fill('https://dashboard-fixture.invalid');
  await page.locator('#connect').click();
  await page.waitForFunction(() => [...document.getElementById('dashboard').options].some(o => o.value === 'fixture'));
  await page.locator('#dashboard').selectOption('fixture'); await page.locator('#load').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('Dashboard loaded'));
  await page.waitForFunction(() => !document.getElementById('save').disabled);
  await page.evaluate(id => document.getElementById('editor').contentWindow.postMessage({type:'compose-ui-builder/select',nodeId:id},location.origin),cardId);
  await page.waitForFunction(() => !document.getElementById('entity').disabled);
  await page.locator('#entity').selectOption('sensor.room');
  await page.waitForFunction(() => JSON.parse(document.getElementById('json').value).views[0].sections[0].cards[0].entity === 'sensor.room');
  remoteDashboard.externalChange = true;
  await page.locator('#save').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Dashboard conflict'));
  assert.equal(writes,0); assert.equal((await exported()).views[0].sections[0].cards[0].entity,'sensor.room');
  failPreviews = true; await page.locator('#dark').check();
  await page.waitForFunction(() => document.getElementById('preview-status').textContent.includes('unavailable'));
  assert.ok((await page.locator('#status').textContent()).includes('Dashboard conflict'));
  failPreviews = false; await page.locator('#dark').uncheck();

  delete remoteDashboard.externalChange;
  await page.locator('#save').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('Dashboard saved'));
  assert.equal(writes,1); assert.equal(remoteDashboard.views[0].sections[0].cards[0].entity,'sensor.room');
  remoteDashboard.externalChange = true;
  await page.locator('#undo-save').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Dashboard conflict'));
  assert.equal(writes,1);
  delete remoteDashboard.externalChange;
  await page.locator('#undo-save').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('Saved dashboard restored'));
  assert.equal(writes,2); assert.deepEqual(remoteDashboard,fixture);
  await new Promise((resolve,reject) => { const deadline=Date.now()+10000; const poll=()=>subscribedEntities.includes('light.sofa') ? resolve() : Date.now()>deadline ? reject(new Error('host did not resubscribe to restored entities')) : setTimeout(poll,50); poll(); });
  haSocket.send(JSON.stringify({id:subscription,type:'event',event:{c:{'light.sofa':{'+':{s:'off'}}}}}));
  await page.waitForFunction(() => document.getElementById('json').value.includes('light.sofa'));
  await page.locator('#style').selectOption('braun'); await page.locator('#dark').check();
  await new Promise((resolve,reject) => { const deadline=Date.now()+10000; const poll=()=>renderRequests.some(r=>r.style==='braun' && r.dark && r.snapshot.states['light.sofa']?.state==='off') ? resolve() : Date.now()>deadline ? reject(new Error('live state/style did not reach rendering')) : setTimeout(poll,50); poll(); });
  admin=false; await page.locator('#load').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('Administrator access'));
  assert.ok(await page.locator('#save').isDisabled());
  admin=true; mode='yaml'; await page.locator('#connect').click();
  await page.waitForFunction(() => document.querySelector('#dashboard option[value="fixture"]').dataset.mode==='yaml');
  await page.locator('#dashboard').selectOption('fixture'); await page.locator('#load').click();
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('YAML dashboards'));
  assert.ok(await page.locator('#save').isDisabled()); assert.equal(writes,2);
  await page.locator('#file').setInputFiles({name:'invalid-dashboard.json',mimeType:'application/json',buffer:Buffer.from('{"views":"invalid"}')});
  await page.waitForFunction(() => document.getElementById('status').textContent.includes('must be an array'));
  assert.ok(await page.locator('#save').isDisabled()); assert.ok(await page.locator('#export').isDisabled()); assert.equal(writes,2);
  const large = '{"views":[{"type":"sections","sections":[{"cards":[{"type":"tile","entity":"light.sofa","future":9007199254740993}]}]}]}';
  await page.locator('#file').setInputFiles({name:'large-number-dashboard.json',mimeType:'application/json',buffer:Buffer.from(large)});
  await page.waitForFunction(() => !document.getElementById('export').disabled);
  await new Promise((resolve,reject) => { const deadline=Date.now()+10000; const poll=()=>rawRenderRequests.some(body=>body.includes('9007199254740993')) ? resolve() : Date.now()>deadline ? reject(new Error('large unknown value did not survive preview request')) : setTimeout(poll,50); poll(); });
  await embedded.waitForFunction(() => {
    const undrawn=globalThis.__uiBuilderUndrawn?.['host-dashboard@0'];
    return Array.isArray(undrawn) && !undrawn.some(node=>node.componentId.startsWith('lovelace/card/'));
  });
  const largeDownload=page.waitForEvent('download'); await page.locator('#export').click();
  assert.ok((await readFile(await (await largeDownload).path(),'utf8')).includes('9007199254740993'));
  assert.equal(writes,2);

  await readFile(path.join(evidence,'dashboard.png'));
  assert.deepEqual(errors, []);
  const report = {passed:true,renders,evidence,renderSource:rcDirectory ? 'actual harc converter capture matrix' : 'committed RC playback fixture',checks:['fixture round trip','view tab switching','large integer import/export/preview','grid resize','unknown custom preservation','delete/undo through editor keyboard','undo edit','section/view templates','template capture/import/reuse','invalid import cannot save','JSON download','RC playback and rendered style change','host HA save/undo conflicts','preview failures preserve conflict feedback','live state/style inputs','admin and YAML save restrictions']};
  await writeFile(path.join(evidence,'report.json'),JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify(report));
} catch (error) {
  await mkdir(evidence, {recursive:true});
  if (page) { await page.screenshot({path:path.join(evidence,'failure.png')}); console.error(await page.evaluate(() => ({status:document.getElementById('status')?.textContent,messages:window.dashboardMessages?.slice(-8)}))); }
  throw error;
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
