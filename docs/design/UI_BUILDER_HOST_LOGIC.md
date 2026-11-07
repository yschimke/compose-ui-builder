# Host logic: a design that carries its own host

Status: **proposal**, with slices 1 and 2 ([the action](#the-action), the canvas runtime)
and slice 3's engine ([On the server](#on-the-server)) implemented. It continues
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

- `action` is `{ name: string, value?: string | number }`: the host action's name and its one
  value, which is exactly what Remote Compose's named `hostAction` carries (see
  [The action](#the-action)). A host that needs two arguments names two actions.
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
                  { "type": "hostAction", "name": "calc.key", "value": "1" } ] } }
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
  const k = action.value;
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
each `calc.key` with its value, the private state after it, and the values pushed.

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

1. The runtime is a JS sandbox, QuickJS. It runs in the browser as Wasm (on the editor's main
   thread, budgeted; see [On the canvas](#on-the-canvas)) and on the server compiled to JVM
   bytecode (quickjs4j; see [On the server](#on-the-server)). They are two builds of one
   interpreter, so the canvas and the simulator agree about arithmetic, string formatting and
   iteration order, and one fixture of answers holds them to it.
2. `language: "javascript"` ships first. TypeScript's type syntax comes with the server compile,
   which strips types, bundles into one module, checks that `init` and `onAction` are exported,
   and checks every literal host-input name against the design's declarations.
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
  "source": "export function init(env) { … }"
}
```

- `hostLogic` is an optional document field. Its absence keeps today's behaviour exactly: host
  actions are logged and nothing answers.
- `UiBuilderDocument` gains it with `@EncodeDefault(NEVER)`, so every existing design and fixture
  serialises byte-for-byte as before. On the wire it is `DesignDocumentV1.hostLogic`
  (`HostLogicV1`, compose-preview-contracts#218), edited by `setHostLogic` and `removeHostLogic`:
  one revision, one undo step, a stale-write conflict when two authors edit it at once.
- Limits are the engine's, not the document's: 8 MiB of memory, a 512 KiB stack and 200 ms per call
  (`assets/host-logic/engine.src.mjs`). A stored limit would let a design raise its own.
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

A `HostLogicController` sits between the canvas and an engine:

```text
CanvasHostAction (a node's hostAction fired)
    -> HostLogicController.onAction(name, value)    (QuickJS, budgeted)
    -> HostLogic.step: values checked against the design's host inputs
    -> canvas state: host inputs drawn from the values (CanvasDocumentHost.hostValues)
    -> "Sent to the host" log; status, private state and Reset host in Screen › Host logic
```

- Host inputs stay read-only **to the design**. The runtime is the only writer, which is exactly
  the contract a real host has. The export's refusal of an action that writes a host input is
  unchanged.
- A **Reset host** control re-runs `init`. Switching example dataset resets too.
- A budget overrun, an exception, or a value of the wrong type stops the runtime, shows the error
  where the event log is, and leaves the last good values on screen. It never takes the editor down.
- The engine is behind `HostLogicEngine` in `:ui-builder` commonMain, and everything that is not
  evaluation — what a call is handed, what of its answer is drawn — is `HostLogic` in
  `:ui-builder-export`, so engines cannot disagree about it. wasmJs implements the engine with
  QuickJS (`assets/host-logic`), imported the first time a design with host logic opens. The JVM
  hosts (desktop, IntelliJ) have none yet and say so in the panel; the server's JVM engine
  ([On the server](#on-the-server)) is what they would use.
- QuickJS runs on the main thread rather than in a Worker. Every call is bounded by the interrupt
  budget, so a runaway loop costs at most 200 ms and is reported, and a call is synchronous, so
  the values are drawn in the same frame as the press. The CSP is why it is an interpreter at all:
  the editor's page allows `'wasm-unsafe-eval'` and not `'unsafe-eval'`, so the page cannot
  evaluate a design's source, and inside QuickJS the program has no `fetch`, DOM, timers or
  storage. Every way of reading the time (`Date.now()`, `new Date()`, `Date()`) reads the design's
  fixed time and `Math.random()` is seeded per call, so a run is reproducible; and everything that
  can run the program's code, down to reading what it threw, runs under the budget.
- `language: "javascript"` is JavaScript only for now: the "compile" is QuickJS parsing the module,
  and its syntax error is what the panel shows. TypeScript's type syntax needs a stripping step,
  which belongs with the server compile in slice 3.

### On the server

The same reducer runs headless for `simulate_action` (#750 item 10): an agent sends a sequence of
actions and gets back the private state and pushed values after each, plus renders if asked. That
turns "the calculator works" into evidence an agent can produce without a browser, and into a
scenario fixture a test can assert.

- `HostLogicSimulator` in `:ui-builder-runtime` is the server's whole engine: `check(document)` for
  `check_design` (the stored program, loading, both exports, and `init`'s answer) and the digest
  the compile route serves it by, and `simulate(document, actions)`. A run is driven by the same
  `HostLogicRun` (`:ui-builder-export`) the canvas's controller drives, so the calls, their order,
  their seeds and the checks on every answer are one piece of code.
- The engine is QuickJS compiled ahead of time to JVM bytecode
  ([quickjs4j](https://github.com/roastedroot/quickjs4j), Javy's build): pure Java, no native code,
  no Wasm runtime to start, no `fetch`, file system or timers. Its prelude is the browser engine's,
  so the clock and the seeded `Math.random()` are the same.
- `docs/design/fixtures/ui-builder/host-logic-parity-v1.json` is the set of answers both engines
  are held to (`HostLogicParityTest` on the JVM, `host-logic-engine.test.mjs` in the browser): a
  reducer, every way of reading the time, the seeded sequence, how each kind of throw is worded, the
  load refusals and the runaway loop. The two builds word a few interpreter errors differently
  (`'fetch' is not defined`), so an error answer is a substring both contain.
- Limits: 16 MiB of linear memory (the browser's 8 MiB heap plus the interpreter) and 1 s per call,
  generous beside the browser's 200 ms because a cold JVM interprets the engine before it compiles
  it. A call that overruns is interrupted, which leaves that interpreter unusable, so the program is
  loaded again on a fresh one for its next call; a reducer keeps nothing but the state it hands
  back, so nothing is lost. A simulation stops at an overrun rather than paying the budget per
  action, and sends at most 200 actions.
- The program's exports are read from its module scope, where `export function init` and
  `export const onAction = …` declare them, and each is then checked to be exported, not only
  declared (a module may not export one name twice, so the program with `export { init };` appended
  compiles only when it never exported `init`). The browser reads the module namespace
  instead, so the forms that rename (`export { reduce as onAction }`, `export default`) work there
  and not here; write the two exports as declarations.

### In the export

Host logic is not part of the `.rc` document; it is part of the **host interface** that #750's
manifest describes. The export bundle gains:

- `host/logic.mjs` (JavaScript) or `host/HostLogic.kt` (Kotlin), the reducer as written;
- in the manifest: the host inputs it writes, the host actions it handles, and its digest;
- for Kotlin, a generated reference host adapter: receive `HostAction`, call `onAction`, push each
  value by name, persist `state` between invocations.

The completeness review's rule holds: a reference host is a reference host. An export with host
logic is "interactive with its stand-in host", not "has a production backend".

## The action

Host logic answers host actions, so the first slice is the action itself (#750 item 3), and it is
useful without any host logic: it is what every fed design needs.

```json
{ "type": "hostAction", "name": "calc.key", "value": "7" }
```

Its shape is Remote Compose's own. The released `remote-creation-compose` has `hostAction(name)` and
`hostAction(name, value)` for a `RemoteString`, `RemoteInt` or `RemoteFloat` value, and the player
delivers a named action with that one value. So the design action has a `name` and at most one
`value` — text, a whole number that fits an `Int`, or a finite decimal — rather than an argument
map the wire cannot carry. A flag is refused (there is no public Boolean overload; send `1` or
`0`), and so, for now, is a formula or a state read. `HostActions` in `:ui-builder-export` is the
one reading of it every lane shares.

| Lane | What a host action becomes |
| --- | --- |
| Remote Kotlin (`remote-m3`) | `hostAction("calc.key".rs, "7".rs)`, in order with any state writes in one `combinedAction` |
| Remote authoring JSON | Refused by name: that vocabulary has no host-action operation |
| Compose screen (`m3`) | `onHostAction("calc.key", "7")`, a callback parameter the screen's caller implements, as `onNavigatePage` is |
| Wear Compose (`wear-m3`) | Refused by name: a Wear screen has no host feeding it |
| Canvas | Reported, in order with the writes around it, as a `CanvasHostAction`; the editor shows what was sent and passes it to an embedding host through `onHostAction`. Nothing in the design changes |

A host action is also a protocol action, `HostActionV1` in `ui-builder-protocol` (3.40.0,
compose-preview-contracts#216), so an edit carries it and a server stores it; the server's reducer
refuses one `HostActions` refuses, so a design never holds a host action its exports would each
refuse. The behaviour inspector offers **Host action** on Remote Compose and mobile catalogs —
a name, and a value read the way JSON reads it (`7` is a number, `"7"` is text, blank is none) —
and not on Wear, which has no host.

## Slices

1. **`hostAction` action** (#750 item 3): document shape, Remote Compose export, Compose export
   (a callback parameter), canvas dispatch and event log, `HostActionV1` in `ui-builder-protocol`
   (*compose-preview-contracts*), and the behaviour inspector entry. *Done.*
2. **`hostLogic` field and canvas runtime** (JavaScript): `HostLogicV1` and its mutations in
   `ui-builder-protocol` (*compose-preview-contracts*), the document field and editor operations,
   the server's reducer, `HostLogicEngine` with the QuickJS engine, the canvas drawing the values,
   and the Screen › Host logic panel with Reset host and the error surface. *This repo and
   contracts.*
3. **Server compile and simulate**: `HostLogicSimulator` and its JVM engine in
   `:ui-builder-runtime` (*this repo*), then the compile route keyed by digest, diagnostics into
   `check_design`, and `simulate_action` (*compose-preview-server*). TypeScript's type stripping
   is still to come.
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
