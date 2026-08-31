# UI builder protocol client boundary

The UI-builder module contains a common Kotlin v1 protocol client that can be compiled for JVM and
Wasm without depending on `:server` or Ktor. Networking is injected through small HTTP and
WebSocket transport interfaces. `wasmJsMain` supplies minimal same-origin browser implementations
using `fetch` and `WebSocket`, without changing request correlation or cursor behavior.

The HTTP side strictly encodes `HttpRequestEnvelopeV1`, supplies deterministic per-client request
IDs, copies the configured actor into the transport envelope, rejects mismatched nested requester
actors before sending, and requires the response request ID to correlate. The released
`snapshotRequired` service error is a distinct client result.

The update side strictly decodes `DesignUpdateEnvelopeV1`. Reconnect uses the last delivered durable
sequence as an exclusive cursor. Snapshots and deltas advance it; presence and operation outcomes do
not. Exact delta replays are ignored, overlapping deltas are trimmed to unseen operations, and a gap
emits one local snapshot-required signal until a replacement snapshot arrives.

The browser HTTP transport uses same-origin credentials. The WebSocket transport resolves the
server endpoint template `/api/ui-builder/v1/designs/{designId}/updates`, converts relative HTTP(S)
endpoints to WS(S), and sends the optional exclusive `afterSequence` as a query parameter. Sequence
values stay strings across the JavaScript boundary so they are not rounded.

This slice deliberately does not add HTTP routes, invent a WebSocket subscription wire DTO, bind the
production editor, implement token/header authentication, or choose browser retry/backoff, socket
error reporting, and backpressure policy. The existing host routes own authentication and bounded
server-side WebSocket delivery; a later editor integration must choose the browser lifecycle policy.
