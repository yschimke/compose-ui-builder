# Compose UI Builder: getting started

The UI builder is a separate Compose/Wasm authoring surface. `/ui-builder/` remains the default
`m3-catalog` instance, and explicitly enabled catalogs are also available at
`/ui-builder/<catalog>/`. It does not replace the existing `/wasm/<catalog>/` preview application.
Each design remains pinned to one catalog while the service can host a small operator-selected set.
Publishing a preview catalog never enables authoring for it automatically.

## Create a design in the website

Start the server with UI-builder persistence and open `/ui-builder/`. The website opens its New
design chooser when no design is named. The same chooser is available from **New design** in every
live editor.

1. Choose **Material 3** for a blank Compose screen, or **Remote Material 3** for a Wear widget.
2. For a widget, choose **Small widget** (216×76dp) or **Large widget** (216×124dp).
3. Enter a path-safe design ID and select **Create**. The website retains the current operator
   token and collaboration identity while it opens the catalog-scoped design.
4. For a widget, search for a Column, Row, Box, or Surface and select **Add** (or drag it) to place
   it in the scaffold's single `content` slot. The inserted container stays selected; search for
   Text or other content and select **Add** again to fill its children slot. This container-first
   step is how one widget holds multiple ordered items.
5. Select a node and edit it in Properties. Changes are committed to the live revision
   automatically; Undo, Redo, Duplicate, and Delete use the same collaboration log.

Direct creation URLs remain available for automation and bookmarks:

```text
/ui-builder/remote-m3/?session=live&create=1&template=wear-widget-small&designId=my-remote-screen
```

`create=1` only creates a missing design. It never overwrites an existing `designId`. The `blank`
template is a real, valid document: a `layout/scaffold` root with an empty `layout/box` in its
required content slot. In `remote-m3`, creation starts with the Small Wear widget scaffold instead:
a 216×76dp host frame with an empty content slot. Use `template=wear-widget-large` for the
216×124dp form. These copy the stable 240dp-screen preview contract—200×60dp or 200×108dp content,
8dp host padding, and 26dp corners—without depending on preview-only Glance code. Authentication
credentials are intentionally absent from this example: supply them through the server and client
credential facilities, never in a shared URL, shell history, or process arguments.

Catalog Add actions and drops resolve the concrete compatible slot before submitting the insert.
The selected node, catalog search, inspector mode, and generated operation sequence survive each
authoritative collaboration snapshot. Text, booleans,
catalog choices, bounded numbers, and declared colors are validated locally and then submitted as
the ordinary authoritative `SetProperty` operation. A rejection names the node and field beside
the control. Help returns to this guide. SVG and Compose export use the current committed revision
through the server or MCP adapter.

The canvas uses real catalog components where a supported Compose/Wasm adapter exists. A
compatibility adapter is explicit capability metadata, not a claim that an unavailable platform
API was silently substituted. The Jetcaster supporting-pane scaffold, for example, retains its
semantic component identity while its general adaptive Material adapter remains marked
unsupported. Inspect capability notes before treating a design as portable to another runtime.

Operators select the reviewed adapters with
`--ui-builder-catalogs m3-catalog,remote-m3`. The packaged deployment uses exactly that allowlist;
other served catalogs remain preview-only until added explicitly.
`remote-m3` is a deliberately small adapter: Small and Large Wear widget scaffolds plus Box, Row,
Column, Surface, Text, and nested Remote Compose document. It is not an alias for every M3
capability.

## Compose export needs a component record

The generator emits a call site only where a **discovered component record** proves one can be
written — the component is public, top-level, importable, not an overload collision, its signature
actually recovered — and refuses by name otherwise. A host with no record for a catalog cannot
export it, and says so rather than guessing.

The packaged deployment ships one for `m3-catalog` and installs it automatically; nothing needs to
be set. For a host you start yourself:

```text
--ui-builder-components m3-catalog=<components.json>
```

`components.json` is a preview bundle's own discovery output. The record shipped here covers the
component ids whose Compose mapping is unambiguous; ids it does not cover are **absent rather than
guessed**, so they refuse by name instead of emitting Kotlin that does not compile.

