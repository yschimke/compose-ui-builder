# UI builder designs kept in the browser

`/ui-builder/<designId>?storage=local` opens a design this browser holds, rather than one
the server holds. Everything about the editor is the same — the same canvas, the same palette, the
same inspector, the same undo — because the only thing that changed is where the design lives and
who applies the edits.

This document says how that works, and answers the question it exists to answer: **how much of the
builder keeps working with the server unreachable?**

## The short answer

The editing loop works offline. Opening a design, drawing it, dropping components, editing
properties and modifiers, moving and deleting nodes, undo, redo, the layers tree, the problems
panel, the Code pane and the Screen inspector are all computed in the page and stored in the page.

Four things still need the server, and each says so where it would otherwise be silently broken:
exported files (SVG, PNG), the native Compose render, comments, and reference pictures.

The one requirement is that this browser has opened the catalog at least once while online. See
[The catalog is the hard part](#the-catalog-is-the-hard-part).

## Why a mode rather than a fallback

A cache that silently takes over when the network drops is the wrong shape for a design tool: the
author cannot tell whether the thing they just typed is somewhere durable, and two tabs that fell
back at different moments disagree about the document without either being wrong. So this is a mode
a person chooses, named in the URL, shown in the status line, and never entered on its own.

`?storage=local` is a query rather than a path segment because it says *where the design is kept*,
not *which design* — the same distinction that keeps `actor` and `token` in the query while the
catalog and design id are the path
([`UI_BUILDER_LIVE_SESSION.md`](UI_BUILDER_LIVE_SESSION.md)). The server never reads it: it serves
the same app shell for the same catalog-scoped path either way, and the page decides.

## The shape: one editor, two services

The editor already talks to the design service through one seam —
`UiBuilderProtocolHttpClient` over a `UiBuilderHttpTransport`. The local mode replaces that
transport, and nothing else.

```text
UiBuilderEditor
  └─ EditorSubmission ──► drain loop ──► UiBuilderProtocolHttpClient
                                            ├─ BrowserUiBuilderHttpTransport ──► server
                                            └─ LocalUiBuilderHttpTransport ──► LocalUiBuilderService
                                                                                 └─ CollaborationReducer
```

`LocalUiBuilderService` answers the released v1 requests — `openDesign`, `getSnapshot`,
`applyOperation`, `listCatalogs`, `updatePresence` — from designs in this browser's storage
(IndexedDB, or `localStorage` where there is none; see [Where the bytes live](#where-the-bytes-live)), and it applies
them with **the same `CollaborationReducer` the server runs**. A design edited offline is not edited
by a simpler set of rules; it is edited by the same rules with the round trip removed.

That reuse is the whole design decision. The alternative — wiring the editor straight to the reducer
in a second host composable — would have meant a second implementation of the ordering rules the
live session already gets right: which snapshot may be displayed while the operator's own edits are
still queued, which revision the next command claims as its base, how a burst of twenty edits avoids
racing its own answers ([`UiBuilderLiveSessionSync`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/client/UiBuilderLiveSessionSync.kt)).
Those rules are subtle, they were expensive to get right, and there is no version of them that is
correct for the server and wrong for the page.

The cost of the reuse is the inverse bridge:
[`LocalProtocolSubmissions.kt`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/local/LocalProtocolSubmissions.kt)
turns the wire spelling back into the reducer's own commands. It is deliberately partial — the wire
carries mutations the editor cannot author (state variables, event bindings, a catalog upgrade) and
those are refused by name rather than dropped, so a mutation the editor learns to send gains a case
here in the same change. `LocalProtocolSubmissionsTest` asserts the round trip for everything the
editor does send.

## What is stored, and what a reload replays

A design is stored as a **seed document plus the commands accepted since**, under one key per
design (`ui-builder.local.design.<designId>`) — the same key and the same text in IndexedDB as in
`localStorage`, so moving between them did not change the record:

```json
{
  "schema": "compose-ui-builder-local-design/v1",
  "designId": "cheeky-raccoon",
  "catalogSystemId": "m3-catalog",
  "seed": { "…": "the document at the revision the log replays from" },
  "seedSequence": 0,
  "log": [ { "type": "batch", "command": { "…": "…" } } ],
  "updatedAtEpochMillis": 1700000000000,
  "origin": {
    "server": "https://preview.coo.ee",
    "designId": "cheeky-raccoon",
    "revision": 41,
    "sequence": 512,
    "documentDigest": "…",
    "takenAtEpochMillis": 1700000000000
  }
}
```

`origin` is the fork point, and it is absent for a design created here — which is a fact about the
design rather than a gap in the record. It exists only at the moment of the checkout and cannot be
recovered afterwards, which is why it is written then and never rewritten.

A log rather than only the current document, because **undo is not a property of a document**. The
reducer's compensation history is what makes "undo the thing I just did" mean anything, and it
rebuilds that history exactly by being handed the same commands in the same order. So opening a
locally stored design is a replay, and the undo stack survives a reload — which is the whole
difference between this and writing `document.json` into a key.

One key per design rather than one key holding all of them: neither store has a partial write of a
value, so a single key would re-encode every design in the browser on each keystroke-sized edit, and
one design over the quota would take all of them down with it.

A command whose replay the reducer refuses — a record edited by hand, a log written by a builder
whose reducer differed — stops the replay. The design opens at the last revision that did apply and
the refusal is reported, rather than the design becoming unopenable.

### Compaction, and the history it costs

A design's record has a byte budget (1 MB), and past it the current document becomes the new seed
and the log is dropped. The same thing happens, budget or not, when the browser refuses a write
outright: compact, and try once more. In `localStorage` the budget is what keeps several designs
inside an origin's five megabytes; in IndexedDB, whose quota is a share of the disk, it is what
keeps a reload's replay quick.

What that costs is undo history older than the compaction. It is the right trade against the
alternative — a design that cannot be saved — and it is stated in the editor rather than hidden: the
persistence outcome is `Stored`, `Compacted` or `Refused`, and a `Refused` write means the document
on screen is newer than the one in storage, which an author should be told.

The in-memory reducer state is deliberately *not* reset by a compaction, so undo keeps working in
the open tab; only a reload settles the log down to what was written.

### Where the bytes live

**IndexedDB**, since compaction stopped being enough: `localStorage` is about five megabytes per
origin, shared with the preview pages' own keys, and a remembered catalog is a good share of that on
its own. IndexedDB is limited by the disk instead. It cost the three things this document always
said it would — a schema, a migration story and an async seam — and each is deliberately small.

- **Schema.** Database `ui-builder-local`, version 1. Store `entries` holds every
  `ui-builder.local.*` key — designs, the remembered catalogs, the two remembered static files —
  with the same text value `localStorage` held. Store `meta` holds `migratedFromLocalStorage`.
- **Migration.** The first page that opens the database copies every `ui-builder.local.*` key out
  of `localStorage` in one transaction, marks it done in the same transaction, and only after that
  commits removes the copied keys — so there is never a moment with no copy, and never two copies
  that could disagree afterwards. A second tab opening at the same time finds the mark and copies
  nothing. A tab still running an older editor keeps writing `localStorage`, where the new one no
  longer looks; reload old tabs after an upgrade.
- **The async seam** is put at the two edges where waiting is already natural, not threaded through
  the reducer. *Open*: before the editor composes, the page opens the database and reads every entry
  into memory, so a read is a map lookup and `LocalDesignStorage` stays synchronous. *Write*: a
  write lands in memory at once — so `Stored`, `Compacted` and `Refused` still come back from the
  same call — and is written behind, in order (read-write transactions on one store commit in the
  order they were opened). A write the database refuses later (its quota, a full disk) cannot change
  an answer already given, so it is reported on its own: the editor says *Not saved in this
  browser* with the reason, exactly as for a synchronous refusal.
- **Fallback.** A browser with no IndexedDB, or one whose database does not answer within four
  seconds, stays on `localStorage` with the old budget. If the database exists but will not open,
  the designs list says so: designs migrated into it on an earlier visit are not visible until it
  does.

### Asking the browser to keep them

Storage a page writes is *best effort*: under storage pressure a browser may clear it, and Safari
clears an origin's storage after a week without a visit unless the site is installed. The first time
a design is saved, the page asks for **persistent storage** (`navigator.storage.persist()`); Chrome
grants it to an installed or frequently used site without asking, Firefox asks the person. The
designs list's "nearly full" notice carries the browser's own figures
(`navigator.storage.estimate()`) — how much this site uses of what the browser allows it — and says
when the browser has not agreed to keep them. Downloading a copy is still the only thing that
survives clearing site data.

### Two tabs, one design

Every write and delete is announced on the `ui-builder.local` `BroadcastChannel`. A tab that hears
one re-reads that key, refreshes its designs list, and — if it has that design open — stops
writing it: `LocalDesignSession.changedElsewhere`. Its edits still apply on screen, but each save
answers `Refused` and the editor says *This design was changed in another tab* with a **Reload**
action. The alternative, last writer wins, would replace the other tab's record wholesale (one key,
no merge) and lose its work with neither tab saying so; refusing loses nothing durable.

## The catalog is the hard part

A design is component ids and property values. What they *mean* — which components exist, what each
one accepts, which of them the Wasm canvas can draw — is the catalog, and the page has no way to
derive it. So the catalog is the one thing an offline session cannot make up, and
`CachingLocalCatalogSource` is what makes offline possible at all:

- **online**: fetch the catalog, use it, remember it;
- **offline**: use the remembered one, and say so in the status line.

Network-first rather than cache-first on purpose. A catalog is pinned by revision in the document,
and a stale one would quietly disagree with the server the next time the design is opened live. The
stored copy is a fallback, never the preferred answer — and when it is being used, the status line
reads `This browser · offline` rather than `This browser`, because the palette in front of the
author is then a memory rather than what the server serves today.

The same network-first rule covers the two other static files a local session needs: the device
presets the Screen inspector offers, and the operations fixture every new design is seeded from.

**So: a browser that has never opened this catalog online cannot open a design offline.** It is told
that in as many words (`CATALOG_UNAVAILABLE`) rather than shown an empty canvas.

## What still needs the server

| Surface | Offline | Why |
| --- | --- | --- |
| Canvas, palette, layers, inspector, undo/redo | ✅ | Compose in the page, reducer in the page |
| Problems panel, Code pane (Compose source) | ✅ | The exporter is common code compiled into the app |
| Device frames, new-design templates | ✅ | Static files, remembered on first load |
| SVG / PNG export | ❌ | Rendered by the server from the stored design; the Export menu is absent |
| Native Compose render | ❌ | A real Compose render the server performs |
| Comments | ❌ | A discussion needs somebody to have it with |
| Reference pictures | ❌ | Design assets the server stores — and a pasted screenshot is most of the origin's quota on its own |
| Presence, collaboration | ❌ | There is one author, and it is the person at this keyboard |

Each of the four refusals is a sentence in the editor naming the reason, not a dead control.

### The app shell itself: a service worker, by choice

Opening `/ui-builder/…?storage=local` still needs the app — `index.html`, `uiBuilder.mjs`, the two
Wasm modules — and without help a cold start with the server unreachable gets it from the HTTP
cache or not at all. `ui-builder-sw.js`, at the root of the web bundle, keeps it on the device.

**It is opt-in, never ambient.** A worker registered at `/ui-builder/` controls every builder page
on the origin, including every automated harness lane that drives one, so the boot script
(`ui-builder-boot.js`) registers it only when a person asked for offline: the editor running as an
installed app (`display-mode: standalone` and friends), a `?storage=local` page, or `?offline=1`.
`?sw=off` unregisters it and deletes its caches. It is never registered inside a frame (an IDE
webview, an MCP App) or outside a secure context.

**The contract with the host:**

| | |
| --- | --- |
| File | `ui-builder-sw.js` at the archive root (`verifyUiBuilderWebArchive` requires it) |
| Served at | `<editor root>/ui-builder-sw.js` — `/ui-builder/ui-builder-sw.js` on compose-preview-server |
| Scope | `<editor root>/` — `/ui-builder/`; registered with that explicit `scope` |
| Headers | `Cache-Control: no-cache` (a release is noticed on the next navigation); a JavaScript media type. `Service-Worker-Allowed: /ui-builder/` is harmless and not needed, since the scope is the script's own directory |
| Never under the versioned prefix | a worker's URL must stay the same across releases; `/ui-builder/v/<digest>/ui-builder-sw.js` would register a new worker per release |

The editor root is computed in the page: the path up to and including `/ui-builder/` when the page
is under one, else the page's own directory — so the same bundle served as plain files registers at
the directory it was served from.

**What it caches, and how:**

- **Navigations, network first.** The shell is the one document a rollout must be able to change,
  so the server answers whenever it can, and the answer is kept. Offline, an *editor* route (the
  root, a design id, a catalog's home) gets the last shell this device saw; the designs index and
  the access, history and delete pages are server pages and are never answered from the cache.
- **The bundle, cache first.** On install the worker fetches the shell, precaches everything it
  names (the two Wasm modules, the `.mjs` files, the boot script) and the vendored fonts, one request
  at a time. Under the server's content-addressed prefix (`/ui-builder/v/<digest>/…`) a URL never
  changes meaning, so cache first is simply correct; precached copies of unversioned URLs are as
  fixed, because the cache is named after the bundle.
- **One cache per bundle.** The build writes a digest of every other file in the bundle into the
  worker, and names the cache after it. A new release is therefore a new worker byte-for-byte, the
  browser installs it beside the old one, and activating it deletes the old caches. Until then a
  small **Reload** notice says a new version is ready; pressing it activates the new worker.
- **Never** anything that is not a `GET`, the catalog runtimes (`/ui-builder/runtime/…`, which run
  in sandboxed frames with their own immutable caching), or the API: `/api/ui-builder/v1/…`,
  `/api/icons/…` and the update sockets are outside the worker's scope altogether. That is why the
  catalogs, device presets and seed fixture stay where they were — network first with a copy in
  this browser's storage, as [The catalog is the hard part](#the-catalog-is-the-hard-part) says.

So the claim is now: **once this browser has run the editor online with offline turned on, a cold
start with the network gone opens it, and a design kept here opens in it.** Without that opt-in the
older claim stands — a tab that already has the app keeps working, and a cold start needs the server.

## Creating a design locally

The server route is the New design form — a real `POST` whose `303` the browser follows, so the
design's permalink is what lands in history. A local design has nowhere to POST, so the page seeds
the document itself with the same shared
[`UiBuilderNewDesignSeed`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/UiBuilderNewDesignSeed.kt)
the server uses, writes it to this browser, rewrites the URL and re-enters the editor. One seed
definition, two places to put the result — a template means the same thing in both modes.

Creating a design this browser already holds is refused, for the reason the server's `PUT` refuses
it: create is not replace, and a New design that silently overwrote one of the same name would lose
work that has no undo left.

## What this is not

It is not offline collaboration. Two people cannot edit a locally stored design at the same time,
because there is one author and it is the person at this keyboard.

What it *is*, since the checkout landed, is a round trip. **Keep in this browser** copies the design
the server is serving into this browser along with its fork point — which server, which design,
which revision, and the digest of the document at it — and **Sync to the server** replays the
commands authored since back onto that server, each claiming the revision its predecessor landed at.
The rules, the refusals and why a merge is a replay rather than a diff are
[`UI_BUILDER_DESIGN_PORTABILITY.md`](UI_BUILDER_DESIGN_PORTABILITY.md).

A design *created* here still has no fork point, and that is not a missing field: it has no
ancestor on any server, so publishing it is a create through
`PUT /api/ui-builder/v1/designs/{designId}`, which refuses to replace. The menu offers it nothing to
sync, and says why.

It is not a second document format. The stored record carries the same `UiBuilderDocument` and the
same reducer commands the rest of the builder uses; the only thing `compose-ui-builder-local-design/v1`
names is the envelope around them.
