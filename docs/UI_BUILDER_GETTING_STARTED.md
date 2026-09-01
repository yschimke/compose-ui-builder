# Compose UI Builder: getting started

The UI builder is a separate Compose/Wasm authoring surface. `/ui-builder/` remains the default
`m3-catalog` instance, and explicitly enabled catalogs are also available at
`/ui-builder/<catalog>/`. It does not replace the existing `/wasm/<catalog>/` preview application.
Each design remains pinned to one catalog while the service can host a small operator-selected set.
Publishing a preview catalog never enables authoring for it automatically.

## Start from a blank screen

Start the server with UI-builder persistence and open:

```text
/ui-builder/?session=live&create=1&template=blank&designId=my-screen
```

For the Remote Compose M3 instance, use a distinct design id under:

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

1. Select the scaffold or content box in Layers.
2. Search the M3 component catalog, drag a component, and release it over the canvas. The catalog
   resolves the concrete compatible slot before submitting the insert.
3. Select a node and use Properties. Text, booleans, catalog choices, bounded numbers, and declared
   colors are validated locally and then submitted as the ordinary authoritative `SetProperty`
   operation. A rejection names the node and field beside the control.
4. Use Undo, Redo, Duplicate, and Delete in the toolbar. Help returns to this guide.
5. Export SVG or Compose from the current committed revision through the server or MCP adapter.

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

The MCP executable remains a thin client in `compose-ai-tools`; see its
[MCP reference](https://github.com/yschimke/compose-ai-tools/blob/main/mcp/README.md) and the
[UI-builder tool contract](design/UI_BUILDER_PRODUCT_SPEC.md#mcp-surface). It exposes
`create_design`, `open_design`, `list_components`, `apply_design_operations`, `render_design`,
`export_svg`, `export_compose`, and `get_revision_diff` over the same persistent session.

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
