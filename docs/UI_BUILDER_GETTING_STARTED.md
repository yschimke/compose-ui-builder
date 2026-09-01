# Compose UI Builder: getting started

The UI builder is a separate Compose/Wasm authoring surface at `/ui-builder/`; it does not replace
the existing `/wasm/<catalog>/` preview application. It edits one persistent, revisioned design
through the same Design API used by agents.

## Start from a blank screen

Start the server with UI-builder persistence and open:

```text
/ui-builder/?session=live&create=1&template=blank&designId=my-screen
```

`create=1` only creates a missing design. It never overwrites an existing `designId`. The `blank`
template is a real, valid document: a `layout/scaffold` root with an empty `layout/box` in its
required content slot. The default live template remains the Jetcaster reference. Authentication
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
