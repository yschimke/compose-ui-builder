# Sidecar access control

How the three records that sit *beside* a UI-builder design — the reference overlay, the comment
board and the links record — decide who may read and who may change them.

## The two gates, and what each one settles

A sidecar request passes two checks, and they answer different questions.

The **route capability** (`ui-builder-read` / `ui-builder-write`) is the host's gate. It says
whether this credential may use the UI-builder surface on this server at all. It knows nothing
about any particular design.

The **design's own access control** is the second gate, and it is the one that decides the request.
It is asked through the service, as the calling actor, so a design the actor may not open is
indistinguishable from a design that does not exist.

The distinction matters because the two are not the same permission and never were. An actor can
hold `ui-builder-write` on the host and be shared into a given design as a `VIEWER` — a
repository-authorised browser session, or an agent grant a person approved. Passing the door is not
being an editor.

## Which action each sidecar takes

| Sidecar | Read | Change |
| --- | --- | --- |
| Reference overlay | design `READ` | design `WRITE` |
| Links record | design `READ` | design `WRITE` |
| Comment board | design `READ` | design `READ` |

The overlay and the links record take `WRITE` because changing them is authoring: the frame a
design reproduces, and the issue, pull request and thread it belongs to, are statements about what
the design *is*. A viewer does not make them.

**The comment board deliberately does not**, and that is the interesting row. Its motivating case,
written up in [`UI_BUILDER_COMMENTS.md`](UI_BUILDER_COMMENTS.md), is a designer saying "The play
icon looks like a cross" on a screen an agent is mid-way through editing. That designer is very
often exactly a viewer. Requiring `WRITE` to comment would lock reviewers out of the review
surface, which inverts what the board is for. Being able to see a design is the right bar for
discussing it.

So the rule is not "sidecars follow the design's write bit". It is: **a sidecar takes the action
that matches what changing it means.** Authoring takes `WRITE`; talking about the design takes
`READ`.

## Asking the question

`GetDesignActions` on `UiBuilderServicePort` answers "what may this actor do to this design",
without doing anything and without naming anybody else.

It exists because nothing else could answer it for a grantee. `GetDesignAccess` is owner-only, and
rightly so — the access record names every collaborator, and being shared in is not the power to
read who else was. A snapshot carries `access` only for the owner. So before this, an actor holding
a grant could only discover its own actions by attempting the write, and a caller outside the
operation log had no way at all.

A design the actor may not read answers exactly as a design that is not here does. That is what
makes it safe to expose where `GetDesignAccess` is not: it describes only the caller's own reach,
and tells an actor nothing it could not learn by opening the design.

It has no `ui-builder-protocol` request shape, for the same reason `RenameDesign` and
`DeleteDesign` do not: this is a host asking its own service a question, not a wire message.
