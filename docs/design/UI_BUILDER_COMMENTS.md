# UI builder comments

A design can be discussed where it is being built. `/ui-builder/{catalog}/{design}` grows a **Talk**
panel: threads pinned to a markup stroke, a design node, or a point on the frame, with replies under
them — between the people looking at the design and the agents helping with it.

The feature is event-driven on both sides. A browser holds a socket; an agent holds a tool call. A
comment typed in the page wakes the agent, and the agent's answer appears in the page, without
either of them polling for the other.

## Where it lives, and why it is not in the document

Comments are stored beside a design, in their own directory, exactly as the
[reference overlay](UI_BUILDER_REFERENCE_OVERLAY.md) is — and for the same three reasons, stated in
full in `ServeUiBuilderCommentStore`'s KDoc:

1. **A comment is not part of the design.** "Should this row be a card?" must never reach the
   Compose export, the SVG export or the rendered document.
2. **The wire cannot carry it.** `DesignMutationV1` is a closed set with no comment mutation, so
   there is no way to write one without releasing `ui-builder-protocol` — to carry something point 1
   says should not be in the document.
3. **It must not disturb the document.** The document is replayed, hashed, diffed for catalog
   upgrades and pushed to every subscriber on every edit. A reply typed into a review thread would
   advance the design's revision and invalidate every client's optimistic state. Discussion has to
   happen *about* a revision without changing it.

Losing the comments directory loses the discussion and no design content, which is the correct blast
radius.

## The board, and its one cursor

One file per design, holding a `StoredCommentBoard`: a list of threads, and a `sequence` that rises
by one on every accepted write — a comment, a reply, a resolve, a delete.

A reader that quotes the sequence it last saw is answered with **the whole board**, not the threads
that changed since. That is deliberate: a design's discussion is a few kilobytes of text, replaying
it costs less than the bookkeeping a per-thread log would need, and a client returning from a nap
gets one answer that is correct rather than a window it may have fallen out of. The design
document's own event log makes the opposite trade for the opposite reason — it is replayed into a
reducer, and it is large.

## Anchors: the link to markup

A thread carries an anchor, and an anchor can say three things at once:

| field | what it pins to | why it exists |
| --- | --- | --- |
| `markId` | a stroke on the reference overlay | circle the thing that is wrong, then say why — this is the point of comments and markup being one product |
| `nodeId` | a node in the design | survives the reference being replaced |
| `x`, `y` | frame fractions, the space `ReferenceMark.points` uses | survives the frame changing from a phone to a tablet |

The editor writes the mark **and** the point it resolves to when a thread is pinned to a stroke, so
rubbing the stroke out later leaves the pin where the author put it and the thread reading
"a mark that is gone" rather than silently jumping to the origin.

## The routes

All of them are plain REST plus one socket, rather than protocol requests, for the reason the
reference and native-preview routes already give: the released `UiBuilderRequestV1` union has no
request for any of this.

| method | path | what it does |
| --- | --- | --- |
| `GET` | `…/designs/{id}/comments` | the whole board |
| `POST` | `…/designs/{id}/comments` | a comment: a reply into `threadId`, or a new thread where `anchor` says |
| `POST` | `…/designs/{id}/comments/{threadId}/resolution` | close a thread, or reopen it |
| `DELETE` | `…/designs/{id}/comments/{threadId}` | remove a thread and everything said in it |
| `GET` | `…/designs/{id}/comments/watch?afterSequence=&waitSeconds=` | long poll; `204` when nothing was said in time |
| `WS` | `…/designs/{id}/comments/updates` | the board on connect, and again on every change |

**Authorised twice**, like the reference routes: the route capability decides whether this caller may
use the UI-builder at all, and then every request reads the design *through the service, as that
actor*, so the design's own access control decides whether there is a design here to discuss.
Without the second check, an actor holding a write capability could post into a design they cannot
open and enumerate which design ids exist by watching which writes succeeded.

An author is **always** the authenticated actor. `CommentPostRequest` has no author field at all: the
only way an author reaches the store is the parameter the route fills in. `authorKind` (`human` or
`agent`) is declared rather than derived, and is cosmetic — it decides a badge, never a permission,
because the host cannot tell a designer's browser from an agent's MCP session by the credential
alone.

## The MCP tools

Four, present only where the host keeps a discussion — absent from `tools/list` rather than present
and failing, the rule the whole surface follows.

| tool | what it is for |
| --- | --- |
| `ui_builder_list_comments` | read the discussion and its `sequence` |
| `ui_builder_post_comment` | reply, or start a thread pinned to `markId` / `nodeId` / `x`,`y` |
| `ui_builder_resolve_comment_thread` | close a thread once it is answered, or reopen it |
| `ui_builder_await_comments` | **wait** for the discussion to move past a cursor |

`ui_builder_await_comments` is what makes an agent a participant rather than a tool: post, wait,
read, act. It returns the moment anybody — a designer in the browser or another agent — posts,
resolves or deletes, and answers a `timedOut` reply when nothing happens within `waitSeconds`, which
the caller acts on by asking again with the same cursor. It is the same
`ServeUiBuilderCommentStore.subscribe` the browser socket is built on, so neither surface can learn
about a comment the other does not.

## The panel

`CommentsInspector`, behind its own switch on the right-hand rail — **Talk**, badged with the number
of open threads, the way Issues is badged with its count. A new thread's pin is chosen from three
chips — this design, the selected layer, or the last mark drawn — and the Markup section of the
Screen panel has a **Discuss** button beside `Undo mark` and `Clear marks`, which is the gesture the
two features exist to join.

The panel holds no cache. Everything it draws comes from the board the host last received, so a
comment is not shown until the server has stored it and told everybody — the same rule the editor
already applies to a design edit, and what stops the panel showing a reply an agent never received.

Pins are drawn by `CommentPinOverlay`, above the reference overlay and the presence outlines: a pin
is the one thing on that canvas a person clicks that is not part of the design, so it must not end
up under a mock somebody has just turned the opacity up on.

## Evidence

`UiBuilderCommentsPanelPreview` and `UiBuilderCommentPinsOverMarkupPreview` render the panel and the
pins, so the next change to either is diffed without anyone remembering to.
`ServeUiBuilderCommentStoreTest` pins the store's rules; `ServeUiBuilderCommentsIntegrationTest`
starts the real server and plays both parts — a browser posting while an agent waits, and an agent
replying while a page is open.
