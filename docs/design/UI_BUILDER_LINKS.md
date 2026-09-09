# UI builder links

A design on this host knows its catalog pin and nothing else. It does not know the issue it was
drawn for, the frame it reproduces, the pull request that implemented it, the thread it is being
discussed in, or the design it continues — and those five facts are what turn a design into a piece
of work somebody can pick up.

So a design carries a **links record**: five optional, typed back-links beside it.

```json
{
  "issue": "https://github.com/yschimke/compose-preview-server/issues/12",
  "reference": "https://www.figma.com/design/abc/Checkout?node-id=1-2",
  "pr": "https://github.com/yschimke/compose-preview-server/pull/34",
  "thread": "https://example.slack.com/archives/C1/p1700000000",
  "previous": "checkout-v1"
}
```

`issue`, `reference`, `pr` and `thread` are absolute `http(s)` URLs, at most 2 KB each; `previous`
is a design id on this host, kept exactly as given: the service creates a design under any
non-blank id, so this field imposes no shape of its own and only refuses a URL. All five are
optional and an absent field means unset. Nothing here is
ever fetched: this host holds no credential for any of the systems these URLs name, and the schemes
are checked so that what is stored is something a browser can be handed, not so that this process
can go and get it.

## Where it lives, and why it is not in the document

The record is stored beside a design, in its own `links/` directory, exactly as the
[reference overlay](UI_BUILDER_REFERENCE_OVERLAY.md) and the
[comment board](UI_BUILDER_COMMENTS.md) are — and for the same three reasons, stated in full in
`ServeUiBuilderReferenceStore`'s KDoc and restated for this record in `ServeUiBuilderLinksStore`'s:
it is not part of the design, the wire cannot carry it, and it must not cost the document anything.
The third reason is the sharpest one here. Pasting an issue URL would advance the design's revision
and invalidate every open client's optimistic state, which is a large price for a fact *about* the
design rather than a change to it.

One JSON file per design, named by the SHA-256 of the design id, written through a temporary file
and moved into place. A design id is caller-supplied text and never becomes a path segment. Losing
the directory loses the back-links and no design content, which is the correct blast radius.

## Why not a saga object

Because the team already has one, and it is the issue.
[`MULTIPLAYER_WORKFLOW.md` §4.2](MULTIPLAYER_WORKFLOW.md#42-do-we-need-product-sagas) argues this at
length: a second long-lived work object inside this server needs status, assignment and a reason to
prefer it over the tracker, at which point it *is* a tracker. What the server lacked was not the
saga but the back-links that let one be assembled from the outside. This is those, and nothing more.

## The routes

Every one of them is authorised twice: the route capability decides whether the caller may use the
UI-builder at all, and then the design is read *through the service, as that actor*, so a design the
caller cannot open is a 404 and nothing is enumerable.

| Route | Capability | What it does |
| --- | --- | --- |
| `GET /api/ui-builder/v1/designs/{designId}/links` | `ui-builder-read` | The record, or 404 when nobody has said |
| `PUT /api/ui-builder/v1/designs/{designId}/links` | `ui-builder-write` | Replace the whole record; 422 with the reason when a value is refused; an empty object deletes it, but a body naming only fields this host does not know is a 422 rather than a clear; 500 when the disk refuses a record that was otherwise fine |
| `DELETE /api/ui-builder/v1/designs/{designId}/links` | `ui-builder-write` | Clear it; 204 whether or not there was one, 500 if a record could not be removed |
| `GET /api/ui-builder/v1/links?issue=<url>` | `ui-builder-read` | The designs citing that issue, filtered to the ones this actor may open |

A **replace** rather than a merge, deliberately: a partial write is how a design ends up citing the
issue it used to be for, and clearing one link has to be expressible.

The reverse lookup is the "feature view" — *what is in flight for this issue* — and it costs a
directory scan. That is the right price for a question asked a handful of times a day, and the
alternative is an index file to keep consistent with the records it indexes.

## The MCP tools

`ui_builder_get_links` (read) and `ui_builder_set_links` (write), listed only on a host that keeps
a links store, which is the rule the whole UI-builder surface follows: a tool that cannot work is
absent from `tools/list` rather than present and failing.

The record also rides along on `ui_builder_get_design` as a `links` object, for the reason the
`comments` notice does — an agent handed a design starts work, and the question it does not think to
ask is what the screen is *for*. Delivering it where the agent is already looking converts "the
agent must think to ask" into "the agent cannot help but see", and costs one file read on a design
nobody has linked.

## The project index

A project's `ui-builder/designs/index.json` may carry a `links` object per entry, and it is written
into this host's store when the design is opened from the library — so a checked-in index says what
each design is for. Additive: an index published before the field existed reads exactly as it did.
The entry is held to the store's own rule, so the index is not a way around it — an entry publishing
a link this host will not keep loses its links, not its design. See
[`UI_BUILDER_PROJECT_DESIGNS.md`](UI_BUILDER_PROJECT_DESIGNS.md).

## Evidence

`ServeUiBuilderLinksStoreTest` covers the store's rules — nothing reads as nothing, the round trip,
a refused scheme, an oversized value, and an emptied record deleting its file.
`ServeUiBuilderLinksIntegrationTest` starts the real server with three credentials and covers the
wiring: a viewer reads and cannot write, a design the actor cannot read is a 404 either way,
`ui_builder_get_design` carries the record, and the reverse lookup lists only what the caller may
open.
