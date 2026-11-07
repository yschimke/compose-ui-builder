# Host logic: a design that carries its own host

Status: **proposal**. Nothing here is implemented yet. It continues
[#750](https://github.com/yschimke/compose-ui-builder/issues/750) (host-bound Remote Compose
designs) and builds on [host inputs](UI_BUILDER_REMOTE_COMPOSE.md#host-inputs).

## The problem

A `remote-m3` design such as [`cranky-bandit`](https://ui.coo.ee/cranky-bandit), a 216×124dp Wear
widget laid out as a calculator, has a display (`display`, showing `106500`) and a grid of keys
(`key-1-1` … ). A calculator's behaviour does not belong in the document:

- Remote Compose document-local state can count and toggle, but it cannot hold an accumulator, a
  pending operator and a "start a new number" flag and fold them into a display string. That is
  program logic.
- On a real device, a widget's document is **fed**. The widget host owns the state, receives the
  document's actions, and pushes new values by name. That is the split #750 describes, and the
  calculator is a small, complete instance of it.

Today the builder can say half of this. A state variable with `"persistence": "host"` exports as a
named User-domain value the host pushes, and the canvas maps a player's `HostNamedAction` to an
event binding of the same name (`RemoteComposeCanvas.bindingName`). What is missing is the host
itself: nothing on the canvas answers a host action, so a host-bound design cannot be tried, an
agent cannot simulate it, and no export says what the host is supposed to do.

**Host logic** is that missing piece: a small program, stored with the design, that receives host
actions and responds by setting host values. It is compiled on the server and runs in a sandbox
wherever the design is played without its real host: the canvas, the server's headless simulator,
and an exported reference host.

## The model

Host logic is a pure, deterministic reducer over a JSON-serialisable private state:

```text
init(env)                     -> { state, values }
onAction(state, action, env)  -> { state, values }
```

- `action` is `{ name: string, args: object }`: the host action's name and its metadata, which is
  what Remote Compose's `HostAction` carries.
- `state` is the host's **private** state. It is never in the document and never pushed. For the
  calculator: the accumulator, the pending operator, whether the next digit starts a new number.
- `values` is a partial map from **host input names** to new values. Only state variables declared
  `"persistence": "host"` may appear in it, with their declared `valueType`; anything else is a
  diagnostic, not a silent write.
- `env` is read-only: the host inputs' current values (`env.values`), the design's `environment`
  (locale, `fixedTime`, theme) and the active example dataset (#750 item 2). There is no clock other than `env.now`, no randomness other than a
  seeded `env.random()`, and no network: `environment.networkAccess` is `false` for this design and
  host logic inherits it.

Purity is the point, not a restriction to apologise for. It is what makes a host action replayable
on the canvas, simulatable by an agent (`simulate_action`, #750 item 10), checkable in a scenario
test, and identical in the three places it runs. A host that needs I/O (Home Assistant calling a
light service) is a *real* host; host logic is its stand-in, or the pure core a real host wraps.

## Worked example: `cranky-bandit`

The document gains one host input, click bindings on the keys, and a `hostLogic` block.

```json
"stateVariables": {
  "calc.display": { "type": "text", "valueType": "string", "initialValue": "0",
                    "nullable": false, "persistence": "host" }
},
"nodes": {
  "display":  { "properties": { "text": { "type": "state", "variable": "calc.display" } } },
  "key-1-2":  { "eventBindings": { "click": [
                  { "type": "hostAction", "name": "calc.key", "args": { "key": "1" } } ] } }
},
"hostLogic": {
  "language": "javascript",
  "source": "…below…"
}
```

```js
export function init(env) {
  return { state: { acc: 0, op: null, fresh: true }, values: { "calc.display": "0" } };
}

export function onAction(s, action, env) {
  if (action.name !== "calc.key") return { state: s, values: {} };
  const k = action.args.key;
  let { acc, op, fresh } = s;
  let shown = env.values["calc.display"];
  if (/^[0-9]$/.test(k)) {
    shown = fresh || shown === "0" ? k : (shown + k).slice(0, 9);
    fresh = false;
  } else if (k === ".") {
    if (fresh) shown = "0.";
    else if (!shown.includes(".")) shown += ".";
    fresh = false;
  } else if (k === "C") {
    return init(env);
  } else if (fresh && op !== null) {
    op = k === "=" ? null : k; // a second operator replaces the first
  } else {
    const x = Number(shown);
    acc = op === null ? x : apply(acc, op, x);
    op = k === "=" ? null : k;
    shown = String(acc);
    fresh = true;
  }
  return { state: { acc, op, fresh }, values: { "calc.display": shown } };
}

const apply = (a, op, b) =>
  op === "+" ? a + b : op === "−" ? a - b : op === "×" ? a * b : b === 0 ? NaN : a / b;
```

Clicking `1`, `+`, `2`, `=` on the canvas then shows `1`, `1`, `2`, `3`, and the event log shows
each `calc.key` with its `args`, the private state after it, and the values pushed.

## The language: JavaScript first, Kotlin compiled to the same runtime

The language question is really a question about *where the program runs*, and the answer is "in
the browser, next to the player", because the canvas is where host logic is used most and a
network round trip per key press is the wrong latency for a calculator.

| | JavaScript / TypeScript | Kotlin |
| --- | --- | --- |
| Runs in the canvas | Directly, in a sandbox | Only once compiled to JS (or run on the server, a round trip per action) |
| Server compile | Cheap: strip types, bundle, validate exports (esbuild/swc-class, milliseconds) | Seconds per compile with a warm `kotlinc-js` daemon; a stdlib to ship |
| Sandbox | QuickJS compiled to Wasm: memory cap, instruction budget, no ambient APIs | Same, once it is JS; a JVM sandbox is a separate process per design |
| Fit with the export | A JS engine on the device, or a port | Native: the reducer can be copied into a generated Kotlin host |
| Agents writing it | Very good | Very good |

So: **one runtime, two source languages**.

1. The runtime is a JS sandbox, QuickJS-on-Wasm. The same engine runs in the browser (a Web Worker
   beside the Wasm editor) and on the server (a JVM binding, or the same Wasm under a Wasm runtime),
   so the canvas and the simulator cannot disagree about arithmetic, string formatting or
   iteration order.
2. `language: "javascript"` (TypeScript accepted) ships first. The server's "compile" is
   type-stripping, bundling into one module, checking that `init` and `onAction` are exported, and
   checking every literal host-input name against the design's declarations.
3. `language: "kotlin"` follows, against a tiny published API
   (`fun init(env: HostEnv): HostStep`, `fun onAction(state: JsonObject, action: HostActionEvent,
   env: HostEnv): HostStep`). The server compiles it with Kotlin/JS into a module for the same
   sandbox, so nothing on the canvas changes. Its payoff is the export: the Kotlin source is the
   reducer a generated Android host calls, with no JS engine on the device.

A Kotlin-first design (compile to JVM, run it on the server, round-trip every action over the live
session) was considered and is not recommended for the first slice: it puts the preview server's
latency and availability inside every click, it needs a per-design JVM sandbox on a shared host,
and an offline or desktop session (`OfflineUiBuilderSession`) would have no host at all.

## Where it is stored, and what is compiled

The design stores **source**. The compiled module is a **lookup**, keyed by the source's digest,
for the same reason an inline Remote Compose capture is
([What the capture costs](UI_BUILDER_REMOTE_COMPOSE.md#what-the-capture-costs)): bytes compiled from
revision 3 describe revision 3, and a compiled field written into the document would persist,
reopen and quietly run the wrong program.

```json
"hostLogic": {
  "language": "javascript",
  "source": "export function init(env) { … }",
  "limits": { "instructions": 1000000, "memoryBytes": 8388608 }
}
```

- `hostLogic` is an optional document field. Its absence keeps today's behaviour exactly: host
  actions are logged and nothing answers.
- `UiBuilderDocument` gains it with `@EncodeDefault(NEVER)`, so every existing design and fixture
  serialises byte-for-byte as before. It is a `:ui-builder-runtime` / `:ui-builder-export` wire
  change, so the ABI dump moves with it, and an editor operation (`setHostLogic`, one revision, one
  undo step) edits it rather than a free-form patch.
- The source is a design asset in everything but name: bounded in size (64KiB), part of the
  revision, diffed in revision history, carried by fork.

The server answers compile by digest:

```text
POST /api/ui-builder/v1/designs/{id}/host-logic:compile   { revision }
  -> { digest, module: "/api/…/host-logic/{digest}.mjs", diagnostics: [ … ] }
```

Diagnostics use the guideline shape (node, field, message), so they appear in the canvas's issues
list and in `check_design` beside layout findings.

## Running it

### On the canvas

A `HostLogicRuntime` sits between the player and the canvas's state:

```text
RcPlayerEvent.HostNamedAction / HostActionMetadata
    -> HostLogicRuntime.onAction(name, args)        (Worker, budgeted)
    -> values: { "calc.display": "3" }
    -> canvas state: host inputs updated           (the player's named-value holder)
    -> event log: action, args, private state, values, time taken
```

- Host inputs stay read-only **to the design**. The runtime is the only writer, which is exactly
  the contract a real host has. The export's refusal of an action that writes a host input is
  unchanged.
- A **Reset host** control re-runs `init`. Switching example dataset resets too.
- A budget overrun, an exception, or a value of the wrong type stops the runtime, shows the error
  where the event log is, and leaves the last good values on screen. It never takes the editor down.
- The runtime is behind an interface in `:ui-builder` commonMain. wasmJs implements it with a
  Worker; JVM (desktop, IntelliJ, tests) with the same QuickJS Wasm under a JVM Wasm runtime, so
  the desktop app gets host logic without a server.

### On the server

The same module runs headless for `simulate_action` (#750 item 10): an agent sends a sequence of
actions and gets back the private state and pushed values after each, plus renders if asked. That
turns "the calculator works" into evidence an agent can produce without a browser, and into a
scenario fixture a test can assert.

### In the export

Host logic is not part of the `.rc` document; it is part of the **host interface** that #750's
manifest describes. The export bundle gains:

- `host/logic.mjs` (JavaScript) or `host/HostLogic.kt` (Kotlin), the reducer as written;
- in the manifest: the host inputs it writes, the host actions it handles, and its digest;
- for Kotlin, a generated reference host adapter: receive `HostAction`, call `onAction`, push each
  value by name, persist `state` between invocations.

The completeness review's rule holds: a reference host is a reference host. An export with host
logic is "interactive with its stand-in host", not "has a production backend".

## Prerequisite: an action that *is* a host action

The worked example's `{ "type": "hostAction", "name": …, "args": … }` does not exist yet.
`RemoteContentEmitter.actionExpression` only knows actions that write a variable (`set`, `toggle`,
`increment`, …) and refuses anything without one. #750 item 3 adds it: export as Remote Compose
`HostAction` with the name and `args` as metadata, play on the canvas through the existing
`bindingName` path, and log it. That is useful on its own — it is what any fed design needs — and
host logic cannot be built before it.

## Slices

1. **`hostAction` action** (#750 item 3): document shape, Remote Compose export, Compose export
   (a callback parameter), canvas dispatch and event log, behaviour inspector entry. *This repo.*
2. **`hostLogic` field and canvas runtime** (JavaScript): document field and editor operation,
   `HostLogicRuntime` interface, wasmJs Worker + QuickJS implementation, Reset host, error surface.
   Compilation is local type-stripping in this slice, so it can be tried without a server change.
   *This repo.*
3. **Server compile and simulate**: the compile route keyed by digest, diagnostics into
   `check_design`, `simulate_action`. *compose-preview-server*, against the slice 2 wire shape.
4. **Export**: `host/logic.mjs` and the manifest entries. *This repo.*
5. **Kotlin**: the published `HostLogic` API, server-side Kotlin/JS compile into the same runtime,
   and the Kotlin reference host adapter in the export. *Both.*

## Open questions

- **JS in the editor's process.** QuickJS-on-Wasm is the safer default; a plain `Worker` running the
  module directly is smaller and faster but trusts the browser's isolation alone, and the server
  side would then need a second engine with the risk of the two disagreeing. The proposal picks
  one engine for both.
- **Who may run whose logic.** A forked design runs its author's code in the forker's tab. In a
  Worker sandbox with no network that is low-risk, but the first open of a design with host logic
  could still ask before running it.
- **Optimistic updates** (#750 item 3's "design-local override that the host's next push clears")
  are independent of host logic and compose with it: the override shows immediately, the runtime's
  push replaces it a frame later.
- **Host lists** (#750 item 5): `values` would carry a list for a `for-each` input. The reducer
  model does not change; the value types do.
