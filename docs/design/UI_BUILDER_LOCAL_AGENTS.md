# Local desktop agents

**Local agent** in the desktop editor uses an installed Codex, Claude Code or OpenCode CLI with the person's
existing local authentication. Opening the panel, discovering executables and reopening history
never send a prompt. **Send** starts a bounded turn; **Stop**, **New session**, changing designs and
closing the view cancel work. Dismissing the dialog leaves a turn running, shown in the toolbar.

The UI offers private advice and drafts. Every turn receives the default design-review prompt,
saved instructions for the current view and a bounded text snapshot of the latest design and open
comments. Responses are plain text. No asset bytes, source URLs, design-server credentials or
project files are passed to the process. Detailed behavior and constraints belong in the agent
prompt and these documents; the UI keeps short action labels.

## Adapter and permission boundary

All adapters use `ProcessBuilder` with an absolute executable path and fixed argument lists.
Prompts go through stdin, never a shell command or an executable argument. Discovery checks only
absolute PATH entries, the user's `.local/bin`, `.cargo/bin` and `.opencode/bin`,
and `/opt/homebrew/bin` and `/usr/local/bin`. The latter two are also added to the child runtime PATH
so Homebrew/npm executables can find Node from a Finder-launched app. Other shell PATH additions
are unavailable when launching from Finder or the Dock; custom executable selection is not yet supported. Windows discovery accepts native
`.exe` executables; shell and npm `.cmd` wrappers are deliberately not launched.

The CLI runs in a separate app-owned directory, never the design file's project directory. Its
environment is restricted to that harness's local login, runtime, locale and network settings. Application and
GitHub tokens, shell startup hooks and arbitrary CLI override variables are excluded. The app
does not read or copy the harness's authentication files.

- Claude Code uses safe/restricted mode, no built-in tools or skills, an empty strict MCP config,
  no user/project settings, plan permissions and automatic denial of permission prompts. Each turn
  has a $1 budget. Safe mode keeps local login available; bare mode would disable OAuth login.
- Codex uses strict config validation, read-only sandboxing, no approval escalation, no user config or rules, and disables
  command execution, code mode, hooks, subagents, apps/plugins, browser/computer use, generation,
  local automation, automatic daemon startup, dependency installation and web search. No MCP grant is injected.
  Read-only sandboxing remains the boundary for any tool a CLI version still exposes.

- OpenCode uses `run --format json --agent compose-chat`, a dedicated primary agent with all
  permissions denied and a single step. User/project configuration is isolated with an app-owned
  config directory, project config is disabled, session sharing and automatic updates are disabled.
  Its native data/auth directory remains available for existing provider logins; no provider key is
  copied into the app or inherited from the application's environment. Custom provider definitions
  in user config are not imported. Model selection uses the CLI default for available native logins. OpenCode's native auth plugins and administrator/remote policies
  remain part of the trusted CLI boundary. An explicit session id resumes only this app's conversation;
  tool events and failed or incomplete results are rejected.

Supported login modes are Codex's standard local login or `OPENAI_API_KEY`, Claude's standard
local login or `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN`/`CLAUDE_CODE_OAUTH_TOKEN`, and OpenCode's
native stored provider logins. `USER` and `LOGNAME` are retained for native account lookup.
Claude Bedrock/Vertex/gateway settings, Codex custom providers and CLI API-key helper scripts
are deliberately not imported. macOS Keychain login has not been validated on a macOS device.

These are trusted native programs running as the user, not an OS sandbox for an arbitrary binary.
A compromised executable, native CLI vulnerability or administrator-managed CLI policy is outside
this app's process boundary. Model text cannot select executables, change arguments, grant access
or execute a UI action. CLI settings restrictions deliberately take precedence over customization;
provider authentication remains the CLI's responsibility. Users should install current native CLIs.
Unsupported CLI flags fail the request; the app never retries with weaker permissions.
Command surfaces were checked with Codex 0.159.0-alpha.3 and Claude Code 2.1.296 using help only.
OpenCode JSON event, stdin, agent and configuration behavior were checked against the source of
[v1.18.35](https://github.com/anomalyco/opencode/tree/v1.18.35). Stable older Codex versions may
reject the newer restriction flags; recognized unknown-option/config failures show “CLI version unsupported” without exposing raw
diagnostics, and ask users to update rather than retrying with weaker permissions. A paid native Codex initial/resume round trip is still unverified;
parser/process tests are not evidence of provider-side resume behavior. The installed Codex parser
accepted the complete exec-level restriction flags before `resume` when invoked with `--help`.

## Session ownership and recovery

App history and native session references are stored locally under the desktop storage root's
`agents` directory, separately by workspace/file, actor, design and harness. The current harness
resumes only that conversation's validated native id (UUID for Claude/Codex, `ses_…` for OpenCode), never the CLI's global "last" session. Reopening history
does not start work or monitoring. Switching harnesses stops monitoring and loads its own history.
Preferences in the external handoff are scoped to the current view.

Successful turns save the native session id alongside up to 40 app messages. Resumed turns send
the design snapshot only when its hash changes; otherwise they say the snapshot is unchanged. Failed or canceled
turns discard their resume reference; the next request starts a new native session from bounded
app history so a partially processed native turn is not silently resumed. **New session** clears
the app's history and reference. Native CLI transcripts remain owned by that CLI and may be retained
under its own settings; the app does not delete them. App history uses ordinary local files with
owner-only permissions where supported, not encrypted storage.

Requests time out after two minutes. stdout and stderr each have a one-megabyte limit; only a
successful final agent message is shown. Native diagnostics and reasoning never become chat
messages. Cancellation terminates the process and its current descendants.

## Comments and background work

The desktop app currently opens offline designs, which have no live comment feed. It offers no
monitoring switch for them. JVM hosts that open `RemoteUiBuilderSession` can opt into the same
integration through `OfflineUiBuilderSessionView(localAgentStore = ...)`, with an origin-specific
private storage namespace. Those sessions use the existing comment feed and controller: reviews
exclude the viewer's own comments, serialize bursts, catch up only after explicit enabling and
pause after five batches. They stay private and never post or resolve a thread.

Monitoring lasts only while the view is open. There is no startup service, detached runner or
persistent agent on the shared server. A background service, scoped MCP tools with native approval
handling, and ACP transport adapters require separate implementation. The common local-host
interface keeps the UI independent of the CLI transport; native adapters avoid requiring Node or
provider credentials in the application. ACP is a possible future transport, not a guarantee that
Claude/Codex login, resume and permission behavior are interchangeable.

## Verification

Tests use fake executable processes, scripted native JSON results and fake UI hosts. They exercise
stdin handling, permission arguments, restricted environment, result parsing, output bounds,
cancellation, timeout, app restoration, native resume and new-session behavior. No test makes a paid
provider request. UI evidence is under `docs/images/local-agent` and is regenerated by
`LocalAgentContentsTest`.
