# Local desktop agents

**Local agent** in the desktop editor uses an installed Codex or Claude Code CLI with the person's
existing local authentication. Opening the panel, discovering executables and reopening history
never send a prompt. **Send** starts a bounded turn; **Stop**, **New session**, changing designs and
closing the view cancel work. Dismissing the dialog leaves a turn running, shown in the toolbar.

The UI offers private advice and drafts. Every turn receives the default design-review prompt,
saved instructions for the current view and a bounded text snapshot of the latest design and open
comments. Responses are plain text. No asset bytes, source URLs, design-server credentials or
project files are passed to the process. Detailed behavior and constraints belong in the agent
prompt and these documents; the UI keeps short action labels.

## Adapter and permission boundary

Both adapters use `ProcessBuilder` with an absolute executable path and fixed argument lists.
Prompts go through stdin, never a shell command or an executable argument. Discovery checks only
absolute PATH entries and the user's `.local/bin` and `.cargo/bin`. Windows discovery accepts native
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

These are trusted native programs running as the user, not an OS sandbox for an arbitrary binary.
A compromised executable, native CLI vulnerability or administrator-managed CLI policy is outside
this app's process boundary. Model text cannot select executables, change arguments, grant access
or execute a UI action. CLI settings restrictions deliberately take precedence over customization;
provider authentication remains the CLI's responsibility. Users should install current native CLIs.
Unsupported CLI flags fail the request; the app never retries with weaker permissions.
Command surfaces were checked with Codex 0.159.0-alpha.3 and Claude Code 2.1.296 using help only.

## Session ownership and recovery

App history and native session references are stored locally under the desktop storage root's
`agents` directory, separately by workspace/file, actor, design and harness. The current harness
resumes only that conversation's UUID, never the CLI's global "last" session. Reopening history
does not start work or monitoring. Switching harnesses stops monitoring and loads its own history.
Preferences in the external handoff are scoped to the current view.

Successful turns save the native session id alongside up to 40 app messages. Failed or canceled
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
