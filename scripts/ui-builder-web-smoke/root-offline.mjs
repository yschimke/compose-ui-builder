import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { join, resolve, extname } from 'node:path';
import { verifyPublishedOfflineCreation } from './published-offline.mjs';

// Model the root host's HTTP contract: one notification registration imports the editor's
// offline handler; server routes stay outside its cache. The JVM host tests verify the emitted
// manifest gate, wrapper, meta tag and reserved-path list against the real server implementation.
export async function verifyRootPublishedOfflineCreation(root, launchOptions, shots) {
  const types = {
    '.html': 'text/html',
    '.js': 'text/javascript',
    '.mjs': 'text/javascript',
    '.wasm': 'application/wasm',
    '.json': 'application/json',
    '.ttf': 'font/ttf',
    '.png': 'image/png',
    '.svg': 'image/svg+xml'
  };
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/push-sw.js') {
      res.writeHead(200, {
        'content-type': 'text/javascript',
        'cache-control': 'no-cache',
        'service-worker-allowed': '/'
      });
      res.end(
        `self.composeUiBuilderOffline={assetBase:'/ui-builder/',reservedPaths:['api','admin','auth','oauth','healthz','status.json','push-sw.js','ui-builder']};\nself.addEventListener('install', e=>e.waitUntil(self.skipWaiting()));\nself.addEventListener('push',()=>{});\nimportScripts('/ui-builder/ui-builder-sw.js');`
      );
      return;
    }
    const relative = decodeURIComponent(url.pathname).replace(/^\/ui-builder\//, '/');
    const file = resolve(root, '.' + (relative === '/' ? '/index.html' : relative));
    if (!file.startsWith(resolve(root) + '/')) {
      res.writeHead(403).end();
      return;
    }
    try {
      let body = await readFile(file);
      if (file.endsWith('/index.html')) {
        body = body
          .toString()
          .replace(
            '</head>',
            '<meta name="ui-builder-base-path" content="/"><meta name="ui-builder-offline-worker" content="/push-sw.js"></head>'
          )
          .replace(
            /\b(src|href)="(?!\/|https?:|data:|#)([^" ]+)"/g,
            (_, attr, path) => `${attr}="/ui-builder/${path.replace(/^\.\//, '')}"`
          )
          .replaceAll('"./js-joda.esm.js"', '"/ui-builder/js-joda.esm.js"');
      }
      res
        .writeHead(200, {
          'content-type': types[extname(file)] || 'application/octet-stream',
          'cache-control': 'no-cache'
        })
        .end(body);
    } catch {
      res.writeHead(404).end();
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const rootShots = shots ? join(shots, 'root-host') : null;
  if (rootShots) await mkdir(rootShots, { recursive: true });
  try {
    await verifyPublishedOfflineCreation(
      `http://127.0.0.1:${server.address().port}`,
      root,
      launchOptions,
      rootShots,
      true
    );
    console.log('ok   root host: cold offline creation and shared notification registration');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}
