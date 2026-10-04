# The UI builder, the preview server and Claude Design

This is research, not a plan of record. It covers what Claude Design is as of October 2026, which
of its surfaces a Compose toolchain can plug into, and an ordered list of integrations for this
repository, compose-preview-server and the skills and plugin repositories. The Claude Design side
comes from Anthropic's public announcements and help pages, plus the artifact-type instructions and
tool contracts that a Claude Code session can read. Anthropic does not document some parts, such as
the handoff bundle's format. Those parts are marked as unknown.

[`UI_BUILDER_FIGMA_INTEGRATION.md`](UI_BUILDER_FIGMA_INTEGRATION.md) §"Stitch and Claude Design"
gives the short version: Claude Design has no tree with stable ids, so the integration is agents
issuing builder MCP operations, not a file exchange. This document keeps that position and works
out what to push in, what comes back, and where Claude Code fits.

## What Claude Design is

Anthropic Labs launched Claude Design on 17 April 2026 at `claude.ai/design`. It turns prompts,
uploads, codebases and design files into designs, prototypes, slides and one-pagers, which you
refine by conversation, inline comments, direct edits and generated sliders. In September 2026 it
moved into every Claude conversation, including **Claude Code** and the Artifacts tab, as a set of
artifact types:

| Artifact type | What it holds | Matters here because |
| --- | --- | --- |
| **Design System** | `project/README.md` (brand book), `project/tokens.json`, `project/components/<Comp>/{README.md,preview.html}`, an optional `components/bundle.js` + `bundle.css` + `index.d.ts`, fonts, and uploaded assets | It is what Claude reads before it designs anything. A catalog published in this shape becomes the vocabulary of every design that uses it. |
| **Design** | A canvas of artboards: `project/canvas.json` plus one `project/<name>.dc.html` per artboard, each a self-contained HTML "Design Component" | It is what comes back. A `.dc.html` is HTML with a small templating layer, and it can mount a design system's bundle components by name. |
| Slides, Docs | Decks and living documents | Not relevant beyond the shared design system. |

These are the integration points:

- **`/design-sync` (Claude Code to Claude Design).** A Claude Code command converts a repository's
  design system and uploads it to a Claude Design design-system project. It runs one component at a
  time, behind a plan the user approves. Underneath is a `DesignSync` tool with these calls:
  - `list_projects` and `create_project`;
  - `finalize_plan`, which locks the exact paths to write and the local directory to upload from;
  - `write_files` and `delete_files`;
  - `report_validate`, which reports counts from a `.render-check.json`: `total`, `bad`, `thin`,
    `variantsIdentical` and `iterations`.

  A component's preview card comes from the first line of its preview HTML:
  `<!-- @dsCard group="Actions" height=88 -->`.
- **Handoff to Claude Code (Claude Design to Claude Code).** The export menu has "Send to local
  coding agent" and "Send to Claude Code Web". These package a handoff bundle that carries the
  component structure, the tokens actually used, the layout hierarchy and the assets. **The
  bundle's format is not publicly documented.**
- **Exports and Send to.** PDF, PPTX, ZIP, standalone HTML, and connectors such as Canva, Lovable,
  Vercel, Replit and Miro. A receiving app gets a link, not a file.
- **Plugins and connectors.** Claude Marketplace opened on 23 September 2026. A developer portal
  for submitting MCP connectors and plugin bundles to the Claude directory opened on
  25 September 2026. Anthropic mentions a "Claude Design MCP integration", but there is no public
  API for third-party design-system sources yet.

There are three hard constraints for Compose:

1. **Everything is web.** Previews are HTML documents and the component bundle is one classic
   script that assigns `window.<Namespace>`, with no imports and no network. React 18 is the only
   framework it supplies.
2. **Previews have a locked-down loader.** A preview may load the artifact script CDNs, Google
   Fonts, the system's own files and its uploads, which must be inlined scripts or `/_blob/<id>`
   assets. The allowed asset types are images, video, PDF, fonts and text. `.wasm` is not one of
   them, so a Compose/Wasm renderer is not a supported preview runtime.
