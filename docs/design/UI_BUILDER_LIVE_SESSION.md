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

Opening a design is a `GET`, and a `GET` never creates one. A design that does not exist is
reported as missing, not brought into existence by somebody following a link. Creating is its own
request, in two shapes.

**The New design form: `POST /ui-builder/<catalog>`.** Fields are `designId`, `template` and an
optional `state`; the answer is `303 See Other` to the design's permalink. An ordinary HTML form,
which matters: the browser submits it and follows the redirect itself, so the URL left in the
address bar and in history is the design's, and reloading it re-opens rather than re-creates. 303
rather than 302 because the method that follows must be `GET`. The server seeds the document —
[`UiBuilderNewDesignSeed`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderNewDesignSeed.kt),
shared with the browser so a template means one thing on both sides — pinned to the catalog
revision this server actually serves. It is refused unless the request is same-origin
(`Sec-Fetch-Site`, else `Origin` against `Host`): a form `POST` is the one shape a hostile page can
aim at this server with the reader's credentials attached.

**The design resource: `PUT /api/ui-builder/v1/designs/{designId}`**, with `If-None-Match: *` and a
`DesignDocumentV1` body. For a caller that has a document rather than an intent. The precondition
is required, not assumed: this route creates and never replaces, so a `PUT` without it is answered
`428 Precondition Required` rather than being quietly treated as a create. `201` carries `Location`
— the editor permalink, because the useful answer to "I made a design" is where a person can open
it — and a design that already exists fails its precondition with `412`.

Neither route overwrites. The form answers an id that already exists with the same `303` it would
have given a fresh one, which is what "open or create" meant when this was a navigation, minus the
mutation on a `GET`.

The older query form still opens a design, so existing bookmarks keep working:

```text
/ui-builder/?session=live&designId=jetcaster-discover&actor=operator&clientId=browser-a
```

The path wins where both name a design, and such a URL is rewritten to the path form —
`history.replaceState`, no round trip — as soon as the design is open. `create=1` in such a URL no
longer creates anything; that is what the two routes above are for.

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
