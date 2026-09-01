# UI builder live browser session

The `/ui-builder/` Wasm application keeps its fixture-backed editor as the default. A shared,
persistent session is explicit:

```text
/ui-builder/?session=live&designId=jetcaster-discover&actor=operator&clientId=browser-a&token=…
```

On a fresh server, add `create=1`. The browser first attempts `OpenDesign`; only a `notFound`
response enables the explicit create path, which seeds the requested ID from the current Jetcaster
fixture at revision zero. An existing design is never overwritten.

Add `template=blank` to seed a minimal scaffold with an empty content box instead. See the
[getting-started guide](../UI_BUILDER_GETTING_STARTED.md) for the complete from-scratch workflow.

The browser opens the design through the released v1 HTTP envelope, renders the authoritative
snapshot, and subscribes to `/api/ui-builder/v1/designs/{designId}/updates`. Editor batches, undo,
and redo retain the actor/client identity from the URL and use the currently rendered authoritative
revision as `baseRevision`. Every accepted outcome or remote delta refreshes the authoritative
snapshot. A rejected stale write therefore rolls back the optimistic local reducer state rather
than leaving a browser-only document behind.

Operation IDs combine the configured logical `clientId` with a per-page nonce. Snapshot resets and
browser reloads therefore cannot accidentally replay an earlier operation ID.

The top-level `token` query value is the existing server operator or agent-grant credential. The
browser carries it to the same-origin request endpoint and WebSocket upgrade using the server's
canonical `token` query. It is never placed in editor state, status text, protocol envelopes, or
transport diagnostics. Optional `endpoint` and `updatesEndpoint` values may override the defaults,
but browser transports reject cross-origin endpoints before sending a request. The transport still
authenticates the actor independently: the URL `actor` must match the identity derived by the
server.

The toolbar reports connecting, saving, rejected, snapshot-recovery, and live sequence states. Its
Reconnect action reopens the WebSocket from the client's last exclusive durable cursor and then
refreshes a snapshot. While connected, the browser sends a bounded ten-second presence heartbeat
containing its authenticated actor/client identity and current selection. Presence is rendered as
collaborator avatars, layer dots, and a sibling canvas selection outline in editor chrome only. It
is excluded from the clean composition, inspection semantics, SVG, PNG, generated Compose, durable
history, revision, and sequence. Both browser and service expire a missing heartbeat after thirty
seconds; reconnecting refreshes the roster from a snapshot before resuming heartbeats.

The real two-browser evidence harness needs a token-gated server started with agent grants and the
two independent UI-builder capabilities:

```shell
SERVE_URL=http://127.0.0.1:8727 SERVE_TOKEN=… npm --prefix preview-harness run harness:ui-builder-presence
```

The server must include `--agent-grants --agent-grant-scopes live
--agent-grant-capabilities ui-builder-read,ui-builder-write`. The harness asks for and approves two
distinct short-lived grants through the real device flow, grants both authenticated actors access
to one fresh design, and captures each Chromium context observing the other's selection.

Fixture modes such as `?mode=interactive-editor`, `interactive-editor-clean`, and the visual
benchmark modes do not contact the design service and remain deterministic offline surfaces.
