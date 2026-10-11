# UI builder: the trash

Deleting a design used to be immediate and final. An owner's delete, or an agent's delete of a design
it created, now moves the design into a **trash** instead. It stays there, restorable whole, for
`UiBuilderServiceLimits.trashRetentionMillis` (24 hours by default). After that the host purges it.

## What a trashed design is

A trashed design is the same stored design with two extra header fields, `trashedAtEpochMillis` and
`trashedByActorId`. Its document, positions, retained revisions, journal and access list all stay on
disk unchanged. That is what makes a restore lossless: the design comes back at the revision it was
deleted at, with its history and its sharing intact.

In memory the service keeps trashed designs apart from live ones. So every other request (open,
apply, list, export, branch, admin list) sees a trashed design exactly as it saw a deleted one:
**not found**. Only these requests reach the trash:

| Request | Who | Effect |
| --- | --- | --- |
| `DeleteDesign` | the owner, or the delegate that created the design | Moves the design into the trash and closes its streams. The reply carries `restorableUntilEpochMillis`. |
| `RestoreDesign` | whoever could have deleted it | Moves it back, unchanged. |
| `ListTrash` | any actor | Lists the trashed designs this actor could restore, most recently deleted first. |

A trashed design still holds its id. Creating a design with that id is refused until the design is
restored or purged, because creating over it would overwrite the files a restore needs. Trashed
designs also count against `maximumDesigns`.

## Purging

The runtime runs no timer. A host calls `UiBuilderAdminPort.purgeExpiredTrash()` on a schedule. It
removes every trashed design past its retention and returns their ids. The host then drops what it
keeps beside each design (comments, links, reference overlays, reviews). Until a host calls it,
nothing is purged.

`adminListTrash()` shows the operator every trashed design. `adminDeleteDesign` stays permanent: on
a live design it bypasses the trash, and on a trashed design it empties that design out of the trash.

## What stays a hard delete

- **A design the store could not read** (quarantined) is removed immediately. There is no sound
  record to restore.
- **The operator's delete** (`adminDeleteDesign`) is removed immediately.

## Host checklist

A host moving to a runtime with the trash should:

- keep a design's side records (comments, links, overlays, reviews) on `DesignDeleted`, and drop them
  for the ids `purgeExpiredTrash()` returns;
- call `purgeExpiredTrash()` periodically, for example at startup and hourly;
- offer restore and a trash listing to people and agents, for example as MCP tools and in the file
  manager.

`scripts/ui-builder/state-size-report.mjs` reads design directories directly, so it counts trashed
designs as stored designs. They do occupy that disk until purged.
