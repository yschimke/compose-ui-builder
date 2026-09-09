# The UI builder's history bar — revisions, thumbnails, and how it fits with the versions view

Status: **built** (2026-09). The strip, the read-only peek, the two-revision compare and the
document diff are in `:ui-builder`; the open questions in [§6](#6-open-questions) are about where it
goes next, not about whether it works.

The editor already kept a complete account of what had been done to a design and showed it as
words. This adds the other half — what each of those revisions *looked like* — as a strip of
thumbnails under the canvas, with any two of them comparable.

---

## 1. The question a list of changes cannot answer

The History dock lists every accepted change newest-first: what it did, who did it, the before and
after of every value it moved, and which one undo is aimed at. It is a good answer to *what has been
done*.

It is no answer at all to *which one looked like what*. That question comes up constantly and only
pixels settle it — "it looked right four changes ago" is a statement about an image, and finding
which change broke it by pressing undo four times and watching is the workflow this replaces.

The viewer settled the same argument for published renders and wrote down why, in
`serve-web/src/viewer/historyModel.ts`:

> A timeline of dates and shas answers "how many versions are there" and nothing else — the question
> a reader actually brings to it is "which one looked like what", and only pixels answer that.

So the builder's history gets pictures for the same reason, in the same shape.

---

## 2. How it fits with the versions view

They are **different axes, kept apart on purpose**, sharing one vocabulary and one visual shape.

| | **Versions** — the viewer | **Revisions** — the builder |
| --- | --- | --- |
| Subject | one *published render* of one preview | one *committed edit* to one design document |
| Unit | a delivery-branch commit whose render bytes changed | a revision minted by `CollaborationReducer` |
| Source | `PreviewHistoryManifest` off `compose-preview/main`, or `ServeProjectHistory` off the local clone | the accepted commands, undos and redos in `CollaborationState` |
| Picture | the published PNG, content-addressed (`raw.githubusercontent.com`, or `/history/render/<blob>.png` in project mode) | the document rewound to that revision, drawn live by the renderer already drawing the canvas |
| Diff | `history_diff` — a *metadata* comparison of content ids, no image fetched | `revisionDiff` — the two documents compared node by node, plus the two renders side by side |
| Collapse | adjacent commits with identical bytes fold into one version | adjacent revisions with identical documents fold into one row |
| Lifetime | forever; immutable; survives the session, the clone and the design | this editor's accepted window (see [§4](#4-what-the-strip-can-and-cannot-reach)) |
| Instability | `unstable` / `flapCount` — a preview that re-renders differently on publishes that did not change it | not applicable; a revision is a recorded edit, not a re-render |

The two answer different questions. *Did the last publish move this preview?* is the versions view's
question, and it is about a rendering pipeline. *What did I just do to this design, and what did it
look like before?* is the strip's, and it is about an editing session. Merging them would give one
timeline whose entries mean two things.

**Where they meet** is publication. A design exported into the render pipeline — a committed
exemplar screen rendered and diffed on every PR — becomes a preview, and its published renders are
versions. So a revision is what a version is eventually made of, not a smaller kind of one:

```
edit ─▶ revision ─▶ … ─▶ revision ─▶ export ─▶ published render ─▶ version ─▶ … ─▶ version
        └────────── the strip ──────────┘                         └──── the versions view ────┘
```

What the two deliberately share, so that a person who has read one surface can read the other:

- **A picture per entry**, because that is the question both are asked.
- **Identical neighbours collapse**, marked `×N`. The viewer folds identical render bytes; the strip
  folds identical documents. Both for the same reason — a timeline is a list of states, and two
  entries nobody can tell apart are one state listed twice.
- **One entry marked current**, so "which one am I on" is never answered by hunting.
- **Any two entries comparable**, and the comparison names what moved rather than only showing that
  something did.

What they deliberately do not share: the strip has no URL of its own and no permalink. A version is
addressable forever because its bytes are published; a revision is a position in a live session's
record, and a link to one would resolve to nothing in another browser. Where a revision *should* be
addressable, the answer is the existing one — pin the design at that revision
(`?revision=`, `authoritativeRevisionFor`), which is a link to a document rather than to a row on a
strip.

---

## 3. Where the pictures come from

**Nothing is stored per revision.** Two facts already in the editor make that unnecessary:

1. Every mutation the reducer accepts carries its **compensating changes** — the before and after of
   every property, modifier chain, environment field and structural move. Undo replays them; so a
   rewind is the same changes applied in reverse order over the same `compensate` function
   (`CollaborationState.documentsBackTo`).
2. The canvas is a **live renderer**, not a picture of one. The component palette already draws its
   rows by inserting the component into an empty frame and shrinking the result with a draw-time
   transform, rather than by shipping baked PNGs.

Put together: the strip rebuilds each revision's document from the record and draws it through
`UiBuilderSurface` at the design's own frame, scaled down. So no PNG is committed, no render is
commissioned from a daemon, a thumbnail cannot disagree with what going back would show, and a
design nobody has baked artwork for still gets pictures.

One walk covers the whole strip. The rewind to revision *n* passes through every revision above it,
so `documentsBackTo` records each as it goes rather than answering forty questions from scratch.

**Read-only, and structurally so.** The rewound state is a picture, not a place to edit from: the
version stamps that make undo safe are not rewound, because nothing submits from a peek. Looking at
an old revision *replaces* the editing canvas rather than covering it — a canvas that accepted a
drop at revision 12 of a design at revision 40 would be editing a picture, and not composing the
editing surface at all is that rule holding itself rather than an overlay somebody eventually finds
a way around. Moving a design backwards is what undo is for, and it has guards this does not need.

An edit made while peeking (through the layers tree, the inspector, a chord — all still live)
brings the canvas back to the design and puts the new revision on the strip. The edit lands on the
document, which the peek never touched.

---

## 4. What the strip can and cannot reach

`reconstructableFromRevision()` is the floor: the oldest revision reachable by an **unbroken** run of
recorded mutations back from the current one. Below it the strip stops, and its first row says so.

This matters in two real cases:

- **A design reopened from a stored snapshot.** The client holds the mutations made since it
  connected and none of the ones that built the document it was handed. The History dock has always
  had this property — it is about what *this session* has done — and the strip inherits it honestly
  rather than drawing rows it cannot picture.
- **A collaborator's edit.** An authoritative document that differs from the local one rebuilds the
  editor's collaboration state, so the record restarts there. The strip survives the reconcile; what
  it was showing does not.

A row whose document could not be rebuilt keeps its words and loses its picture. A thumbnail that is
not what that revision looked like is worse than a blank one, so the rewind fails closed — on a
missing record, and on a compensation that refuses.

The strip is bounded at `REVISION_TIMELINE_LIMIT` (24) rows. That is the length of the *strip*, not
of the history: every row costs a rebuilt document in memory and a composed thumbnail on screen, and
a strip long enough to need its own scrollbar has stopped being a glance. Everything older is still
in the record and still undoable.

---

## 5. Diffing

Picking a second revision replaces the review pane with the two side by side and a list of what
moved.

**The list is computed from the two documents, not from the operations between them.** A log diff
reads plausibly until an undo is in the range, at which point it lists a change and its reversal and
calls that the difference. The documents cannot lie about it: an edit and its own undo compare as no
difference, which is what they are. The operations remain the right account of *what was done*, and
the History dock is where they are read — the two surfaces disagreeing on that is not a bug in
either, it is the two questions being different.

Per node: properties compared key by key (not as one blob — "the properties changed" is not
something anyone can act on), the modifier chain summarised at both ends, slot children counted,
event bindings counted. Screen-level fields are reported apart from the nodes, because they belong
to no node. Values are rendered by the same `displayValue` / `modifierSummary` the History dock
writes, so one change reads the same wherever it is met.

Ordering is by revision number rather than by pick order, so comparing forwards and backwards give
the same answer.

---

## 6. Open questions

- **Restore-to-revision.** The strip can show revision 12 and cannot make the design be revision 12.
  The honest implementation is a *forward* command — a batch that writes the differences the diff
  already computes — rather than a rewind of the document, because a shared design cannot be moved
  backwards under a collaborator. The diff is the ingredient; the command is not built.
- **The peek on a phone.** The strip is composed in the desktop workspace only. The compact layout's
  bottom docks are the place for it, and the review pane needs a different shape there — two
  side-by-side frames at 400 dp is not a comparison anybody can read.
- **Server-side history.** Reaching below the client's floor means asking the server for the
  document at a revision. `get_revision_diff` already returns durable events after a cursor, so the
  data is there; what is missing is a rewind on the service side and a decision about how much
  history a design keeps.
- **A link to a revision.** See [§2](#2-how-it-fits-with-the-versions-view) — deliberately absent for
  now, and the pinned-revision link is the existing answer.
- **Publishing the strip.** A design whose renders reach the delivery branch has both timelines. The
  row that would connect them — "this design's published renders" — is a link the strip does not yet
  carry.
