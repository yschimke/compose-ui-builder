# Where a design lives, and how it comes back

**Status: built for the browser lane — the checkout and the sync back; proposed for the rest.** This
answers the question raised against
[#536](https://github.com/yschimke/compose-preview-server/pull/536): a design should be able to live
on this server, in a file, in this browser, or in a git repository — and one design may move through
several of them. *I download a design just before a flight, and sync it back when I land.* What
happens to the document then?

## The short answer

**There is one merge in this system and it is the reducer.** A design that comes back from an
offline session comes back as **the commands that were authored while it was away**, replayed
through [`CollaborationReducer`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/CollaborationReducer.kt)
at the revisions they were authored against. Syncing back after a flight is not a new problem: it is
the problem of a collaborator whose round trip took eight hours instead of eight milliseconds, and
the reducer already resolves that one, per property, per node, per modifier chain, with a conflict
notice when a stale write wins.

Everything else in this document follows from one rule:

> **A lane that can carry the command log can be reconciled. A lane that carries only a document can
> be copied, forked or published — never merged.**

## The four places are not four peers

The four lanes in the question are not four equivalent homes with a sync matrix between them. They
divide by what they carry, and what they carry decides what they can do:

| Lane | Carries | What it is for | Reconciles |
| --- | --- | --- | --- |
| **This server** | seed document + the accepted command log, revisions, undo history, presence | the design being edited, by anyone | it *is* the record |
| **This browser** ([#536](https://github.com/yschimke/compose-preview-server/pull/536)) | seed document + the accepted command log ([`LocalDesignRecordV1`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/local/LocalDesignRecord.kt)) | one author, offline | yes — same vocabulary, same reducer |
| **A file** | either a `DesignDocumentV1`, or a checkout bundle (seed + log + fork point) | moving a design between the two homes above | no — it is the courier, not a party |
| **A git repository** ([`UI_BUILDER_PROJECT_DESIGNS.md`](UI_BUILDER_PROJECT_DESIGNS.md)) | `DesignDocumentV1` documents under `ui-builder/designs/` | review, diffs, a design a pull request can argue about | no — and deliberately so |

So: **two homes, one courier, one publication surface.** Reconciliation only ever happens in one
direction, into one place — the server, in the reducer, by replay. That is not a limitation to be
lifted later; it is the only place in the system where the rules that decide who wins are written
down once.

The browser lane already knows this about itself. #536's whole design decision was to reuse the
server's reducer in the page rather than write a second, simpler one — so a design edited offline is
edited by the same rules with the round trip removed. Sync-back is the same decision applied to the
return journey: reuse the ordering the live session already gets right rather than invent a merge.

## What a checkout has to record

A design taken offline diverges from the moment it is taken. Reconciling the two later needs one
thing that exists only at that moment and cannot be recovered afterwards: **the fork point.**

```json
"origin": {
  "server": "https://preview.coo.ee",
  "designId": "checkout",
  "revision": 41,
  "sequence": 512,
  "canonicalDigest": "sha256:…",
  "takenAtEpochMillis": 1700000000000
}
```

Without it there are two documents and a guess. With it there is a common ancestor, which is the
whole difference between a merge and an overwrite. The digest is what makes the claim checkable: a
fork point naming revision 41 of a design whose retained revision 41 hashes differently is a record
from a different design's history, and the sync should refuse rather than produce a plausible
document nobody authored.

`LocalDesignRecordV1` carries this as `origin`, written by **Keep in this browser** — the act that
knows the answer — and never rewritten. A record without one is not a lossy record: it is an
accurate description of a design that has no origin, and it syncs as a **create** rather than a
merge (below).

## The flight, step by step

The server design is at revision **R** when it is taken. Offline, the reducer continues that
revision line from R — and so does the server, for whoever is still editing it. Both lines number
their next revision `R+1`, and they mean different documents. **That is the fact that makes a naive
replay wrong**, and the reason the rebase below is spelled out rather than assumed.

Offline the author writes commands `c₁…cₙ`, stored with the local base revisions `R`, `R+1`, ….
On landing, the server is at revision **S ≥ R**. The sync sends the log through the ordinary
protocol client — no new endpoint, no new document format — with each command's base rewritten:

```text
base(c₁) = R                            the fork point
base(cₖ) = committedRevision(cₖ₋₁)      the revision this run's predecessor landed at
```

Three properties come out of that chain, and each is the reason not to do the obvious alternative:

- **Not `base = current`.** Claiming the server's newest revision for every command would make each
  offline edit *newer* than the concurrent server edits it is racing, so it would win silently. The
  reducer's staleness comparison is per-address (`propertyVersions`, `modifierVersions`,
  `moveVersions`, `structuralVersions`) and it is what produces `STALE_PROPERTY_WRITE` and
  `STALE_MOVE`. Naming the true base is what keeps the conflict *reportable*; naming the current
  revision throws the report away and calls the result a merge.

- **Not `base = R` for the whole run.** Every command claiming the fork point would make the run
  concurrent with *itself*: `c₅`'s move of a node `c₂` inserted resolves its anchor against the
  position snapshot retained at R, where that node does not exist — `INVALID_LOCATION` — and a
  property `c₂` and `c₅` both wrote would be reported as `c₅` overwriting a stale write of the same
  author's. The offline run is a sequence, not a set.

- **The chain is deterministic, so the sync is resumable.** The reducer treats a resent
  `operationId` as an idempotent replay and returns the revision it originally committed at — but
  only if the command is *identical*, `baseRevision` included, otherwise it is `OPERATION_ID_REUSED`.
  Because each base is derived from what the server already answered rather than from what is
  current, re-running an interrupted sync from the top reproduces exactly the same commands: the
  landed prefix answers idempotently and the run continues. No transaction, no cursor to keep
  honest, no half-applied state to clean up. (The window is
  `retainedCommittedOperations = 1_024` in
  [`PersistentUiBuilderService`](../../ui-builder-runtime/src/main/kotlin/ee/schimke/composeai/uibuilder/service/PersistentUiBuilderService.kt);
  a resumed sync is well inside it, a sync resumed after a thousand other operations is not, and
  would double-apply. If retries are ever unattended, that number is what bounds them.)

### Replay the run faithfully, including the undos

The stored log holds `Undo` and `Redo` submissions alongside batches, and they replay as
themselves. Compacting the run to its net effect first is tempting — fewer revisions on the server —
and it is wrong twice: the net effect of an offline session is a document diff by another name, so
it discards the per-command base revisions that make the merge resolvable at all; and the undo
compensations are what let the author, after landing, undo the work they did on the plane. Undo and
redo carry an `ACTOR_MISMATCH` guard, so the run replays as one actor — the offline author — who is
then the one who can undo it. That is the honest description of what happened.

The price is `n` revisions on the server for `n` offline commands, including the ones that cancelled
each other out. Revisions are cheap; a merge nobody can explain is not.

## What the reducer decides, and what it refuses

The sync produces a **report**, not a silent result. Every outcome below is already a code the
reducer returns; the sync's job is to carry it to a person, not to interpret it.

| Outcome | What happened | What the author is told |
| --- | --- | --- |
| `Accepted`, no conflicts | nobody touched what you touched | landed at revision *S+k* |
| `Accepted` + `STALE_PROPERTY_WRITE` | you and someone else set the same property; yours is now the value | which node, which field, whose revision was overwritten |
| `Accepted` + `STALE_MOVE` | the same, for a node's position | which node |
| `Accepted`, `idempotentReplay` | this command already landed — a resumed sync | nothing; it is not news |
| `REVISION_MISMATCH` on a delete | you deleted a node offline that somebody has edited since | **refused**, and it stops the run |
| `REVISION_NOT_RETAINED` | the fork point is older than the server's retained revisions | **refused**: the flight was too long |
| `UNSAFE_COMPENSATION` | an undo whose target has since been overwritten | **refused**, that command only |
| `INVALID_PROPERTY`, `INVALID_DOCUMENT` | the catalog moved under the design | **refused**: a pin problem, not a merge problem |

Two of those deserve their reasons stated, because they are the cases where this design says *no*
rather than resolving something:

**A stale delete is refused, and that is correct.** The reducer requires the current revision for
`DeleteNode` and `RestoreNode` — a delete is a claim about a subtree, and a subtree somebody has
edited since is not the thing you decided to delete. Retrying it at the current revision would be a
decision ("yes, delete it anyway, including their work"), and decisions belong to the person, not to
the sync. So the run stops there, with the prefix landed and the rest of the log still in the
browser, and the author is asked. A sync that guessed here would be the one way this design could
lose work irrecoverably.

**The flight window is bounded, and it is not indefinite.** `retainedRevisionSnapshots = 1_025`
means a fork point survives a thousand subsequent revisions of the same design. That is a long
flight for a design one team is editing and a short one for a design under heavy collaboration. The
refusal is legible (`REVISION_NOT_RETAINED`), and the fallback is the fallback for any lane that
cannot merge: publish the offline design as a **new** design and let a person reconcile two screens
side by side. Making the window unbounded means retaining every revision of every design forever,
which is the cost [#578](https://github.com/yschimke/compose-preview-server/issues/578) is already
working to bound.

## Why not a three-way document merge

The obvious alternative is to keep only documents everywhere — they are what files and git carry
anyway — and merge base/ours/theirs structurally when a design comes home. It is rejected for
reasons that are specific rather than aesthetic:

- **A document has no intent.** "This node's `text` is now `Buy`" and "the author retyped the label
  while someone else was renaming it" are the same diff. The reducer knows which revision last wrote
  each address, so it can say who is stale; a document diff cannot, and would either always win or
  always lose.
- **It would be a second merge.** The rules that decide a conflict would then exist twice — once in
  the reducer for live editing, once in the merger for offline — and the day they disagree is the
  day a design means different things depending on how it got home. #536 refused to write a second
  reducer for exactly this reason; a document merger is that same second reducer with a different
  name.
- **It can produce a document nobody authored.** A structurally merged tree can violate the
  catalog's own rules — a required property dropped, a slot given a child it does not accept — and
  the first time anyone finds out is a render. Every command that reaches the reducer is validated
  against the catalog at the revision it lands on.
- **Undo would not survive it.** A merged document has no compensation history, so landing an
  offline session would silently empty the undo stack of the design it landed in.

The one thing a document merge is genuinely good at is what git already does with published
documents — a human reading a diff in a pull request — and that is the lane it keeps.

## File and git: publication, not sync

**A file is a courier.** It carries one of two things, and which one it carries is not a detail:

- a `DesignDocumentV1` — a *snapshot*, for publishing, reviewing, seeding, importing. This is what
  `scripts/ui-builder/design-sync.mjs publish` writes and what
  `POST /admin/ui-builder/library/{system}/{designId}` reads.
- a **checkout bundle** — the seed, the log and the fork point, i.e. `LocalDesignRecordV1` with its
  `origin`. This is what makes "download before the flight" work on a laptop that is not the browser
  the design was taken in, and what makes an offline session survive a cleared origin. It is not a
  publication format and should not be reviewable, diffable or hand-edited; it is a home in transit.

Keeping those two shapes distinct is the point. A file that might be either is a file whose import
has to guess whether it is being asked to create a design or to reconcile one.

**Git holds documents, and its merge is a person.** The published form is deliberately a whole
document rather than an operation log
([`UI_BUILDER_PROJECT_DESIGNS.md`](UI_BUILDER_PROJECT_DESIGNS.md)), because a design in a repository
exists to be *read* — the `export` operations form is a list of inserts in tree order precisely so
that a diff reads as what changed about the screen. Storing a reducer command log there instead
would trade the only property that justifies putting a design in git.

Which means the answer to "how do two branches of a design in git reconcile?" is: **the same way two
branches of anything in git reconcile — a person resolves it, in the pull request.** Two further
rules follow, and they are the ones worth writing down:

- **Never auto-merge a design document by text.** A three-way text merge of two JSON documents can
  produce a syntactically valid file that is not a valid design, and the repository would carry it
  as though somebody had approved it. `ui-builder/designs/*.json` should carry `merge=binary` in
  `.gitattributes` — a conflict a person resolves by opening both in the builder, not by editing
  braces.
- **Stamp what a published document was published from.** The same `origin` block, on the published
  document: which host, which design, which revision, which digest. It costs nothing, and it turns
  "is this file behind the design on the server?" from an archaeology exercise into a comparison.
  It is a provenance record, not a sync cursor — nothing reads it to decide anything automatically.

Opening a design from a repository stays what it is today: a **create**, refusing to replace an
existing design, one-way, no writing back on its own.

## Identity: when are two designs one design?

Today's rule, stated in #536, is that a locally stored design and a server-stored design with the
same id are two designs. With fork points that rule refines, and it should refine in exactly one
way:

> Two designs are the same design when one carries an `origin` naming the other, and the digest at
> that revision agrees. Nothing else — not a matching id, not a matching title — makes them one.

So an id collision is still two designs, which keeps the existing create-never-replaces guard doing
its job. And a locally created design that has no origin **syncs as a create**, through the existing
`PUT /api/ui-builder/v1/designs/{designId}` route, refusing an id that is taken. There is no merge
there because there is no common ancestor, and inventing one — treating an empty template as the
base — would be the document merge this design just rejected, with a fabricated base.

## What is built, and what is not

The browser lane is whole: a design can be taken from a server into this browser and brought back.

1. **The browser as a home** — offline editing, template-created designs
   ([`UI_BUILDER_LOCAL_STORAGE.md`](UI_BUILDER_LOCAL_STORAGE.md)). *Built.*
2. **Keep in this browser** — copies the server's design here with its fork point. *Built.*
3. **Sync to the server** — the replay above, its base chain, its report and its refusals, driving
   the ordinary protocol client rather than a new endpoint. *Built*
   ([`LocalDesignPortability.kt`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/local/LocalDesignPortability.kt)).
4. **The checkout bundle as a file** — download and upload of a record with its fork point, for a
   flight that changes machines. *Not built.*
5. **The provenance stamp on published documents**, and the `.gitattributes` merge rule. *Not
   built*, and independent of everything above.

Steps 2 and 3 landed together deliberately: a checkout that cannot come home is a fork with extra
steps, and it would be reasonable for an author to expect otherwise.

## Not in scope

- **Two browsers, one offline design.** Syncing between homes without passing through the server is
  a peer-to-peer merge, and it needs a third party to say which revision line is which. The file
  courier covers moving a session; it does not cover editing it in two places.
- **Assets and reference pictures.** A reference picture is a server-stored asset and an offline
  session has none (#536); a design that gained one offline has nothing to sync. Content-addressed
  assets would reconcile trivially by digest, and that is a separate change.
- **Presence and comments across the divide.** There is nobody to be present with offline, and a
  comment on a revision that only one browser has seen has nothing to anchor to.
- **Automatic sync.** For the same reason #536 made local storage a mode rather than a fallback:
  an author needs to know which copy is the one they are editing, and a sync that happened on its
  own is a sync they cannot have decided to postpone.
