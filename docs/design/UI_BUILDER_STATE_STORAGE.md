# UI builder: a store that does not rewrite every design on every keystroke

Where saved designs live, why the one file they live in is at 73% of its ceiling, and the per-design
store that replaces it. Addresses the "related risk" of
[#568](https://github.com/yschimke/compose-preview-server/issues/568) — and most of that issue's
main complaint too, because a store that reads one design at a time cannot be taken down by another
design's bytes.

## The problem is not the ceiling

`FileUiBuilderStateStorage` holds every design in one file, `ui-builder-service-v1.json`, bounded at
`maximumBytes`. On `preview.coo.ee` that file is 23.4 MB, and was at 73% of the 32 MiB ceiling it
had before that ceiling was raised. The obvious reading is that the ceiling is too low and designs
are too big. Both halves are wrong, and so was this document's first answer about which bytes are
to blame.

**The bytes are undo state, not retained revisions.** This section originally argued from a model:
`retainedRevisionSnapshots` was 1,025 whole copies of the document and 1,025 of the position map,
so a handful of long-lived designs had to be the whole store. Running
[`scripts/ui-builder/state-size-report.mjs`](../../scripts/ui-builder/state-size-report.mjs) against
the live file said otherwise — 36 designs, none past revision 20, so retention depth was never the
binding constraint:

```
format compose-preview-ui-builder-service/v2  23.40 MB of 32.00 MB (73.1%)  36 designs

by section (all designs)
  acceptedOperations      7.00 MB   29.9%
  positionSnapshots       4.79 MB   20.5%
  revisionSnapshots       3.38 MB   14.4%
  tombstones              2.58 MB   11.0%
  operationOutcomes       1.82 MB    7.8%
  history                 1.48 MB    6.3%
  positions               1.24 MB    5.3%
  document                1.02 MB    4.3%

by design (top 3)
  claude-home                12.50 MB   53.4%  rev 10  386 nodes  largest: acceptedOperations (4.29 MB, 10 entries)
  agent-welcome               1.51 MB    6.4%  rev 20  55 nodes   largest: acceptedOperations (0.44 MB, 20 entries)
  ui-builder-code-pane        1.26 MB    5.4%  rev 1  219 nodes   largest: acceptedOperations (0.38 MB, 1 entries)
```

Undo bookkeeping — `acceptedOperations`, `tombstones`, `operationOutcomes`, `history` — is **55%**
of the store. The two snapshot lists are 35%. The live documents everyone is actually editing are
**4.3%**.

**One design is half the store, and one operation is 430 KB.** `claude-home` holds 53.4% of the file
across ten accepted operations. The cause is structural: `StructureChangeV1` carries `before` and
`after` as whole `NodeTreeSnapshotV1` subtrees, so a single edit near the root of a 386-node design
stores that subtree twice — and `acceptedOperations` was bounded only by `retainedOperationOutcomes`,
a count of 4,096 with no relation to how large a record is. Four thousand records at 430 KB is a
design that alone exceeds any ceiling. That gap is now closed by `retainedUndoBytes`, a per-design
byte budget on `acceptedOperations` and `tombstones` with a floor of `minimumRetainedUndoOperations`
undo steps, but it is a bound, not a fix: the subtrees are still stored twice per structural edit.

**Every accepted edit rewrites all of it.** `commitPersisted` calls
`storage.replace(encode(candidate, format))` on each applied batch, rename, asset write and
compensation. `encode` builds a `JsonElement` tree of the entire service, walks it into one
canonical string to checksum, then serializes the envelope into a second string. `replace` then
reads the existing file back to write `.backup`, fsyncs it, writes the new file, and fsyncs that.
So one designer nudging one padding value in the smallest of 36 designs costs two full
serializations of all 23.4 MB — `claude-home`'s 12.5 MB included — plus a whole-tree `JsonElement`
in heap, and ~70 MB of file I/O with three fsyncs. It is `O(everything stored)` per keystroke-scale
edit, and the measurement makes it worse than the model did: the store is dominated by one design
that most edits never touch.

The ceiling is what makes that visible. Raising it — which has been done, to 128 MiB — buys room
while the store is rebuilt and makes each edit no cheaper; a bigger file is a bigger rewrite. The
fix is to stop writing bytes that did not change and stop reading bytes nobody asked for.

## The shape

One directory per design, one file per part of it, and nothing loaded until it is read.

```
<ui-builder-state>/
  store.json                       {"format":"ui-builder-store-v3"} and nothing else that can fail
  .ui-builder-service.lock         as today: one advisory lock, one writer
  assets/                          unchanged — already one content-addressed file per digest
  designs/<slug>/
    design.json                    header: id, title, access, catalogPin, revision, lastSequence,
                                   timestamps, retainedFromSequence, and which file each part is
    document-<digest>.json         the current document
    positions-<digest>.json        the current position map
    revisions/<revision>-<digest>.json   one retained revision: document + positions, written once
    journal.jsonl                  appended history, outcomes, undo records and audit
    quarantine.json                present only when this design could not be read or served
```

**Part files are named by the digest of what is in them**, and `design.json` says which name is
current. That is what makes a commit atomic without a second copy of anything: the parts a commit
changes are written under names nothing points at yet, and the header rename is the single moment
the design becomes the new one. A crash before it leaves the previous header pointing at a complete
previous generation; a crash after it leaves files nothing references, which the next open sweeps.
It also makes the diff fall out of the naming — a part whose content did not change has the name it
already had, so there is nothing to write.

`<slug>` is the first 32 hex characters of `sha256(designId)`, not the id. Design ids come from the
protocol artifact and are not promised to be filename-safe; hashing sidesteps path traversal, case-
insensitive filesystems and length limits in one move, and the real id is the first field of
`design.json`. It also keeps a design's directory stable when its title changes.

Four properties follow from the layout, and they are the whole point:

- **A write costs what the edit costs.** An applied batch rewrites `document.json` and
  `positions.json` (one document, ~9.5 KB), writes one new `revisions/<revision>.json`, appends to
  `log/`, and unlinks whatever fell out of retention. Retained revisions are written once and
  deleted by `unlink`, never re-serialized. Nothing belonging to another design is touched. The
  per-edit cost stops depending on how much is stored.
- **A read costs what is read.** Startup parses `store.json` and each `design.json` — a few hundred
  bytes per design — which is all `listDesigns`, access control, catalog pinning and quarantine
  reporting need. A document is parsed on first open of that design and held in a bounded LRU with
  idle eviction. An old revision is read from its own file when `getSnapshot(revision)` or a pinned
  export asks for it, and is not retained afterwards. Resident memory becomes proportional to the
  designs currently open, not to the designs ever saved.
- **A bad design is one design.** Today an unparseable file, a checksum mismatch or an oversize file
  fails the whole load before per-design quarantine can apply, which is the second half of #568.
  With a header per design, a design whose files will not parse gets a `quarantine.json` and is
  reported by `adminUnusableDesigns()`; every other design serves. The only files whose failure can
  still cost more than one design are `store.json` and the directory listing itself, and neither is
  written on an edit.
- **The ceiling becomes a budget.** One whole-store cliff across all designs is replaced by limits that
  name what they bound: `maximumSerializedDocumentBytes` (already 8 MiB) for `document.json`, a
  per-design byte budget covering `revisions/` and `log/`, and a store-level *soft* limit that warns
  rather than refuses. A design save can no longer fail because a different design grew.

## The port

`UiBuilderStateStorage` is `load(): ByteArray?` and `replace(ByteArray)`. That seam is the design:
it can express nothing except "here is all of it", so every caller above it must materialize
everything. Splitting files under it would be a lie — `load()` would still concatenate them.

The store therefore gets a port that can say which design and which part:

```kotlin
internal interface UiBuilderDesignStore {
  /** Every design stored here, read from the headers alone. */
  fun listDesignIds(): List<String>

  /** One design, assembled from its parts; null when it is not stored. */
  fun read(designId: String): StoredDesignRead?

  /**
   * Persist [next], writing only the parts in which it differs from [previous].
   *
   * [previous] is what this store last wrote for the design, or null when it wrote nothing.
   */
  fun commit(designId: String, previous: PersistedDesignV1?, next: PersistedDesignV1)

  fun remove(designId: String)

  /** Why a design cannot be read, recorded beside it rather than thrown. */
  fun quarantine(designId: String, reason: String)

  fun usage(): UiBuilderStorageUsage?
}
```

**The store computes the delta rather than being handed one.** The sketch this replaces had the
caller describe its own commit — appended operations, appended audit, the revision to retain. That
is knowledge every mutation site would have to carry, and there are ten of them: an applied batch, a
compensation, a rename, an asset write, a create, a repair. Each already produces one immutable
`PersistedDesignV1` candidate, and each would have had to produce a second description of how that
candidate differs from the last one — the same fact, spelled twice, in ten places, with the store
unable to tell when the two disagreed.

Diffing costs nothing worth avoiding. The candidate is built with `copy()`, so every part the edit
did not touch is *the same object*, and both `AbstractList.equals` and `AbstractMap.equals` answer
an identical reference in constant time. So `previous.acceptedOperations == next.acceptedOperations`
is a pointer comparison for a rename and a real comparison only for the part that changed — which
is exactly the part about to be written.

### The journal, and why three parts are not files

`document`, `positions` and each retained revision are whole values that a commit replaces, so they
are files named by their digest. The other four — `history`, `audit`, `operationOutcomes`,
`acceptedOperations` and `tombstones` — are collections that grow by one entry per edit and are
trimmed from the front. Writing each as a whole file would put the store back where it started, one
scope smaller: a design holding 4 MiB of undo state would rewrite 4 MiB to record one 400-byte
outcome.

So those go to `journal.jsonl`, one line per change, appended: `historyAppend`, `auditAppend`,
`outcomePut` / `outcomeRemove`, `acceptedPut` / `acceptedRemove`, `tombstonePut` / `tombstoneRemove`,
and a `…Set` record for the case a list is replaced outright rather than extended (an asset write
clears `history`). Replay is in file order, last write wins per key.

Two things keep it bounded and honest:

- **The header says how long the journal is.** `journalBytes` is written in `design.json`, so a
  commit that appended and then died before the header rename leaves a tail that replay never reads.
  The append is not the commit; the header is.
- **It compacts when it stops paying.** Past 256 KiB, and past four times the size of writing the
  live collections out whole, the next commit writes a fresh journal holding the current state and
  unlinks the old one. That bounds a design's journal at roughly four times its live undo state,
  which is the same trade the byte budget already makes for undo itself.

`PersistentUiBuilderService` keeps its reducer, its single lock and its in-memory `WorkingDesign`
for open designs. What changes is that `commitPersisted(whole state)` becomes
`store.commit(designId, previous, next)` for the one design an edit touched. The reducer is not
touched, and neither is a single wire shape.

`UiBuilderStateStorage` stays for the in-memory and test implementations, and for the v2 reader the
migration needs.

## Retained revisions stay a promise, and stop being 1,025 documents

`retainedFromSequence` and `SNAPSHOT_REQUIRED` are protocol: a client that asks for a revision the
service no longer keeps gets a named error and the current revision, and pinned export and
`getSnapshot(revision)` both rely on it. So retention is a contract and the store must keep
answering it — but the contract says how far back, never how the bytes are held.

Two changes, in order of how much they cost to build:

1. **Retain fewer, and by budget.** *Done, ahead of the store.* 1,025 whole documents per design was
   a default nobody chose for the sizes designs actually reach. `UiBuilderServiceLimits` now retains
   at most `retainedRevisionSnapshots` (128) revisions and at most `retainedRevisionBytes` (2 MiB)
   worth of them, whichever binds first, never cutting below `minimumRetainedRevisionSnapshots`
   (32). The depth is measured from the canonical document bytes the commit already hashes, so it
   costs nothing extra. This needed no format work, which is why it went first — and, on the
   measured store, why it changed nothing: no design there is deep enough for either bound to bite.
   It is insurance against a design that keeps being edited, not a remedy for the file today.

   It also moved a floor that used to be quoted loosely. `SNAPSHOT_REQUIRED` raised for a missing
   *revision* now answers with the oldest revision still retained, not with the operation log's
   floor: the two were within one of each other while both were ~1,024, and retaining fewer
   revisions than operations makes the difference real — a client told a floor 900 sequences below
   what is actually retained would ask again for a revision that is still gone, and loop. A delta
   still answers with the operation log's own floor, which is the right one for that question.
2. **Keyframes and deltas.** `ChangeRecordV1` already carries `before` and `after` for every
   property, modifier, state variable, event binding, environment and structural change, which is
   exactly a forward-and-backward delta — it is what undo already replays. So a retained revision
   need not be a document: keep a whole document every 64 revisions and the change records between,
   and reconstruct on read by replaying forward from the nearest keyframe. That is a 30–60×
   reduction on the snapshot sections, and the file layout above already allows it because a
   revision is already its own file. Worth sizing against the measurement first: those sections are
   35% of the live store, not the 81% the original model assumed, so the same technique applied to
   `acceptedOperations` — whose `StructureChangeV1` records are themselves whole subtrees, stored
   twice — is likely the larger prize. It is deliberately staged second: replay must
   reproduce the stored document byte-for-byte or `documentHash` and pinned export both lie, and
   proving that deserves its own change with the round-trip test to match.

## Durability

The current file has one property worth keeping exactly: the checksum covers *the payload as
stored*, the parsed tree, never a re-encode of the decoded value. The comment in `decode` explains
what re-encoding cost — `DesignEnvironmentV1.typeface` arriving with a default made every existing
state file unreadable and the release crash-looped. Per-file checksums keep that discipline
verbatim: `design.json`, each `document-<digest>.json` and each `revisions/<r>-<digest>.json` carry
a checksum of their own stored tree, verified on read, and a field the model gains later is simply
absent from the stored tree and defaulted after.

- **Atomic per design, not merely per file.** Each file is written to a temporary in the same
  directory, forced, and `ATOMIC_MOVE`d, as `FileUiBuilderStateStorage` already does — but a commit
  touching several files needs more than each one landing whole. Because a part file is named by
  the digest of its content, the parts a commit writes are invisible until `design.json` names them,
  and the header lands last. So the ordering is: new parts, then the journal append, then the
  header. A crash anywhere before the header leaves the previous header pointing at a previous
  generation that is complete in every part; a crash after it leaves unreferenced files, which the
  next open of that design unlinks. There is no torn state to replay forward, because nothing
  partially applied is ever reachable.
- **The journal's tail is bounded by the header; its records are checksummed one by one.** Records
  are one JSON object per line — the record, and a checksum of the record as stored — appended
  before the header names their length. Replay reads `journalBytes` and stops, so a partial line
  from a crash, or a complete one from a commit whose header never landed, is never applied; it is
  truncated on the next commit. That answers *which records are committed*, which is a different
  question from *whether the bytes are still the bytes*, and only the checksum answers the second:
  a record that flipped a bit while staying valid JSON would otherwise be replayed as though it had
  been authored, silently changing an outcome, an undo record or a tombstone while every other part
  of the design is checked. Per record rather than a digest over the committed prefix, because
  hashing everything committed on every append is the cost the journal exists to avoid.
- **The backup goes away, per design.** The single global `.backup` is what makes today's write read
  and rewrite all 23.4 MB to save a copy nobody has ever restored automatically. Retained revisions
  under `revisions/` *are* the previous generations, per design, and `restoreBackup()` becomes
  "make revision r current", which is both a smaller operation and a more useful one. The
  `RecoverableUiBuilderMigrationStorage` pair stays only for the v2 reader.
- **A design that cannot be read is quarantined, not thrown.** A missing part, a checksum mismatch
  or a header that will not parse writes `quarantine.json` beside the design and leaves every other
  design serving — the property the single file could not have, because its checksum covered all of
  them at once. Its bytes still count against the store's gauge, because they are still on the disk,
  and `adminDeleteDesign` still retires it: reading a design and retiring it are different
  permissions on different failures, and a quarantine that could not be cleared would be the
  one-way door this mechanism exists to avoid. Downloading or repairing it cannot work, and says so
  — both need the document that would not decode, so `/admin/ui-builder` gives it a row of its own
  (it is not a design in memory and nothing else would list it) and offers only the action that
  works on it. The startup warning sends an operator to that page; a row that was not there, or
  buttons that could not work, would send them nowhere.
- **A design directory is only that design where its name says so.** The slug is the address, not a
  label: a commit and a delete both derive `designs/<slug(designId)>` from the id rather than from
  the directory the design was read out of. So a design restored or copied under some other basename
  — an in-place backup, a directory put back under a name a person can read — is reported like a
  quarantined design rather than served, keyed by the directory it is in so that it stays distinct
  from whichever design legitimately holds that id, and so that it can still be retired by the name
  it has. Served, it would be worse than useless: the next commit would reuse the part names in
  *its* header while writing into the canonical directory, naming files that only exist beside the
  copy, and a delete would take the canonical directory while the copy came back at the next open.

- **A commit that exceeds a design's own budget is refused before the header lands.** The header is
  what makes a generation the design, so a budget checked after it would tell the caller its edit
  failed and hand a restart the edit that failed. A part the header names that cannot be *measured*
  refuses the commit for the same reason and not as free space: a commit reuses the name of every
  part it did not rewrite, so counting a missing one as zero bytes would land a header naming a file
  that has gone — an edit acknowledged here and a design quarantined at the next open.
- **A deleted design is moved into the store's own `designs/.deleted/`, not renamed beside the
  live ones.** The unlink after the rename is cleanup and can be interrupted, so the next open has
  to be able to tell what it is finishing — and no *name* is proof of who wrote it. A tombstone
  named `<directory>.deleted-<millis>` beside the designs made `checkout.deleted-1700000000000`, a
  plausible operator backup, indistinguishable from garbage, in a store that otherwise treats an
  unfamiliar directory as the operator's content. Everything under the reserved directory is the
  store's own, by where it is rather than by what it is called.
- **A quarantine key never names a design that loaded.** A quarantine is reported under the id in
  its header and, where there is no id to read, under the directory holding it — a name an operator
  chose, which can be anything, including the id of a design that loads perfectly well from its own
  directory. On a collision the quarantine yields the name: it is the one of the two with no id of
  its own to insist on, and left colliding it would mark a working design unusable and let a delete
  aimed at the quarantine take it out of the service.
- **Creating a design asks the store for the place, not the id.** A quarantine is not always
  reported under the id it holds: a design whose header will not parse has no id to be read out of
  it and is reported under its directory. Both still occupy the directory an id resolves to, and
  creating into one is how a live design and a stale quarantine end up in the same place — the
  design serves, and retiring the quarantine deletes it.

## Migration

One direction, one shot, never at the same time as anything else:

- If `store.json` exists, the store is v3 and `ui-builder-service-v1.json` is ignored.
- Otherwise, if `ui-builder-service-v1.json` exists, it is read with the existing v1/v2 decoder and
  written out as the tree above, one design at a time; the old file is then renamed to
  `ui-builder-service-v1.json.migrated` — never deleted — and `store.json` is written last, which is
  what makes the migration atomic. The marker goes after the rename because the marker is the commit
  point: it is what makes the next start skip the migration, so a rename that failed after it would
  leave one start reporting a failed migration and disabling the lane while the next start read the
  marker, skipped the migration and served the very same designs — and the disabled-lane warning
  would offer a `.migrated` rollback whose absence was the failure. Ahead of the marker it is a step
  the migration retries from: the parts are named by the digests of their contents, so the retry
  writes the same tree. The migration also refuses to write a file its own reader would refuse: a
  legacy design with a document — or a header, which carries an unbounded access list — over the
  per-design budget is recorded as quarantined with its size and the limit, rather than written out,
  committed under the marker, and quarantined by the very next load. File by file, not by the
  design's total: a design merely past the budget loads, serves and exports and refuses to grow,
  which is what any design that reaches the budget does. The v2 envelope decodes as a whole — one checksum covers every design in it, so
  there is no per-design failure to quarantine at this point; that granularity begins the moment the
  designs are written out separately, which is the next paragraph and every read after it.
- Otherwise the store is empty and starts at v3.

Rollback is the old file, still on disk and still valid, plus `--ui-builder-state-dir` pointing at a
copy. An `admin` export that rewrites a v3 tree back into a v2 envelope is worth having for the same
reason `migratePersistenceToLatest()` was, but it is not a startup path: on the evidence of #568,
migration work must never be able to abort `serve`, so the migration above runs inside the same
`runCatching` that disables the lane.

## What to do first

In order, with the first two done:

1. **Report before deciding.** *Done, and it changed the plan.*
   `scripts/ui-builder/state-size-report.mjs` against the live
   `/config/ui-builder-state/ui-builder-service-v1.json` was run, and it overturned the model this
   document was first written from: the bytes are in undo state, not retained revisions, and one
   design is half the store. The output is quoted at the top. Two things followed — a byte budget on
   `acceptedOperations` and `tombstones`, which is the bound that was actually missing, and a fix to
   the report's own projection line, which had assumed every design sat at full retention depth and
   so offered a 7.66 MB saving that did not exist. Re-run it after any change here; the whole point
   of the step is that the guess and the measurement disagreed.
2. **Cut retention, and warn.** *Done, and it turned out not to be the lever.* Every design on the
   live store is at revision 1-20, far under both the old 1,025 cap and the new 128, so the revision
   cut changes nothing there — it is insurance for a design that accumulates revisions, not a
   remedy for the file as it stands. The undo budget in step 1 is what reaches these bytes.

   The store also reports its own headroom: `UiBuilderStateStorage.usage()` says what is held against the
   ceiling that would refuse the next write, `/status.json`'s `uiBuilder` row carries
   `storageBytes`, `storageMaximumBytes` and `storageUsedPercent`, and `serve` prints a warning at
   startup from 80% naming the number, the consequence and the remedy. Both rows are null rather
   than zero when the storage bounds nothing, so an alert can tell "not measured" from a measured
   0%. This is the stopgap; it buys the room to do the rest properly, and it does not shrink a state
   file that is already large — retention applies as designs are edited.
3. **Never let persistence abort `serve`.** *Done.* The main body of #568. Every failure after the
   argument check — an unwritable state directory, a corrupt or oversize state file, a checksum
   mismatch, a migration that cannot complete — now disables the UI-builder lane and prints a
   warning naming the failure, the file and the three recovery paths, rather than propagating out of
   `main`. `--ui-builder-migrate-state` passed where it cannot apply stays fatal: it is a flag the
   operator typed on this invocation, and skipping it silently would be worse than refusing.

   What is deliberately still missing is the issue's "keep `/admin/ui-builder` reachable enough to
   diagnose": with no service there is nothing for those routes to talk to, and a degraded admin
   surface is its own piece of work. The warning carries the diagnosis instead.
4. **Per-design store, then keyframes.** In that order, because the first makes the second cheap and
   the second is the one that needs a byte-for-byte replay proof.

   *The store is done.* `UiBuilderDesignStateStore.open(<state dir>)` is what `serve` now opens, a
   v2 file beside it is migrated once and renamed, and `PersistentUiBuilderService` commits one
   design at a time. What did **not** land with it, deliberately: the design map is still read whole
   at startup and held in memory. The layout is what makes lazy reads possible — a document is its
   own file — but the read path has a question the store cannot answer on its own: `unusableDesigns`
   is computed today by validating **every** stored document against its catalog at startup, and a
   host that stops reading documents it was not asked for stops knowing that answer until somebody
   asks. Whether `adminUnusableDesigns()` should then mean "known to be unusable so far" or should
   keep costing a full startup pass is a decision about an admin surface, not about a file layout,
   and it deserves its own change.

   The keyframe half is unchanged and still second.

## What this deliberately does not do

- **No database.** A directory of files keeps the property that an operator can read, copy, diff and
  `scp` the state, which has been the recovery path for every incident so far.
- **No multi-writer.** The store stays single-writer behind one advisory lock; the concurrency note
  on `FileUiBuilderStateStorage` still holds, and a deployment needing concurrent writers still
  supplies its own implementation. Per-design files make that possible later — per-design locks are
  a natural next step — but changing the concurrency model at the same time as the layout would make
  both unreviewable.
- **No change to assets.** `FileUiBuilderAssetStore` is already one content-addressed file per
  digest, which is the shape argued for here; see
  [`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md).
- **No protocol change.** No wire shape, no error code and no client behaviour changes.
  `retainedFromSequence` moves faster under a smaller retention budget, which is what it exists to
  say.
