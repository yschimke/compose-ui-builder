# UI builder designs kept in the browser

`/ui-builder/<catalog>/<designId>?storage=local` opens a design this browser holds, rather than one
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
`applyOperation`, `listCatalogs`, `updatePresence` — from designs in `localStorage`, and it applies
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
design (`ui-builder.local.design.<designId>`):

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

One key per design rather than one key holding all of them: `localStorage` has no partial write, so
a single key would re-encode every design in the browser on each keystroke-sized edit, and one
design over the quota would take all of them down with it.

A command whose replay the reducer refuses — a record edited by hand, a log written by a builder
whose reducer differed — stops the replay. The design opens at the last revision that did apply and
the refusal is reported, rather than the design becoming unopenable.

### Compaction, and the history it costs

`localStorage` is about five megabytes per *origin*, shared with every other design in this browser
and with the preview pages' own keys. So a design's record has a byte budget (1 MB), and past it the
current document becomes the new seed and the log is dropped. The same thing happens, budget or not,
when the browser refuses a write outright: compact, and try once more.

What that costs is undo history older than the compaction. It is the right trade against the
alternative — a design that cannot be saved — and it is stated in the editor rather than hidden: the
persistence outcome is `Stored`, `Compacted` or `Refused`, and a `Refused` write means the document
on screen is newer than the one in storage, which an author should be told.

The in-memory reducer state is deliberately *not* reset by a compaction, so undo keeps working in
the open tab; only a reload settles the log down to what was written.

`localStorage` rather than IndexedDB because everything stored here is small, textual and read once
at open. IndexedDB buys asynchrony and a bigger quota, and costs a schema, a migration story and an
async seam. If compaction stops being enough, that is the change that earns IndexedDB.

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

### The app shell itself

The remaining gap is the Wasm bundle. Opening `/ui-builder/…?storage=local` still fetches the app —
`index.html`, `uiBuilder.mjs`, `skiko.wasm` — and with the server unreachable that is served by the
browser's HTTP cache or not at all. Making the shell reliably available offline means a service
worker, which is deliberately **not** in this change: a service worker registered at `/ui-builder/`
controls every builder page on the origin, including the eight Playwright harness lanes, and that
blast radius deserves its own change with its own evidence rather than riding along with the storage
mode.

So today's honest claim is: **a tab that already has the app keeps working with the network gone,
and a design opened in it is safe.** A cold start still needs the server to hand over the bundle.

## Creating a design locally

The server route is the New design form — a real `POST` whose `303` the browser follows, so the
design's permalink is what lands in history. A local design has nowhere to POST, so the page seeds
the document itself with the same shared
[`UiBuilderNewDesignSeed`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderNewDesignSeed.kt)
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
