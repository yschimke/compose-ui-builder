# The editor as an MCP App on a server design

Captured by `scripts/ui-builder-web-smoke/mcp-app-host.mjs`, server-design pass (`?mode=server`):
the fake host advertises no `openai/resource`, opens the editor with `{ designId }`, and answers
`ui_builder_editor_read` / `_write` / `_await` through `tools/call`, as Claude does for a server's
tools (`docs/design/UI_BUILDER_MCP_APP_HOST.md` → *Server designs*).

- `opened.png`: the design read at revision 1; nothing written.
- `saved.png`: after deleting the button, the save `base=1 -> saved r2`; the model context tells
  the agent to change the node with `ui_builder_apply` on the design id.
