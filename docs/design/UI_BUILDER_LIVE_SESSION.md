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

**A design named without its catalog redirects to the canonical URL.** `/ui-builder/<designId>`
answers `302` to `/ui-builder/<catalogPin.systemId>/<designId>`. The catalog is a segment the *API*
never asks for — `PUT /api/ui-builder/v1/designs/{designId}` is catalog-free, because the server
reads the pin out of the stored document, and `ui_builder_create_design` takes an id and returns no
URL — so anything holding only an id builds the shorter link, and it used to answer a bare `404`
that reads like a deleted design
([#509](https://github.com/yschimke/compose-preview-server/issues/509)).

The redirect is **not** an existence oracle, and that is what makes it safe to have. Designs are
private to their owner and collaborators, and `cheeky-raccoon`-style ids are guessable, so a
redirect that fired for any id that exists would tell an unauthenticated stranger both that the id
is taken and which catalog it pins. So the lookup is made as the caller, through the same
`GetSnapshot` the design API would answer: a caller who cannot open the design gets the `404` they
got before, and an id that names nothing is a `404` for everyone. That last part also keeps the
property the catalog-scoped branch protects — an asset that is simply missing must 404 rather than
silently render the app shell.

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

## Naming a revision, a node or a thread

The canonical URL says which design and nothing else, which was enough while a design was something
one person had open and not enough the moment two people and an agent were talking about it. A
designer could not paste "look at this thread" into a chat, and an agent told "the button on the
checkout design" had to search for it. Three optional selectors say *what in the design* a link
means, and a URL carrying none of them opens exactly what it opened before.

```text
/ui-builder/m3-catalog/jetcaster-discover?revision=41&node=discover-grid#thread=t-4f2a
```

**`?revision=<n>`** opens the design at one committed revision, read-only, with a banner naming it
and a one-click **Go to latest**. The snapshot is fetched with the `revision` the design API has
always taken, and the page holds it: no socket, no presence heartbeat, and no edit. Editing is
refused at the one place every change passes through — the reducer bumps an operation sequence for
each command it forms and for nothing else, so selecting, filtering, opening a panel and marking up
the reference all still work while every change to the *document* is dropped before it reaches the
canvas. A revision the service will not answer for — trimmed out of the retained window, or one this
design never reached — is a **stale link, not a failed page**: the editor opens the living design and
the banner says the revision is unavailable. That is the same instinct as the catalog-less redirect
above; answer the question the reader actually has. `?revision=0` is a revision like any other: a
design is created at 0 and that snapshot is retained, so the link names the state a template started
in. Only a negative or non-numeric value is no revision at all.

A pinned page is historical all the way down, not merely un-editable, and several things follow that
are easy to leave out. The catalog installed is the one the **pinned** snapshot resolved against, not
the one the catalog list offers today, so a design whose catalog pin moved between revisions is drawn
with the capabilities it actually had. Presence is dropped: the page holds no socket, and collaborator
avatars and selection outlines from the live design would be pointing at nodes of a revision this
canvas is not showing. And every lane that renders on request is pinned to the same revision — the
export routes already took one, and the native-preview route now does too. That last one is the
only server change this needed, and it is load-bearing rather than tidy: on a catalog whose Wasm
canvas is only a stand-in (`wear-m3` draws Material 3 lookalikes, because a Wasm build cannot link
`androidx.wear.compose:compose-material3` at all) the native render is the *only* faithful picture
the page has. Withholding it there did not leave the reader with a rough drawing of the right
document, it left them with an accurate drawing of the wrong component library, under a banner
naming a revision.
**Screen → Snapshot design** is pinned too, and is the lane worth naming separately because it does
not merely display what it renders, it *keeps* it: an unpinned snapshot would lay the head over the
historical canvas and then persist it as that design's reference. **Reconnect** is the one thing
genuinely withheld, and for a different reason: there is no session to resume.

**`?node=<nodeId>`** selects that node as the design opens — the canvas outline and the Layers row —
and opens the Properties panel on it. An id this design does not have opens the design with a small
notice rather than an error, for the same reason: the link is stale and the design is not. A
`#thread=` naming no conversation on the board is answered the same way, once the board has arrived
— it comes over its own socket well after the design, so asking earlier would call every thread link
stale for the first moments of every page — and the fragment is then taken out of the address bar
rather than left claiming a thread that is not there. On a
viewport too narrow for the docked inspector the same selector opens the compact Properties sheet,
which is the one dock that hosts every inspector mode, Talk included — a selector that set the state
and left the phone showing nothing would be the panel half of the feature missing.

**`#thread=<threadId>`** opens the Talk panel scrolled to one conversation, and combines with
`?node=` where the thread is pinned to a layer, so "the thread about this button, beside the button"
is one URL. The panel follows the fragment rather than only reading it once at startup: moving
between two thread links inside an open design is a *same-document* navigation, so the browser never
reloads and the Wasm app is never re-entered — an editor that read the fragment only on mount would
sit on the previous conversation while the address bar named the new one, and Back would do the
same. A thread the URL names is shown even when it is resolved, without switching **Show
resolved** on for everything else: a permalink to a settled conversation is exactly the link somebody
sends to explain a decision. The panel scrolls to it **once**: a link says where to start reading,
and a page that kept pulling itself back would fight whoever read on.

**The thread is a fragment on purpose, and this is the load-bearing part.** A fragment is never sent
to the server — not in the request line, not to a proxy, not into an access log, not in a referrer
header. A thread id names a *discussion*, which is the private half of a private design, and the id
of a conversation somebody linked to should not accumulate in logs that outlive the link. The
revision and the node are properties of the document the reader is about to be served anyway, so
they cost nothing in the query. This is the same argument the redirect above makes about not being
an existence oracle, applied to the other end of the URL. Nothing on the server reads any of the
three: the app shell serves the same bytes whatever the query says, which is why adding them needed
no route change.

**They survive the canonical rewrite, and they do not survive being wrong.** The `history.replaceState`
that turns a legacy `?designId=` URL into the path form carries `revision` and `node` across and
leaves the fragment alone — a rewrite that dropped them would silently turn a link to one layer into
a link to the design. In the other direction, `?node=` is taken back out of the address bar the
moment the selection moves off it, and `#thread=` the moment the reader closes that thread or opens
another, so a URL copied later cannot point at something nobody has been looking at.

**Copy link** produces these URLs from the two places a person is standing when they want one: a
selected layer's menu, which copies the node at a revision the host has confirmed *that layer was
in*, and a comment thread's card,
which copies the thread and the layer it is pinned to. Both copy the canonical path form with no
identity or token value on it — a shared link is an address and never a credential, which is the
rule the export lane's Copy link already follows.

The revision on a layer link is two questions, and taking either alone produces a link that is
reliably wrong. It is not the revision on screen: the reducer raises that the moment an edit is
applied so the canvas can draw it, so the number describes a submission still in the queue, which
resolves to nothing or — once a collaborator claims the number first — to a document the person
copying never saw. But it is not simply the last accepted revision either, because a layer that only
exists thanks to a queued insert, duplicate or paste was not in it, and pairing the two makes a link
that opens on the missing-layer notice every time. So the host answers per node: the last revision
it confirmed *that layer was in*.

Copy link is also withheld entirely for a design the path form cannot name. The two ends of a design
URL disagree about what an id may contain: the service stores any id that is not blank, while the
editor refuses to start on a design *named in the path* unless the id matches
`[A-Za-z0-9][A-Za-z0-9._-]*` and does not end in a suffix the shell reads as a file — a design
legitimately called `screen.png` is routed as an asset request and 404s. A design created through
the protocol, MCP or the Design API with a space in its id is therefore reachable only through the
legacy `?designId=` query, and a path-form link to it would hand its recipient a 404 or a page that
refuses to initialise. Emitting a different *kind* of URL for those designs would be a second
address form to keep working; the one thing worse than no Copy link is one that copies a broken
address.

Where it has no revision to name, **Copy link is withheld** rather than degraded. Dropping the revision does
not rescue such a link — an unpinned link to a layer that is not committed yet opens the living
design, whose first snapshot on the recipient's side has no such node either, so the editor falls
back to the root and never reselects it when the edit lands. There is no address that is right, so
the row is absent for the moment the queue takes.

The grammar is one function,
[`parseDesignUrlSelectors`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/DesignUrlSelectors.kt)
and its `designUrlPath` twin, in common code with a test rather than in a `@JsFun` in the browser
entry point: a link this editor writes has to be a link this editor reads back.

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

## The same session, from an agent

Every accepted write reaches every subscriber on that design, whatever transport made it: an MCP
`ui_builder_apply` and a browser's Design API request both land in one
`PersistentUiBuilderService.apply`, which commits and then fans a `ServiceDeltaV1` out to every
mailbox. The browser therefore sees an agent's edit without doing anything — the delta arrives on
the socket it already holds, and the protocol client does not filter by `clientId`.

The reverse needs a tool, because MCP's own server-to-client notifications are not available here:
`/mcp` is a stateless JSON-RPC endpoint, `GET /mcp` — the Streamable-HTTP listening stream a
notification travels on — answers `405`, and `initialize` advertises
`resources: {"subscribe": false}`. So `ui_builder_await_design` holds the same
`UiBuilderServicePort.subscribe` the socket is built on for the length of one call, and replies with
the released `DesignUpdateEnvelopeV1` — the identical frame the socket delivers. Quote the
`lastSequence` you last saw as `afterSequence`; a cursor inside the retained window is answered with
the operations after it, and one that is too far behind or ahead of the design is answered with a
whole snapshot, which is the service's own `catchUp` rule rather than a second one.

Presence does not wake it, for the reason presence is excluded from the composition, the revision
and the sequence above: somebody looking at a design has not changed it.

Fixture modes such as `?mode=interactive-editor`, `interactive-editor-clean`, and the visual
benchmark modes do not contact the design service and remain deterministic offline surfaces.
