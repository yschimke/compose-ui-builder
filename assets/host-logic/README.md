# Host-logic engine

`engine.mjs` runs a design's host logic in the browser editor: QuickJS, compiled to Wasm, with a
small adapter (`engine.src.mjs`). See
[`docs/design/UI_BUILDER_HOST_LOGIC.md`](../../docs/design/UI_BUILDER_HOST_LOGIC.md).

Why an interpreter rather than the page's own JavaScript: the editor's CSP allows
`'wasm-unsafe-eval'` and not `'unsafe-eval'`, so the page cannot evaluate a design's source, and a
design's code should not run with the page's authority anyway. Inside QuickJS a program has no
`fetch`, DOM, timers or storage. Each call runs under a memory limit, a stack limit and a time
budget, and the clock (`Date.now()`, `new Date()`, `Date()`) and `Math.random()` are set per call,
so a call is reproducible. Everything that can run the program's code, from loading it to reading
what it threw, runs under that budget, and nothing the engine relies on is a global the program
could redefine.

The archive ships it as `host-logic/engine.mjs`, imported the first time a design with host logic
opens (`HostLogicEngine.wasmJs.kt`). It is not preloaded, and it has its own size budget in
`scripts/ui-builder-web-smoke/budgets.json`.

## Rebuilding

The bundle is committed so the Gradle build needs no npm. To rebuild it after changing
`engine.src.mjs` or the pinned versions in `package.json`:

```shell
cd assets/host-logic
npm ci
npm run build
rm -rf node_modules
```

The single-file variant (`@jitl/quickjs-singlefile-browser-release-sync`) embeds the Wasm in the
module, so there is no second file to serve. `LICENSE` is that package's, which carries both
QuickJS's licence and quickjs-emscripten's (MIT).