3. **The token schema is lists, not DTCG maps.** A token is `{name, value, usage}`, and colors carry
   one value per theme. M3's light and dark color schemes fit this directly.

## What we already have that lines up

| Claude Design needs | We have | Where |
| --- | --- | --- |
| A design system with component names, tokens and previews | Catalogs such as `m3-catalog` and Wear, with a capability contract that includes `colorTokens.roles` | [`UI_BUILDER_CATALOG_CONTRACT.md`](UI_BUILDER_CATALOG_CONTRACT.md) |
| Ground-truth renders of every component state | `render_preview` and `render_matrix` for device, theme and locale, PNG from the real renderer | compose-preview-server `:mcp`, `:server` |
| A sticker-sheet export aimed at design tools | `catalog.json` + `images/`, whose README already says "import into Figma / Stitch / Claude Design" | compose-preview-server `scripts/design-artifacts/render-readme-md.mjs`, `render-index-html.mjs` |
| A way to score an HTML design against Compose | `emit-design-references.mjs` rasterises a Claude Design HTML export at its own viewport for the `/compare` lane; `ui_builder_set_reference` and `compare_reference` | compose-preview-server `scripts/design-artifacts/` and `ServeUiBuilderMcp.kt` |
| An agent-editable design | `.uid` `DesignDocumentV1`, typed operation batches with `baseRevision`, about 50 `ui_builder_*` tools, comments and decisions | This repository; compose-preview-server `ServeUiBuilderMcp.kt` |
| Code out | `export_compose` (Compose, Remote Compose, Wear), SVG, RC, BUNDLE | `:ui-builder-export` |
| An agent handoff from a visual surface | The Connect agent panel, `window.__uiBuilderAgent`, and presence at `/api/ui-builder/v1/designs/<id>/agents` | [`UI_BUILDER_AGENT_HANDOFF.md`](UI_BUILDER_AGENT_HANDOFF.md) |
| Inline UI in chat | MCP Apps: preview viewer, library (with the Designs tab), RC viewer, the editor at `ui://compose-ui-builder/editor` | compose-preview-server `mcp-app/` |
| Plugins for Claude Code, Codex, Cursor, Gemini CLI and Antigravity | `compose-preview` (local MCP, `SessionStart`/`PostToolUse`/`Stop` hooks, `design-reviewer` agent) and `compose-catalogs` (hosted MCP), generated from one `src/plugins.json`; listed in the MCP Registry; Claude plugin directory submission pending | yschimke/compose-ag-plugin |
| Skills that already target Claude Design | `compose-preview-design-board` (an HTML design board for import) and `compose-design-catalog` (a sticker sheet with `tokens.dtcg.json`) | yschimke/skills |

## The integrations, in the order worth doing them

### 1. Publish a catalog as a Claude Design design system

This is the highest value for the least risk, and nothing else on this list works well without it.

Extend what `compose-design-catalog` already emits, not a new tool: today it writes
`tokens.dtcg.json`, which a Claude Design system reads as empty (see the constraints above). Add
the `project/` tree, which `/design-sync` or a plain Artifact publish then uploads:

- **`tokens.json`.** Two themes, `light` and `dark`, from the catalog theme's `ColorScheme` roles
  (`primary`, `on-primary`, `surface-container`, and so on), with values and a `usage` line per
  role. Also the M3 type scale as type groups, shapes as `radius`, and the catalog's spacing if it
  has any. Use the catalog's real values and never invent any, as the type's checklist also
  demands. Set `meta.source` so a re-sync can tell where the tokens came from.
- **`README.md`.** The brand book, generated from the catalog and written for an agent: what the
  components are, which slots and parameters exist, and the rule that a design should only use
  catalog components because it will become Compose.