The capability is per catalog. `remote-m3` has no record and is not meant to — Remote Compose is
kept out of the Compose exporter by design — so a host serving both advertises Compose export on
`m3-catalog` and not on `remote-m3`.

## Reading the code a design produces

**Code** in the toolbar opens the Kotlin the export would write, under the canvas, updating on
every edit. It is the export's own output: same generator, same record, same allow-list. A design
the generator cannot express shows the reasons where the source would be, rather than blanking —
those reasons are the actionable half, and they are the same list the Issues tab reports.

**Native** compiles the design and renders it with real Compose on the host, beside the browser's
canvas rather than instead of it — a difference between the two renderers is the thing worth
seeing. It needs a host with a compile lane (`--playground-bundle`); where there is none, the
control is absent rather than present and failing. The render is tagged with each design node id,
so the server's annotation lane can report where every node landed in the frame.

## Property coverage

| Catalog shape | Inspector | Round trip |
| --- | --- | --- |
| `string` | Text input | Preserves an existing encoded value type; new values use `string` |
| `boolean` | Toggle | `bool` |
| `number` / `integer` with editor bounds | Bounded input and step controls | `float` / `int` |
| `allowedValues` | Choice menu | Preserves the existing semantic type, such as `typographyToken` |
| Explicit local color capability | Color/token input | Literal ARGB/RGB colors and declared Material tokens |
| Object, array, nullable unions, or unbounded numbers | Read-only | No unsafe shape guessing |

Text currently covers content, typography style, weight, style, color, font size, line height,
letter spacing, minimum/maximum lines, wrapping, overflow, alignment, decoration, box alignment,
and layout weight. Environment axes—viewport, density, font scale, locale, theme, and layout
direction—are deliberately not node properties.

Existing `size`, `fillMaxWidth`, and `padding` modifiers render and export. Their JSON is visible in
the inspector, but modifier parameter editing is read-only until the released Design API has an
authoritative modifier mutation; the builder does not invent a browser-only operation.

## Connect an MCP agent

The builder is reachable over the server's own `/mcp` endpoint — the same one the catalog tools use,
with the same bearer. Seven tools, one per protocol request plus the native render:

| Tool | Capability | What it answers |
| --- | --- | --- |
| `ui_builder_list_catalogs` | `ui-builder-read` | What a design's `catalogPin` may name |
| `ui_builder_list_designs` | `ui-builder-read` | The designs on this box |
| `ui_builder_get_design` | `ui-builder-read` | One whole document, and the revision to quote next |
| `ui_builder_create_design` | `ui-builder-write` | A design, from a document or copied from one |
| `ui_builder_apply` | `ui-builder-write` | `DesignMutationV1` operations — insert, set, delete, move |
| `ui_builder_export` | `ui-builder-export` | The generator's Kotlin, or its refusals |
| `ui_builder_render_native` | `ui-builder-export` | A frame compiled by real Compose on the host, plus where each node drew on it |

They are absent from `tools/list` on a box that serves no builder, and `ui_builder_render_native` is
absent on one that cannot compile — a client reads what this server can do off the tool list rather
than off a failed call. Replies are the released `McpResponseEnvelopeV1`, except the native render,
which has no request type in the contract and says so in its own description.

A session runs: list catalogs → create or open a design → read its revision → apply mutations →
export. `baseRevision` is how a concurrent edit is detected, so quote the revision you read rather
than guessing it. The command's actor is filled from the presented grant and never from the message.

For a token-gated server, enable agent grants with an operator ceiling containing:

```text
ui-builder-read,ui-builder-write,ui-builder-export
```

The agent should `POST /agent-access/request` with a JSON `capabilities` array containing those
three names, show the returned approval URL and user code to the operator, and poll the returned
poll URL only at the advertised interval. Store the approved bearer in the MCP host's secret or
environment facility. Do not embed the bearer in a URL, checked-in configuration, or command-line
argument. Browser and MCP changes then converge through the same revision log; neither automates
the other's UI.

The UI-builder tools keep their own named read/write/export capabilities, while the catalog tools
beside them use cumulative `preview`/`live` scopes; both are checked per call, off the request the
credential arrived on. [`design/CATALOG_MCP.md`](design/CATALOG_MCP.md) carries the whole surface
and explains why this is one endpoint rather than the sidecar the product spec planned.
