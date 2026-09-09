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

The two sidecars part company on one point, and on purpose: changing a design's overlay or its links
record takes that design's own `WRITE` action, while commenting takes only `READ`. A reviewer who
may see a screen is exactly the person the board exists for, so being a viewer must not be a reason
they cannot say the icon looks wrong. [`UI_BUILDER_SIDECAR_ACCESS.md`](UI_BUILDER_SIDECAR_ACCESS.md)
sets out both gates and why this row differs.

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

## Telling the room

Everything above is event-driven *inside* the product: a browser holds a socket, an agent holds a
tool call, and one write wakes both. What none of it reaches is somebody who is not in the editor. A
designer's "the gap above the card is wrong", left in Talk on a Friday afternoon, is invisible to
the PM in the chat thread and to the engineer on the PR until one of them opens the design.
[`MULTIPLAYER_WORKFLOW.md`](MULTIPLAYER_WORKFLOW.md) names that as the one missing direction of its
review loop, and `--ui-builder-comment-webhook <url>` (container
`SERVE_UI_BUILDER_COMMENT_WEBHOOK`) is it: one URL, told when a board moves.

**What fires** is what somebody *said* — four events, each carrying the author, their `authorKind`,
the excerpt trimmed by the same 160-character rule the `comments` notice uses, where the thread is
pinned, the design's id and title, and the thread permalink
`https://<host>/ui-builder/<catalog>/<designId>#thread=<threadId>`:

| event | when |
| --- | --- |
| `thread` | a new thread |
| `reply` | a comment under an existing one |
| `resolved` | a thread closed — quoting the *opening* comment, because what was settled is the question rather than the "done" under it |
| `reopened` | a thread opened again; it names no author, because the store clears `resolvedBy` and inferring the actor from the acknowledgement map would be a guess dressed as a fact |

**What does not fire** is a reaction, an acknowledgement or a deletion, and that is the load-bearing
decision rather than an omission. The rule three sections up already draws the line for the board's
own cursor — *one actor catching up is not news the others have to catch up with* — and a
notification is the same claim made louder. A webhook that posted on every 👀 would fire three times
for one comment an agent picked up, answered and closed, and a channel that posts noise is a channel
people mute. A deleted thread is silent for a different reason: the news would link to a thread that
is gone.

**It cannot drift from what the editor sees.** It is a third subscriber to the same
`ServeUiBuilderCommentStore` feed the socket and `ui_builder_await_comments` are built on, announced
from the same statement, so it can neither learn about a comment they miss nor stay quiet about one
they show. What it receives is a whole board twice — as it was and as it is — and *what changed* is
a diff of the two rather than a field the store would have to carry. Same trade the board itself
makes: replaying a few kilobytes of text costs less than the bookkeeping a per-thread change log
would need, and a write shape added later cannot forget to announce itself.

**Delivery never touches the write.** The comment is already accepted and durable when the webhook
hears about it, so the listener does an in-memory diff and hands the events to a bounded queue that
one background coroutine drains. A slow, wedged or 503-ing chat platform therefore costs the person
who typed the comment nothing. The queue drops the **oldest** on overflow, with one log line: a full
queue means the far end is behind, and in that state the newest comment is the one worth having.
Timeouts are seconds, and there is exactly one retry — a chat platform's hook is up or it is not,
and a longer ladder turns one wedged host into a queue that never drains.

**What a notification says is fixed when the comment is written, not when it is delivered.** The
design's title and catalog are resolved on the thread accepting the comment and travel with it on
the queue. A design id is not a stable name for a design — ids come from the client and are free
again once one is deleted — so metadata resolved at delivery time, or remembered from an earlier
event, can put one design's title and permalink on another design's comment. That is why
`adminDesignSummary` exists: a keyed read under the service's lock rather than the scan of every
design that listing them would pay, small beside the disk write the comment already does. Delivery
staying off that thread is a separate promise, and the one that matters for latency: a slow or dead
webhook host never touches the write.

