# A project's designs

**Status: built (2026-09).** An app keeps the designs it is working on in a conventional directory
in its own repository, the server reads them, and one can be opened from the screen that already
manages the designs a host holds.

## What this is for

An app team leaning on the builder while a screen is still being designed. Somebody prototypes a
screen, exports it into the app's repository beside the code, and the next person — or the next
session, or an agent — opens it here and carries on. The design in the repository is the thing under
review, the thing that survives a container being reclaimed, and the thing a pull request can argue
about. The design on this host is where it is being worked on.

That is the whole point, and it is worth being explicit about what it is *not*: this is not a shelf
of showcase screens shipped alongside a catalog. A design nobody is editing has no reason to be
here, and a directory of designs kept "for reference" is the failure mode to avoid, not the goal. The
convention exists so a design can move between somebody's editor and their repository during the
phase where it changes every day.

## The problem it solves

Before this, a design lived in exactly one place: the mutation log the server writes under
`/config/ui-builder-state`. That is the right home for a design being *edited* — it is the
collaboration record, and the revision log is what makes a concurrent edit detectable — and it is
the only home there was. A team that wanted a design to outlive a session, be reviewed, or be picked
up by somebody else had three options and none of them were good: paste a document into the MCP API,
ask an operator to run a script, or describe the design in a README and let the reader rebuild it.

[`docs/design/fixtures/ui-builder/designs/`](fixtures/ui-builder/designs/README.md) solved half of
it for *this* repository — a design in git, replayed, validated and rendered by the build. It did
nothing for anybody else's project, and nothing at runtime: a design in a file is not a design you
can open.

## The convention

Two paths, wherever a project's designs are read from, so a team moves between the halves of the
loop without rewriting anything:

```text
ui-builder/designs/index.json      the manifest: which designs this project has
ui-builder/designs/<file>.json     one DesignDocumentV1 per design
```

The index:

```json
{
  "schema": "compose-ui-builder-design-index/v1",
  "designs": [
    {
      "id": "checkout",
      "title": "Checkout",
      "file": "checkout.json",
      "description": "Two-step, being reworked for the 4.2 nav"
    }
  ]
}
```

`file` defaults to `<id>.json`, `title` to the id, and `description` is optional.

## The two sources, and which half of the loop each is

- **A directory** (`--ui-builder-designs [<catalog>=]<dir>`) — the app's own checkout. This is the
  prototyping case and the primary one. A design exported into the repository shows up on the next
  read: no publish step, no branch, no commit needed to try something. It is deliberately **not
  cached**, because this is the half that changes under you — somebody exports from the editor and
  expects to see it in the list, not in five minutes.

- **A branch** — a served catalog's `design-artifacts/<system>`, read the same way every other thing
  a catalog contributes is read. This is for designs that have settled enough to travel with the
  catalog, and it needs no per-catalog wiring at all: a catalog registered at runtime through
  `POST /admin/catalogs` brings its designs with it. A branch's index *is* cached, against the
  timestamp of that catalog's last load, which moves exactly when the refresher re-fetches it.

Local directories are listed first. When both a checkout and a catalog offer a design of the same
name, the one on this machine is the one being worked on.

Three decisions behind the shape:

- **A convention, not a registry.** Every other thing a catalog contributes is a path the server
  knows to look for. A project with no designs has no `index.json` — a missing file or a 404,
  whichever source — which is an ordinary answer rather than a condition anybody configures away.

- **Documents, not operation logs.** The published file is a whole `DesignDocumentV1`, the shape
  `CreateDesignRequestV1` takes. The two describe the same design and
  `scripts/ui-builder/design-sync.mjs publish` generates one from the other, but only one can be
  *consumed* here: the reducer that replays an operation log lives in `:ui-builder-export`, which
  `:server` deliberately does not depend on
  ([`UI_BUILDER_PROJECT_BOUNDARY.md`](UI_BUILDER_PROJECT_BOUNDARY.md)). Publishing the document keeps
  that boundary, and keeps the artifact the same shape as the API that receives it.

- **An index rather than a directory listing.** A branch is read through
  `raw.githubusercontent.com`, one path at a time; there is no listing, and adding one would mean a
  second, rate-limited GitHub API. A manifest is one read for any number of designs, and it carries
  the title and description a listing could not. A local directory could be walked, but it uses the
  same manifest so that moving a design from a checkout to a branch changes nothing about it.