- **`components/<CatalogId>/README.md`.** Generated from the catalog entry: summary, parameters and
  variants, and the Compose signature as the code users will get.
- **`components/<CatalogId>/preview.html`.** The `@dsCard` line, then the component's
  `render_matrix` PNGs (light and dark, key variants) uploaded as assets and shown with `<img>`.
  These are pixels from the real renderer, so the preview never drifts from Compose.

The directory naming should use the catalog's component ids, so a canvas that cites a component
by name points at a catalog entry.

There are two ways to deliver it:

- **From a developer's Claude Code:** `/design-sync` picks up the emitted tree. Its incremental,
  plan-approved model matches how catalogs change, one component at a time.
- **From CI:** publish to a Design System artifact and mark `lastChange.via` as `CI…`. The type
  then warns editors that a pipeline owns the system. The imports pipeline could do this for each
  imported app, but those are other people's brands, so limit it to the M3 and Wear catalogs and
  apps whose owners opt in.

### 2. No HTML look-alike bundle

An earlier draft proposed a thin `components/bundle.js` of look-alike components, so a canvas
could mount catalog components by name. That breaks rule R1 in compose-ag-plugin's
[`docs/agent-rules.md`](https://github.com/yschimke/compose-ag-plugin/blob/main/docs/agent-rules.md):
an agent never hand-builds an HTML, CSS or SVG mock of a preview and shows it as the UI. The design
system therefore ships no bundle. Its previews are real renders, and a canvas built on it is a
sketch that comes back through point 3, never something presented as the Compose result.

Generating `index.d.ts` from the catalog contract is still worth doing: it is documentation, not a
render, and Claude reads the types to learn the parameter set the builder validates.

### 3. Bring a canvas back as a `.uid`

Add a builder operation source, provisionally an MCP tool `ui_builder_import_dc_html` in
compose-preview-server. It reads a `.dc.html` (or a standalone HTML export) and returns
`apply_design_operations` batches. The tool handles two cases:

- **Components named by catalog id** (in text, `data-` attributes or artboard titles) map onto
  catalog nodes.
- **Free markup** (headings, flex boxes, images) maps to layout primitives, and a warning lists
  anything it could not place. This is the same approach as the Stitch lane in the Figma document.

Set the original artboard as the design's reference
(`ui_builder_set_reference` → `compare_reference`) so the overlay shows the difference. Because a
`.dc.html` has no stable ids, re-import replaces the design. Reconcile it as the Figma lane does,
matching the longest run of siblings still in order, rather than promising true round-trip.

For **Claude Design → Claude Code**, the handoff bundle lands in a session that has the
`compose-preview` skill and the builder MCP. The skill should say what to do with it: create or
update a `.uid` (or edit Compose directly in an existing app), render, compare against the bundle's
screens, then `export_compose`. Until the bundle's format is public, the skill should treat it as
HTML plus screenshots and route it through the importer above.

### 4. Claude Code and Codex as hosts

Most of this exists. compose-ag-plugin already ships `compose-preview` and `compose-catalogs` for
Claude Code and Codex from one `src/plugins.json`. Their hooks are a `SessionStart` summary, a
`PostToolUse` edit reminder and a `Stop` gate, plus a `design-reviewer` agent. Since
[compose-ag-plugin#117](https://github.com/yschimke/compose-ag-plugin/pull/117), the same
marketplace also lists the skill bundles from yschimke/skills. What is left:

- **Finish the Claude plugin directory submission** (compose-ag-plugin #53 and #80).
- **Use Artifacts for anything visual that Claude Code's terminal cannot show.** The terminal does
  not render MCP Apps. Teach the skill to publish its output as a private Artifact page, such as a
  `render_matrix` grid, a before/after `history_diff`, or a `compare_reference` overlay. Only real
  renders go on it (R1). The user can open that page from the CLI, the desktop app or a phone.
- **Make the MCP Apps work in Claude, not only ChatGPT and Codex.** Claude renders MCP Apps on web,
  desktop and mobile (a WebView on mobile, remote MCP only). The library already falls back from
  `openai/files/open` to `ui/open-link`. Check every app against Claude's MCP Apps design
  guidelines (display modes, host style variables, mobile layout). Keep the `openai/` extensions
  in compose-preview-server's `OpenAi*.kt` behind feature detection, the way this repository's
  `McpAppBridge` already separates them.

**Codex** reaches the same tools the other way round. Codex desktop renders MCP Apps, so the
editor opens inside the conversation as the `.uid` file entrypoint
([`UI_BUILDER_MCP_APP_HOST.md`](UI_BUILDER_MCP_APP_HOST.md); the server's `design_open` waits on
compose-preview-server#1236). OpenAI has no design-system import like Claude Design's. Its Product
Design plugin carries work into Figma and Canva, so for Codex users the route to a design tool is
the existing Figma path (design-parity, Code Connect), not anything built for Claude Design. Keep
the export neutral, with real renders, tokens and component docs, and keep thin destination
adapters in the skills.

### 5. Spikes worth one afternoon each

- **The editor as an Artifact.** The web archive is static files. Published as a multi-file Artifact
  (or loaded from an npm package through jsDelivr, which artifacts allow), with the `.uid` as data
  and the `db` capability for comments, the editor could run in claude.ai with no server. Two
  things are unverified: whether an Artifact serves `.wasm` with the right media type, and whether
  its content security policy allows `WebAssembly.instantiateStreaming`. Try it before designing
  anything around it.
- **Remote Compose as a preview runtime.** rc-players already publishes a browser player,
  `@yschimke/remote-compose-player-cmp` (`rc-player/wasm`). It is Compose/Wasm and driven inside an
  iframe, and jsDelivr, which artifacts allow, can serve it. Whether a design system's
  `preview.html` may load Wasm at all is the same unverified question as the spike above. The
  Design canvas forbids `<iframe>`, so this is for design-system previews only.

## What not to do

- Don't emit DTCG token maps: the page reads them as empty.
- Don't try to make Claude Design the editor of record. It has no version history yet, its
  multi-user editing is "basic", and its trees have no ids. The `.uid` and its revision log stay the
  source of truth, and Claude Design is a place to explore and present.
- Don't publish third-party apps' brands from the imports pipeline as design systems.
- Don't put Compose/Wasm into design-system previews before the spike shows the loader allows it.
  PNGs from the real renderer are the safe default.
- Don't hand-build HTML look-alikes of Compose components for a canvas, a Codex Site or anything
  else (R1).

## Open questions

- The handoff bundle's format. If Anthropic documents it, the importer in point 3 should read it
  directly instead of going through HTML.
- Whether `/design-sync` accepts a pre-built `project/` tree from a non-web repository, or always
  converts from source. The `DesignSync` tool takes arbitrary files under an approved local
  directory, which suggests a pre-built tree works.
- Whether the "Claude Design MCP integration" will let a third-party MCP server act as a
  design-system source. If it does, compose-preview-server could serve a catalog live instead of
  through a sync.

## Sources

- [Introducing Claude Design by Anthropic Labs](https://www.anthropic.com/news/claude-design-anthropic-labs)
- [Get started with Claude Design](https://support.claude.com/en/articles/14604416-get-started-with-claude-design)
- [Claude Design now stays on brand for daily work](https://claude.com/blog/claude-design-stays-on-brand-for-daily-work)
- [Build plugins for Claude with the directory submission portal](https://claude.com/blog/build-plugins-for-claude)
- [MCP Apps design guidelines](https://claude.com/docs/connectors/building/mcp-apps/design-guidelines)
- [TechCrunch: Anthropic launches Claude Design](https://techcrunch.com/2026/04/17/anthropic-launches-claude-design-a-new-product-for-creating-quick-visuals/)
- The Design System and Design artifact-type instructions and the `DesignSync` tool contract, as
  served to a Claude Code session in October 2026
