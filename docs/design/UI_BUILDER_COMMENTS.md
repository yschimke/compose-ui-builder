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

## Links, next door

The same shelf holds one more record: five typed back-links per design — the issue it is for, the
frame it reproduces, the pull request that implemented it, the thread it is discussed in, and the
design it continues. It is stored beside the design in its own directory for the three reasons above,
which hold for it unchanged, and it is read the same way: its own routes, its own two MCP tools, and
a `links` object spliced onto `ui_builder_get_design` beside the `comments` block.
[`UI_BUILDER_LINKS.md`](UI_BUILDER_LINKS.md) has it.

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
| `POST` | `…/designs/{id}/comments/acknowledgement` | mark the whole discussion as read, for this actor |
| `POST` | `…/designs/{id}/comments/{threadId}/acknowledgement` | mark one thread as read, for this actor |
| `POST` | `…/designs/{id}/comments/{threadId}/{commentId}/reactions` | add an emoji to one comment, or take it back with `on: false` |
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

## Seen, worked on, settled — three different claims

Resolution used to be the only thing an actor could say about a thread, and it says the question is
*answered*. That left an agent that had read a bug report but not yet fixed it with two bad options:
stay silent, and be invisible, or resolve, and lie. A person reading the panel could not tell
"nobody has looked at this" from "somebody is on it".

So there are three acts, and they are deliberately not the same one:

| act | what it claims | where it is stored |
| --- | --- | --- |
| **react** | "noticed" — 👀 on a comment picked up, 👍 on a fix | `StoredComment.reactions`, emoji → the actors who left it |
| **acknowledge** | "I have read this" — nothing about the question | `StoredCommentThread.acknowledgedBy`, actor → the sequence they read it at |
| **resolve** | "this is settled" | `StoredCommentThread.resolved` |

Acknowledgement is **per actor**: a thread the agent has read is still waiting for the second
designer, and the board says so for each of them separately. It is compared against
`StoredCommentThread.updatedAtSequence` — the board sequence the thread was last *spoken* at — so a
reply after an acknowledgement is unacknowledged again, exactly as it should be. The wall clock next
to it is for the panel; a millisecond comparison would silently swallow a reply that landed in the
same millisecond as the acknowledgement.

Three rules keep the set honest:

- **Writing into a thread acknowledges it for the writer.** Posting, resolving and reacting all do,
  so nobody is ever told to catch up with their own words.
- **A reaction acknowledges, and resolves nothing.** That is the decision the issue left open: a
  reaction is engagement with the comment, and it is the lightest possible acknowledgement — 👀 on
  receipt, a reply when there is something to say, resolve when it is actually done.
- **Acknowledging and reacting never move `updatedAtSequence`.** One actor catching up is not news
  the others have to catch up with; an agent's 👀 must not read to a designer as new activity. Both
  still bump the board's own `sequence`, so an open page learns the agent has seen the comment at
  the moment it does.

A thread written before these fields existed carries `updatedAtSequence: 0`, which is below every
acknowledgement and so reads as unacknowledged. That is the safe direction: an old thread resurfaces
once rather than being marked as seen by somebody who never saw it.

## The unacknowledged block, on replies nobody asked to carry it

A designer left "The play icon looks like a cross" on a design an agent was mid-way through editing.
The agent applied several more mutations and exported twice without seeing it, and only found the
comment because the person eventually asked whether it had. The tools to find it existed; the
problem was that noticing was **opt-in** — an agent mid-edit has no reason to poll a discussion it
does not know has moved, and `ui_builder_await_comments` blocks, which is the wrong shape for
something in the middle of a different job.

So the discussion is delivered where the agent is already looking. `ui_builder_get_design`,
`ui_builder_apply`, `ui_builder_export`, `ui_builder_render_native`, `ui_builder_put_asset` and
`ui_builder_await_design` carry a `comments` block whenever this actor has a thread waiting on them:

```json
"comments": {
  "unacknowledged": 1,
  "sequence": 4,
  "threads": [
    {"id": "t-…", "author": "Yuri", "excerpt": "The play icon looks like a cross.", "nodeId": "play-button", "comments": 1, "resolved": false}
  ],
  "hint": "… acknowledging is not resolving, and this block stays until you do one of them."
}
```

- **The excerpt is the load-bearing part.** A bare count is one number among the fields of an apply
  outcome and is easy to skip past; a quoted sentence naming a node the agent has its hands on is
  not. It is never dropped to save bytes.
- **Bounded.** A count, the cursor, and at most three threads, newest first, each excerpt trimmed to
  160 characters. A busy design must not turn every apply outcome into a transcript, and
  `ui_builder_list_comments` is one call away for the rest.
- **Absent rather than zero.** An agent that reads `"unacknowledged": 0` on every call learns to
  skip the key, which is how a notice stops being noticed.
- **Cheap, and never in the way.** One board read per reply, skipped entirely on a host that keeps
  no discussions; the reply is only re-parsed when there is something to add, and a discussion this
  host cannot read costs the agent nothing rather than costing it the answer it asked for.

The comment tools themselves do not carry the block — they answer with the board, so it would be the
same news twice.

## The MCP tools

Six, present only where the host keeps a discussion — absent from `tools/list` rather than present
and failing, the rule the whole surface follows.

| tool | what it is for |
| --- | --- |
| `ui_builder_list_comments` | read the discussion, its `sequence`, and each thread's `acknowledgedBy` |
| `ui_builder_post_comment` | reply, or start a thread pinned to `markId` / `nodeId` / `x`,`y` |
| `ui_builder_acknowledge_comment` | say you have read one thread, or the whole discussion |
| `ui_builder_react_to_comment` | an emoji on one comment, or `on: false` to take it back |
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

The panel does not yet draw reactions or the acknowledgement state — the routes exist and the board
carries both, so a chip row and a "seen by" line are a rendering change rather than a protocol one.
Until it does, the browser is on the human side of the feature the same way it always was: a person
posts and resolves, and what an agent has seen is visible in the board it reads.

Pins are drawn by `CommentPinOverlay`, above the reference overlay and the presence outlines: a pin
is the one thing on that canvas a person clicks that is not part of the design, so it must not end
up under a mock somebody has just turned the opacity up on.

## Evidence

`UiBuilderCommentsPanelPreview` and `UiBuilderCommentPinsOverMarkupPreview` render the panel and the
pins, so the next change to either is diffed without anyone remembering to.
`ServeUiBuilderCommentStoreTest` pins the store's rules — acknowledgement per actor, a reply after
one, a reaction as the lightest acknowledgement, and the notice's shape and bounds.
`ServeUiBuilderCommentsIntegrationTest` starts the real server and plays both parts — a browser
posting while an agent waits, an agent replying while a page is open, and a comment from somebody
else riding along on the reply the agent was already reading until it acknowledges or reacts.
