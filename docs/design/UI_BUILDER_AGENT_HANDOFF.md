# Agent handoff and presence

The desktop editor offers [local Claude Code, Codex and OpenCode sessions](UI_BUILDER_LOCAL_AGENTS.md).
Those use installed harnesses and local authentication; no provider token goes to the design host.
The shared JVM view can opt into the same adapter for remote designs with a live comment feed.

The web editor's **Connect agent** action opens browser-owned chat when the host offers it, with an
**External agent** tab for the prompt panel. [Browser chat](UI_BUILDER_BROWSER_CHAT.md) keeps provider
credentials and conversation history in the browser and offers opt-in comment monitoring while
the page is open. One toolbar control owns connection and activity; there is no separate invitation
bar above the workspace. Sharing also lives in the main toolbar: a **Private**/**Public** control
with the full access rule in its tooltip. At compact widths a visibility icon stays beside the title,
and **More → Sharing** opens the same host-owned sharing page. These controls replace the former
sharing strip; access, revision and error notices retain their existing status strip.

The agent control shows a recent agent's reported name, or **OpenRouter** for browser chat,
with an extra-participant count when both are available. Browser work and monitoring identify
OpenRouter explicitly; tooltips and accessibility descriptions include reported model names and
browser chat's selected model. Unavailable activity is distinct from a known empty roster.
Clicking a named external agent opens its activity/handoff tab; ongoing browser work opens chat.
The panel lists browser viewers and recent agent activity, and offers full setup or minimal prompts.

Prompt preferences are stored on this browser's origin, separate from the design document:
`ui-builder.agent.preferences` stores the setup history, legacy invitation dismissal and general
instructions; `ui-builder.agent.document.<designId>` stores a design override. A blank override uses
the general instructions. Copying a prompt does not claim setup succeeded. Observed agent activity
selects the minimal prompt; **Include setup** controls whether setup steps are copied.

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