## The loop

```shell
# serve the app's own checkout
compose-preview serve --ui-builder-designs ./ui-builder/designs --admin-token …

# open one at /admin/ui-builder, edit it in the builder, then write it back
COMPOSE_PREVIEW_UI_BUILDER_TOKEN=… node scripts/ui-builder/design-sync.mjs export checkout \
  --server http://localhost:8080 --out docs/design/fixtures/ui-builder/designs/checkout.json

# and, for a project that authors operation logs, regenerate the published documents
node scripts/ui-builder/design-sync.mjs publish docs/design/fixtures/ui-builder/designs \
  --out ui-builder/designs
```

`export` writes the operations form, which is what makes a design reviewable in a pull request: it
is a list of inserts in tree order with a hash, so a diff reads as what changed about the screen.
`publish` turns those into the documents the server reads. A project that would rather author
documents directly writes them and an index by hand; nothing requires the generator.

## Reading

[`ServeUiBuilderDesignLibrary`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderDesignLibrary.kt)
holds the reading half. It is given a fetcher — the same one every other branch read goes through,
so a library read is counted and throttled with the rest — and a supplier of the projects to look
in, read at request time rather than captured, because the set changes when a catalog is registered
or retired.

The catalogs searched are the **configured** ones, not the available ones: a catalog that failed its
last load still has the designs it has, and keying the list on load state would make it flicker for
projects whose branches are perfectly readable.

Every read is best-effort per project. An unreachable branch, a missing index, a malformed one or an
index of the wrong schema contributes nothing and takes nothing away from the projects that answer.
Each says so once in the log, because "has something that is not being offered" is otherwise
indistinguishable from "has nothing".

Two things are refused before they become a read or a design id, because they reach a URL path and
then a service call: an id outside `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, and a `file` that is anything
but one flat `*.json` name under the designs directory. A directory source additionally canonicalises
the resolved path and requires it to stay inside the root — the same door, locked twice.

## Browsing and opening

The screen at `/admin/ui-builder` — which already lists every design a host holds — gains a second
table, **From the projects**, driven by two routes behind the same `--admin-token`:

| Route | What it does |
| --- | --- |
| `GET /admin/ui-builder/library` | every design across the projects this host reads, and which projects were searched |
| `POST /admin/ui-builder/library/{system}/{designId}` | read that design's document and create it here |

`catalogsSearched` is on the response so an empty list is legible: nothing searched is a host with no
projects configured, while projects searched and nothing found is a host whose projects have no
designs. The screen says which.

Opening is an ordinary create through the same service the editor writes through, with the same two
guards the create route already applies: a design that already exists is left alone rather than
overwritten — opening twice is a no-op, reported as `alreadyOpen` — and a document is refused unless
this host authors the catalog it pins, because a pin naming a catalog we do not serve produces a
design that cannot render, export or be opened. What is deliberately not re-checked is the
document's shape: the service validates every node against the catalog it resolves, and a second
opinion here could only disagree with the one that counts.

The design is created as `operator:library` rather than as whichever operator pressed the button:
opening is an act of the host, and it should read that way to everyone who then collaborates on it.

The whole library sits behind the admin token because opening a design **writes**. Reading it does
not, and the read half could move out from behind the token on its own if a team wanted the list
public; the write half cannot.

## What this does not do

- **No sync.** Opening copies, and editing here never writes back on its own — `export` is a
  deliberate step somebody takes when the design is worth keeping. That is the same one-way door
  `import` has always had, for the same reason: the mutation log is the record of an edit, and a
  file is a snapshot of one revision. During a prototyping phase that is the behaviour you want; a
  design being edited by two people should not be silently overwritten by a file on disk.
- **No pinning migration.** The opened design carries the `catalogPin` the file carried. A project
  whose designs pin a catalog revision this host no longer serves is refused at open time rather
  than migrated, which is the same answer the editor gives.
- **No staleness policy.** Nothing here decides that a design has rotted. A project that stops
  editing a design and leaves it in the directory keeps offering it; deciding it is finished with is
  the project's call, expressed by deleting the file.
