# UI builder live browser session

The `/ui-builder/` Wasm application opens the live New design chooser when neither `mode` nor
`session` is specified. One shared, persistent design is named in the path:

```text
/ui-builder/m3-catalog/jetcaster-discover
```

That is the canonical URL for a design, and the only thing it says is which catalog and which
design. The identity and transport values — `actor`, `clientId`, `displayName`, `color`, `token`,
`endpoint`, `updatesEndpoint` — remain a query, because they configure *who* is editing rather
than *what*. The browser reads the design out of `location.pathname`; the server serves the app
shell for a catalog-scoped segment that names no file, and redirects the trailing-slash spelling
away, because the shell resolves `uiBuilder.mjs` relative to the document.

A design named in the path is opened-or-created: the browser first attempts `OpenDesign`, and only
a `notFound` response enables the explicit create path, which seeds the requested ID from the
current Jetcaster fixture at revision zero. An existing design is never overwritten, so this is
safe by construction; `create=0` still opts out of creating at all. `template=blank` seeds a
minimal scaffold with an empty content box instead. See the
[getting-started guide](../UI_BUILDER_GETTING_STARTED.md) for the complete from-scratch workflow.

`template` and `state` describe how a design that does not exist yet is seeded, so they say nothing
once it does. As soon as the design is open the browser rewrites the URL to the canonical path form
with `history.replaceState`, dropping them along with `session`, `create` and `designId` — one
design, one URL, whichever spelling opened it.

The older query form is still honoured, so existing bookmarks and automation keep working:

```text
/ui-builder/?session=live&create=1&designId=jetcaster-discover&actor=operator&clientId=browser-a
```

The path wins where both name a design, and such a URL is rewritten to the path form on open.

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