Shutdown gives the queue a couple of seconds to drain and says out loud what it abandons: nothing
replays a notification, so an event dropped at SIGTERM is a comment that stays in the board and is
never announced.

**Nothing on the wire is markup.** Every string in a notification was typed by whoever left the
comment, so each adapter renders it as characters: Slack and Google Chat escape the three
characters they read as markup, and the Teams card carries its text in `TextRun` inlines, which do
not interpret Adaptive Card Markdown at all. Without that, a comment body of
`[Open the design](https://attacker.example)` arrives in a shared channel as a clickable link to
somewhere nobody chose, under a headline naming a colleague as its author.

**The name shown is not the identity carried.** A comment's `displayName` arrives in the request
body while its `authorId` is established by the authorization layer, so anyone who may comment can
put a colleague's name on one. The event carries both: `author` is the cosmetic label a channel
shows, and `authorId` is the authenticated actor a relay can check it against. Only the label would
have let a chat window state as fact that somebody said a thing they did not.

**A permalink is only worth sending to somebody who can open it.** The browse token travels as a
header or `?token=`, never a cookie, so on a host gated by `--token` with no GitHub sign-in a
recipient lands on the shell and the design behind it stays refused. Starting with a webhook in that
configuration prints a note saying so. The token is deliberately *not* put in the link: it is a far
stronger credential than the hook URL this feature refuses to log, and a chat channel is long-lived
and widely readable. It is a note rather than a refusal because a local `serve` posting to a
loopback receiver is legitimate, and so is a proxy that authenticates in front of the box.

**The URL is a credential.** A Slack or Teams hook URL carries its secret in its path: anybody
holding the string can post into that channel. So it is never logged — not on success, not on
failure, not in the banner — and everything that has to name it names a short digest of it instead,
which tells two configured hooks apart and is useless to whoever reads the log. For the same reason
only `https` is accepted, refused at startup rather than at the first delivery, with `http://` on
loopback the one exception: that is the test receiver and the local relay, and there is no network
to eavesdrop on.

**The bodies.** `--ui-builder-comment-webhook-format` picks one of four, and each adapter is a pure
function from the plain event, so what Slack receives is checked by a unit test rather than by an
operator with a real channel:

| format | body |
| --- | --- |
| `plain` | this server's own event JSON — `{"event", "design", "thread", "comment", "url"}` |
| `slack` | `{"text": …}` in mrkdwn, the permalink as the title's link, the excerpt quoted under it |
| `teams` | a minimal Adaptive Card in the `message` envelope, the permalink as an **Open the thread** action — a card rather than `text` because the Workflows hooks that replaced Office 365 connectors take only the card |
| `google-chat` | `{"text": …}` in Chat's markup, which is Slack's for the two things used here |

The format is named rather than sniffed from the hostname: a hook behind a relay or a workflow
runner has a host that says nothing about what parses the body at the far end, and guessing wrong is
a channel that silently receives nothing readable.

The `#thread=` selector the permalink carries is a separate build item and may not have landed on
the host reading the link. That is why it is a fragment: an editor that does not understand it opens
the design and ignores it, which is the right degraded behaviour — the reader still lands on the
thing being discussed rather than on a 404.

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

`ServeUiBuilderCommentWebhookTest` pins the outbound half's two pure pieces: the board diff (a new
thread, a reply, a resolve, a reopen; a reaction, an acknowledgement and a deletion producing
nothing) and each format adapter, including that markup typed into a comment is escaped rather than
interpreted — `<!channel>` in a design review must reach the channel as those characters.
`ServeUiBuilderCommentWebhookIntegrationTest` puts a real receiver on the other end of the real
server: a comment posted over HTTP arrives with its permalink, a reaction between two events that
*are* news arrives nowhere, and three comments written against a receiver that never answers are
accepted at once rather than one timeout apart.
