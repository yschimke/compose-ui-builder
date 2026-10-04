# Agent handoff and presence

The web editor's **Connect agent** action opens the prompt panel. A dismissible invitation appears
until dismissed in this browser or an agent is active; the highlighted toolbar action stays visible.
The panel lists browser viewers and recent agent activity, and offers full setup or minimal prompts.

Prompt preferences are stored on this browser's origin, separate from the design document:
`ui-builder.agent.preferences` stores the setup history, invitation dismissal and general
instructions; `ui-builder.agent.document.<designId>` stores a design override. A blank override uses
the general instructions. Copying a prompt does not claim setup succeeded. Observed agent activity
or the person's explicit **My agent is already set up** choice selects the minimal prompt.

The minimal handoff carries the design URL, MCP server name and endpoint, skill name and public
setup guide. The HTML shell also exposes the endpoint and recovery instructions before Wasm loads;
`window.__uiBuilderAgent` supplies the current design's credential-free handoff after it opens.
Browser credentials never become part of this handoff.

## Host integration

The companion compose-preview-server change exposes
`GET /api/ui-builder/v1/designs/<designId>/agents`, gated by read access to that design and served
with `Cache-Control: no-store`. The payload is `{ "agents": [{ "id": "opaque-id", "name": "MCP agent",
"model": "optional reported model" }] }`. Public readers receive anonymous labels and no model.
The browser polls every ten seconds; a missing, refused or failed endpoint means presence is
unavailable, rather than an empty room. Local documents and pinned revisions do not poll.

The hosted MCP endpoint is stateless. Its roster records authorized tool activity for a design:
participants stay present while a call runs (including bounded comment waits), and for thirty
seconds afterward. It does not promise an idle client is connected. Agent identity is cosmetic:
optional `agentName` and `agentModel` tool arguments supply a reported client/model; neither is an
authorization input. Unknown models are omitted. Presence never changes the document or revision.
Browser presence continues using the existing WebSocket protocol and its expiry rules.

## Browser launch

The VS Code action opens its documented `vscode:mcp/install` URL with a public HTTP MCP endpoint.
It installs connection configuration after VS Code's review. It does not start a task or paste the
prompt. Other clients use the combined setup guide, which links canonical skills from
yschimke/skills and hosted MCP wiring from yschimke/compose-agent-plugins, with separate Claude Code,
Codex, Antigravity and Other paths. A local host's prompt and install link use that host's endpoint.
