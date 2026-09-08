# UI builder: a store that does not rewrite every design on every keystroke

Where saved designs live, why the one file they live in is at 73% of its ceiling, and the per-design
store that replaces it. Addresses the "related risk" of
[#568](https://github.com/yschimke/compose-preview-server/issues/568) — and most of that issue's
main complaint too, because a store that reads one design at a time cannot be taken down by another
design's bytes.

## The problem is not the ceiling

`FileUiBuilderStateStorage` holds every design in one file, `ui-builder-service-v1.json`, bounded at
`maximumBytes` (32 MiB). On `preview.coo.ee` that file is ~24.5 MB. The obvious reading is that the
ceiling is too low and designs are too big. Both halves are wrong.

**Almost nothing in the file is a design.** `PersistedDesignV1` keeps
`retainedRevisionSnapshots` — 1,025 by default — whole copies of the document in `revisionSnapshots`,
and 1,025 whole copies of the position map in `positionSnapshots`. The live document is one of
those 2,050 copies. Modelling a single 40-node design at full retention with
[`scripts/ui-builder/state-size-report.mjs`](../../scripts/ui-builder/state-size-report.mjs):

```
format ui-builder-persistence-v2  15.13 MB of 32.00 MB (47.3%)  2 designs

by section (all designs)
  revisionSnapshots      12.32 MB   81.4%
  positionSnapshots       2.80 MB   18.5%
  document                0.01 MB    0.1%
  ...

by design (top 2)
  a                          11.47 MB   75.8%  rev 1025  40 nodes  largest: revisionSnapshots (1025 entries)
  b                           3.66 MB   24.2%  rev 1025  12 nodes  largest: revisionSnapshots (1025 entries)
```

A 40-node design serializes to about 9.5 KB and occupies 11.5 MB of the store. Two or three
actively-edited designs reach 24.5 MB with no design being large and no design being wrong. The
store is 99.9% history and it is stored as full copies.

**Every accepted edit rewrites all of it.** `commitPersisted` calls
`storage.replace(encode(candidate, format))` on each applied batch, rename, asset write and
compensation. `encode` builds a `JsonElement` tree of the entire service, walks it into one
canonical string to checksum, then serializes the envelope into a second string. `replace` then
reads the existing file back to write `.backup`, fsyncs it, writes the new file, and fsyncs that.
So one designer nudging one padding value costs, today, at 24.5 MB: two full serializations of every
design plus a whole-tree `JsonElement` in heap, and ~75 MB of file I/O with three fsyncs. It is
`O(everything stored)` per keystroke-scale edit, and the constant is measured in tens of megabytes.

The 32 MiB ceiling is what makes that visible. Raising it makes each edit more expensive and moves
the wall. The fix is to stop writing bytes that did not change and stop reading bytes nobody asked
for.

## The shape

One directory per design, one file per part of it, and nothing loaded until it is read.

```
<ui-builder-state>/
  store.json                       {"format":"ui-builder-store-v3"} and nothing else that can fail
  .ui-builder-service.lock         as today: one advisory lock, one writer
  assets/                          unchanged — already one content-addressed file per digest
  designs/<slug>/
    design.json                    header: id, title, access, catalogPin, revision, lastSequence,
                                   timestamps, retainedFromSequence, checksum
    document.json                  the current document
    positions.json                 the current position map
    revisions/<revision>.json      one retained revision: document + positions, written once
    log/<sequence>.jsonl           appended committed operations, outcomes and audit records
    quarantine.json                present only when this design could not be read or served
```

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
- **The ceiling becomes a budget.** One 32 MiB cliff across all designs is replaced by limits that
  name what they bound: `maximumSerializedDocumentBytes` (already 8 MiB) for `document.json`, a
  per-design byte budget covering `revisions/` and `log/`, and a store-level *soft* limit that warns
  rather than refuses. A design save can no longer fail because a different design grew.

## The port

`UiBuilderStateStorage` is `load(): ByteArray?` and `replace(ByteArray)`. That seam is the design:
it can express nothing except "here is all of it", so every caller above it must materialize
everything. Splitting files under it would be a lie — `load()` would still concatenate them.

The store therefore gets a port that can say which design and which part:

```kotlin
public interface UiBuilderDesignStore {
  /** Every design's header, and nothing else. Cheap enough to call at startup. */
  public fun listHeaders(): List<StoredDesignHeader>

  /** Header, current document and current positions for one design; null when it is not stored. */
  public fun read(designId: String): StoredDesign?

  /** One retained revision, read from its own file; null when it is no longer retained. */
  public fun readRevision(designId: String, revision: Long): RetainedRevision?

  /** Apply exactly the parts [commit] names, atomically for this design. */
  public fun commit(designId: String, commit: DesignCommit)

  public fun remove(designId: String)

  /** Why a design cannot be read or served, recorded rather than thrown. */
  public fun quarantine(designId: String, reason: String)
}

public data class DesignCommit(
  val header: StoredDesignHeader,
  /** Null means unchanged — a rename touches the header and no document. */
  val document: DesignDocumentV1? = null,
  val positions: Map<String, StableNodePositionV1>? = null,
  /** The revision to retain, written to its own file. */
  val retain: RetainedRevision? = null,
  /** Retained revisions below this are unlinked; this is the whole of retention enforcement. */
  val retainFromRevision: Long,
  val appendedOperations: List<CommittedOperationV1> = emptyList(),
  val appendedOutcomes: Map<String, OperationOutcomeRecordV1> = emptyMap(),
  val appendedAudit: List<AuditRecordV1> = emptyList(),
)
```

`PersistentUiBuilderService` keeps its reducer, its single lock and its in-memory `WorkingDesign`
for open designs. What changes is that `persisted: PersistedServiceV1` — one map holding everything
— becomes a header map plus a bounded cache, and `commitPersisted(whole state)` becomes
`store.commit(designId, …)` describing one design's delta. The reducer is not touched, and neither
is a single wire shape.

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
   costs nothing extra. This needed no format work, which is why it went first.

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
   reduction on the section holding 81% of the store, and the file layout above already allows it
   because a revision is already its own file. It is deliberately staged second: replay must
   reproduce the stored document byte-for-byte or `documentHash` and pinned export both lie, and
   proving that deserves its own change with the round-trip test to match.

## Durability

The current file has one property worth keeping exactly: the checksum covers *the payload as
stored*, the parsed tree, never a re-encode of the decoded value. The comment in `decode` explains
what re-encoding cost — `DesignEnvironmentV1.typeface` arriving with a default made every existing
state file unreadable and the release crash-looped. Per-file checksums keep that discipline
verbatim: `design.json`, `document.json` and each `revisions/<r>.json` carry a checksum of their own
stored tree, verified on read, and a field the model gains later is simply absent from the stored
tree and defaulted after.

- **Atomic per file.** Each file is written to a temporary in the same directory, forced, and
  `ATOMIC_MOVE`d, as `FileUiBuilderStateStorage` already does. A commit that touches several files
  orders them so a crash between any two leaves a readable design: the retained revision and the log
  first, then `document.json` and `positions.json`, then `design.json` last. The header names the
  revision, so a header that is behind means the newest revision is on disk but not yet current —
  the load path replays it forward. A header ahead of the document cannot occur.
- **The log is append-only.** Records are one JSON object per line with a length and a CRC; a torn
  tail from a crash is detected and truncated to the last complete record. Nothing rewrites a
  segment; segments roll at a size cap and are unlinked whole once every record in them is below
  `retainFromRevision`.
- **The backup goes away, per design.** The single global `.backup` is what makes today's write read
  and rewrite 24.5 MB to save a copy nobody has ever restored automatically. Retained revisions
  under `revisions/` *are* the previous generations, per design, and `restoreBackup()` becomes
  "make revision r current", which is both a smaller operation and a more useful one. The
  `RecoverableUiBuilderMigrationStorage` pair stays only for the v2 reader.

## Migration

One direction, one shot, never at the same time as anything else:

- If `store.json` exists, the store is v3 and `ui-builder-service-v1.json` is ignored.
- Otherwise, if `ui-builder-service-v1.json` exists, it is read with the existing v1/v2 decoder and
  written out as the tree above, one design at a time; then `store.json` is written last, which is
  what makes the migration atomic. The old file is renamed to `ui-builder-service-v1.json.migrated`
  and never deleted. A design that fails to decode is written as a quarantine and does not stop the
  rest — the same posture #568 asks for.
- Otherwise the store is empty and starts at v3.

Rollback is the old file, still on disk and still valid, plus `--ui-builder-state-dir` pointing at a
copy. An `admin` export that rewrites a v3 tree back into a v2 envelope is worth having for the same
reason `migratePersistenceToLatest()` was, but it is not a startup path: on the evidence of #568,
migration work must never be able to abort `serve`, so the migration above runs inside the same
`runCatching` that disables the lane.

## What to do first

The design above is a change to the store, and the store is at 73% now. In order:

1. **Report before deciding.** `scripts/ui-builder/state-size-report.mjs` against the live
   `/config/ui-builder-state/ui-builder-service-v1.json` says which designs and which sections hold
   the 24.5 MB. Everything below assumes it says what the model above predicts; if it says something
   else — one design with 60 MB of tombstones, say — that is the thing to fix instead.
2. **Cut retention, and warn.** *Done.* Retention is now bounded by count and by bytes (above), and
   the store reports its own headroom: `UiBuilderStateStorage.usage()` says what is held against the
   ceiling that would refuse the next write, `/status.json`'s `uiBuilder` row carries
   `storageBytes`, `storageMaximumBytes` and `storageUsedPercent`, and `serve` prints a warning at
   startup from 80% naming the number, the consequence and the remedy. Both rows are null rather
   than zero when the storage bounds nothing, so an alert can tell "not measured" from a measured
   0%. This is the stopgap; it buys the room to do the rest properly, and it does not shrink a state
   file that is already large — retention applies as designs are edited.
3. **Never let persistence abort `serve`.** The main body of #568, and independent of everything
   here.
4. **Per-design store, then keyframes.** In that order, because the first makes the second cheap and
   the second is the one that needs a byte-for-byte replay proof.

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
