# Designs kept in the repository

One file per design, in the same `compose-ui-builder-operations/v1-candidate` shape as the other
fixtures in the parent directory: a `createDesign` followed by one `insertNode` per node, in tree
order, plus the `expectedDocumentHash` the replay must reproduce. These are the UI builder's own
screens — the editor chrome, its dialogs, docks, menus and the mobile workspace — authored as designs
in the builder's own catalog, so the builder is designed with the builder.

The chrome moves, so these move with it: when a change lands in `UiBuilderEditor.kt` that alters a
screen one of these depicts, the design is updated in the same way it was authored, and the test
below is what stops the two drifting apart quietly.

They are three things at once:

| Consumer | What it does with a file here |
| --- | --- |
| `:ui-builder` `DesignFixturesTest` | replays it, checks the hash, validates every node against the pinned catalog, and requires the Compose export to accept it |
| `:ui-builder` `DesignFixturePreviews.kt` | renders it as a `@Preview` through `UiBuilderSurface`, so `composePreviewRender` and the visual-diff bot see it |
| `scripts/ui-builder/design-sync.mjs` | opens it as a live design on a server, and writes a live design back into this shape |

## Round-tripping with a live server

```shell
# a committed design → a live design on the server (needs a grant with ui-builder-write)
node scripts/ui-builder/design-sync.mjs import docs/design/fixtures/ui-builder/designs/ui-builder-menus.json \
  --server https://preview.coo.ee --design-id ui-builder-menus

# a live design → a committed file (needs ui-builder-read); review the diff, then commit it
node scripts/ui-builder/design-sync.mjs export ui-builder-menus \
  --server https://preview.coo.ee --out docs/design/fixtures/ui-builder/designs/ui-builder-menus.json
```

The bearer comes from `COMPOSE_PREVIEW_UI_BUILDER_TOKEN`, never from an argument, matching the
remote MCP process. Replacing a live design's content in place is deliberately not what `import`
does: a design's revision log is the collaboration record, and a file is a snapshot of one revision.
Import creates; if the id is taken, pass a fresh `--design-id` and let the server keep the history.

## Adding one

1. Author it — in the browser, or through the MCP `ui_builder_*` tools — and `export` it here, or
   write the operations by hand against `m3-catalog-capabilities-v1.json`.
2. Add a `@Preview` for it in `DesignFixturePreviews.kt` with the fixture's own frame size.
3. `./gradlew :ui-builder:jvmTest --tests '*DesignFixturesTest*'` proves it replays, validates and
   exports; `./gradlew :ui-builder:composePreviewRender` draws it.

A file that stops validating is a real finding, not a fixture to patch around: either the design
uses something the catalog no longer declares, or the catalog dropped something a design relies on.
