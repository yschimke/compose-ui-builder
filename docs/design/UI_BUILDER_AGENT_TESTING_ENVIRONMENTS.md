# Compose UI Builder: Agent Testing Environments

This document describes how agents can test the UI Builder in different environments, the capabilities and limitations of each, and the infrastructure needed for cloud-based agent testing.

## Overview

Agents can test and interact with the UI Builder through four primary testing environments:

1. **preview.coo.ee** — The public, production-like compose-preview-server instance
2. **Local Desktop Server** — The native Compose Desktop application
3. **Local Wasm Server** — A local HTTP server serving the Wasm-compiled web editor
4. **Gradle Tests** — Unit and integration tests compiled into the Gradle build

## 1. preview.coo.ee (Public Cloud Host)

### What It Is

A publicly accessible instance of `compose-preview-server` running at `https://preview.coo.ee`. The UI Builder at `/ui-builder/` hosts the web editor, the design state directory, the MCP endpoint at `/mcp`, and the native preview render lane.

### Access and Capabilities

**Hosted services:**
- **Web editor** at `/ui-builder/`
- **Design API** at `/api/ui-builder/v1/designs/<designId>/...`
- **MCP tools** at `/mcp` for programmatic design manipulation
- **Export** via `/api/ui-builder/v1/designs/<designId>/export.{svg,png}`
- **Native preview compilation** for designs (Java 21 available)
- **Collaboration** — multiple actors reading and writing the same design in real time
- **Comments** — **Talk** feature for design discussion threads

**Packaged catalogs:**
- `m3-catalog` — Material 3 for phones
- `remote-m3` — Remote Compose for Wear widgets
- `wear-m3` — Wear Material 3 for watch screens

**Who can access:**
- Browser access is public, but designs are private to their owner
- Agents act under time-limited grants from the approved operator — `ui-builder-read`, `ui-builder-write`, `ui-builder-export`
- Agents request their own grants through `/agent-access/request` with a human-in-the-loop approval flow

### Limits

- **No local state mutation** — designs exist on the server; no offline editing
- **No credential storage** — the operator's token remains in the cloud environment
- **Network required** — every read/write hits the server
- **Bounded concurrency** — the server's rendering capacity limits simultaneous exports
- **No debugger access** — cannot inspect the server's internal state or logs
- **Rate limited** — the server enforces request rates to protect infrastructure

### Using the MCP Tools

Agents can call the MCP tools directly through Claude Code's MCP support. The tools are available on the server's `/mcp` endpoint. See the tool list below for the full set of capabilities.

### When to Use

- **Integration testing** against the production system
- **User acceptance testing** — a live, collaborative environment where humans and agents can work together
- **Long-running designs** that should survive server restarts and be accessible across sessions
- **Testing collaboration and comments** — the only environment with these features
- **Testing full export pipeline** including native preview compilation

### MCP Tools Available

| Tool | Capability | Use Case |
| --- | --- | --- |
| `ui_builder_list_catalogs` | `ui-builder-read` | Discover available catalogs and their capabilities |
| `ui_builder_list_designs` | `ui-builder-read` | Enumerate existing designs |
| `ui_builder_get_design` | `ui-builder-read` | Fetch a design and its current revision |
| `ui_builder_await_design` | `ui-builder-read` | Wait for another actor to mutate a design |
| `ui_builder_create_design` | `ui-builder-write` | Create a new design |
| `ui_builder_apply` | `ui-builder-write` | Apply mutations (insert, set, delete, move nodes) |
| `ui_builder_export` | `ui-builder-export` | Generate Kotlin from a design |
| `ui_builder_put_asset` | `ui-builder-write` | Upload an image asset for `asset/image` nodes |
| `ui_builder_render_native` | `ui-builder-export` | Compile and render via the native preview lane |
| `ui_builder_list_comments` | `ui-builder-read` | Read design discussion threads |
| `ui_builder_await_comments` | `ui-builder-read` | Wait for new comments |
| `ui_builder_post_comment` | `ui-builder-write` | Reply to or start a discussion |
| `ui_builder_design_access` | `ui-builder-read` | List who can access the design |
| `ui_builder_share_design` | `ui-builder-write` | Grant read/write access to collaborators |
| `ui_builder_rename_design` | `ui-builder-write` | Rename a design |
| `ui_builder_delete_design` | `ui-builder-write` | Delete a design (owner only) |

See `/ui-builder/` → **Connect an MCP agent** for full details and the OpenCode config example.

---

## 2. Local Desktop Server

### What It Is

A native Compose Desktop application (`jvm` desktop Skiko backend) that runs offline. The editor and the canvas both render through Skiko, with no browser or HTTP server needed. Designs are persisted locally under `~/.compose-preview/ui-builder-desktop`.

### Building and Running

```bash
# Build and launch the desktop app
./gradlew :ui-builder-desktop:run

# Against a specific catalog
./gradlew :ui-builder-desktop:run --args='--catalog remote-m3'

# With a template
./gradlew :ui-builder-desktop:run --args='--catalog remote-m3 --template weather-widget'

# Against a remote server's preview compilation
./gradlew :ui-builder-desktop:run --args='--server https://preview.coo.ee'

# Open a checked-in design file
./gradlew :ui-builder-desktop:run --args="$PWD/docs/design/fixtures/ui-builder/state-actions.uid"

# Combination
./gradlew :ui-builder-desktop:run --args='--catalog remote-m3 --server https://preview.coo.ee'
```

### Capabilities

- **Full offline editing** — no network needed, all state is local
- **Preview compilation** (optional) — can connect to a remote server for the native Preview pane
- **Native Skiko rendering** — the same rendering stack as deployed Android/iOS (no Wasm simulation)
- **Direct file editing** — open, edit, and save `.uid` or `DesignDocumentV1.json` files to disk
- **Templated startup** — open preset templates for each catalog
- **Device approval flow** — first preview request opens a browser to approve capabilities

### Limits

- **Offline only by default** — designs exist only on the local machine
- **No collaboration** — cannot be shared or accessed by other actors (unless a preview server is connected)
- **No comments** — the **Talk** feature is server-only
- **Native Preview requires server** — to render against real Compose, a server with Java 21 is needed
- **Single-user** — no real-time synchronization with other editors
- **Desktop window management** — basic, no IDE integration

### File Locations

- **Workspace**: `~/.compose-preview/ui-builder-desktop`
- **Per-catalog**: `~/.compose-preview/ui-builder-desktop/<catalog-id>/`
- **Per-template**: `~/.compose-preview/ui-builder-desktop/<catalog-id>/<template-id>/`

### Agent Workflow

Agents can automate the desktop app by:
1. Launching it with specific arguments
2. Writing design files to disk
3. Opening those files via the `--args` flag
4. Monitoring the output directory for saved changes
5. Spawning it headless for batch operations (where display is not needed)

**Example:**
```bash
# Write a design JSON
cat > /tmp/my-design.uid << 'JSON'
{ ... design document ... }
JSON

# Open it in the editor
./gradlew :ui-builder-desktop:run --args="/tmp/my-design.uid"

# Editor saves changes back to /tmp/my-design.uid on every mutation
# Agent can watch the file and react to changes
```

### When to Use

- **Offline testing** where no server is available
- **Local development** of the editor or canvas
- **Integration with a code project** — open a design file from the repo, edit, save back
- **Batch operations** on checked-in design fixtures
- **Headless rendering** (where Skiko is available and display can be suppressed)
- **Testing canvas frame performance** (see the CI job `canvasFrameBenchmark`)

### Testing with Desktop

The CI job `ui-builder:canvasFrameBenchmark` measures edit-to-canvas latency on the JVM desktop backend:

```bash
./gradlew :ui-builder:canvasFrameBenchmark
```

Results are saved to `ui-builder/build/reports/ui-builder/canvas-frame-times.json`.

---

## 3. Local Wasm Server

### What It Is

The UI Builder compiled to WebAssembly (Kotlin/Wasm + Skiko on WebGL) and served over HTTP. A browser renders the Wasm module and a JavaScript bridge connects it to the HTTP API. Designs are persisted in the server's state directory (usually in-memory or a temp directory for testing).

### Building and Running

#### Step 1: Build the Wasm frontend

```bash
./gradlew :ui-builder:wasmFrontendDist
# Outputs to: ui-builder/build/wasmDist/
```

#### Step 2a: Serve with the compose-preview-server (recommended)

```bash
# Standalone, no project needed
compose-preview-server ui --no-project

# Or with your project
compose-preview-server ui --module app

# Or with just the packaged catalogs
compose-preview-server ui --no-project --ui-builder-dir ./ui-builder/build/wasmDist
```

See the **Running it locally** section in `docs/UI_BUILDER_GETTING_STARTED.md`.

#### Step 2b: Serve standalone (for testing only)

```bash
# Using Python's built-in server (testing only, not for production)
cd ui-builder/build/wasmDist
python3 -m http.server 8080
```

Then open `http://localhost:8080/index.html` in a browser.

### Capabilities

- **Wasm editor** — full Compose/Wasm + Skiko rendering in the browser
- **HTTP API** — the same REST endpoints as the full server
- **WebGL Skiko** — 2D rendering through the browser's WebGL
- **Offline-capable** — if the server also provides a state directory, designs can persist
- **Browser dev tools** — JavaScript console, network inspection, profiling

### Limits

- **No native preview** — the Wasm canvas is the only renderer (no Android/Robolectric rendering)
- **WebGL required** — needs a browser with hardware acceleration
- **No collaborative features** — talk/comments only work with a full compose-preview-server instance
- **Single-page application** — all state is in the browser's session until explicitly exported
- **Slower rendering** — Wasm + WebGL is slower than native desktop Skiko
- **Mobile browsers** — WebGL on mobile may be limited or unavailable

### Testing Approach

The CI job `ui-builder-web-smoke` tests the shipped Wasm editor:

```bash
# In scripts/ui-builder-web-smoke/
./gradlew :ui-builder:wasmFrontendDist
npm ci
npx playwright install --with-deps chromium
node smoke.mjs ../../ui-builder/build/wasmDist ../../build/web-smoke
```

This uses **Playwright** to open the Wasm editor in a headless browser and verify that the page loads and renders without errors.

### Agent Workflow

Agents can test the Wasm server using:
- **Playwright** (recommended for browser automation)
- **Puppeteer** (alternative browser automation)
- **Raw HTTP** to the `/api/ui-builder/v1/` endpoints
- **JavaScript evaluation** to inspect DOM and component state

**Example:**
```bash
# Start the server in the background
./gradlew :ui-builder:wasmFrontendDist &
python3 -m http.server 8080 -d ui-builder/build/wasmDist &

# Use Playwright to interact with it
node -e "
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  await page.goto('http://localhost:8080');
  await page.screenshot({ path: 'screenshot.png' });
  await browser.close();
})();
"
```

### When to Use

- **Browser smoke testing** — verify the page loads and renders
- **Wasm module validation** — test that the compiled Wasm runs in different browsers
- **API testing** — exercise the HTTP endpoints without browser complexity
- **CI/CD validation** — part of the build pipeline to catch Wasm build issues early

---

## 4. Gradle Tests

### What It Is

Unit and integration tests compiled into the Gradle build. Test source lives under `src/*/Test` directories in each module.

| Module | Test Directory | Scope |
| --- | --- | --- |
| `:ui-builder` | `src/jvmTest`, `src/commonTest`, `src/wasmJsTest` | Editor model, operations, exporters |
| `:ui-builder-export` | `src/jvmTest`, `src/commonTest` | Design→Kotlin projection, Remote Compose generation |
| `:ui-builder-renderer-sdk` | `src/commonTest` | Sandbox host and catalog protocol |
| `:ui-builder-host-jvm` | `src/jvmTest` | Desktop app host layer |
| `:ui-builder-desktop` | `src/jvmTest` | Desktop window and File menu |
| `:ui-builder-runtime` | `src/test` | Design service, revision pinning |
| `:ui-builder-intellij-plugin` | `src/integrationTest` | IntelliJ Platform integration |

### Running Tests

```bash
# All tests
./gradlew check

# Single module
./gradlew :ui-builder:test
./gradlew :ui-builder-export:test
./gradlew :ui-builder-runtime:test

# JVM tests only (faster)
./gradlew :ui-builder:jvmTest
./gradlew :ui-builder-export:jvmTest

# Watch mode (if supported by your Gradle wrapper)
./gradlew :ui-builder:test --watch
```

### Test Modules

#### `:ui-builder` Tests

**JVM tests** (`src/jvmTest`):
- Editor reducer and operations
- Canvas frame layout
- Compose export generation
- SVG export
- Design fixture replay (`.uid` files)
- Component palette integration

**Common tests** (`src/commonTest`):
- Shared model logic
- Multiplatform data structures

**Wasm tests** (`src/wasmJsTest`):
- Wasm-compiled module sanity checks
- Browser-specific behavior

**Example:**
```kotlin
// Test a mutation
val design = createBlankDesign()
val designAfter = design.applyMutation(InsertNode(...))
assertEquals(1, designAfter.root.children.size)

// Test export
val kotlin = exportDesign(design)
assertContains(kotlin, "@Composable")

// Test canvas
val frame = measureCanvas(design, frameSize = 1080x1920)
assertTrue(frame.isValid)
```

#### `:ui-builder-export` Tests

Tests the design→screen-model projection:
- Record-driven Compose code generation
- Remote Compose widget generation
- Wear Material 3 code generation
- Icon picker integration
- Component pack projection
- Refusal cases (unsupported components, missing properties)

#### `:ui-builder-runtime` Tests

Tests the design service that compose-preview-server depends on:
- Revision history and pinning
- Concurrent edits and merging
- Catalog validation
- Export projection

#### Plugin/Desktop Tests

**IntelliJ Plugin** (`src/integrationTest`):
- IDE tool window rendering
- File handling and project integration

**Desktop** (`src/jvmTest`):
- File menu operations
- Window management
- Native preview token flow

### Limits

- **No server dependencies** — tests run offline
- **Mocks where needed** — expensive operations (network, rendering) are stubbed
- **Unit/integration mix** — some are true units, others use real Compose rendering on JVM
- **No collaboration** — comment and concurrency features are server-only
- **Fast feedback** — tests run in seconds, not minutes

### Key Test Patterns

**Design fixtures:**
```bash
# Replay a checked-in design and verify exports
./gradlew :ui-builder:test -k DesignFixturesTest
```

**Equivalence gates:**
Tests that the canvas and the native preview render the same output.

```bash
# Catalog-level checks
.github/scripts/test-ui-builder-equivalence.sh

# m3-catalog, remote-m3, wear-m3 parity
.github/scripts/ui-builder-equivalence.sh --policy ... --golden ... --strict
```

**Component record validation:**
Tests that discovered component metadata matches the catalog's actual API.

### When to Use

- **Before a commit** — run `./gradlew check` to catch regressions locally
- **CI/CD** — the full test suite is part of every pull request
- **Test-driven development** — write tests alongside code
- **Regression detection** — design fixtures are kept in the repo to test against future changes
- **Performance benchmarking** — the `canvasFrameBenchmark` measures frame times

### Scripts for Agent Testing

The repository includes Node.js scripts for testing and replay:

| Script | Purpose |
| --- | --- |
| `scripts/ui-builder/design-sync.mjs` | Move designs between server and checked-in files |
| `scripts/ui-builder/replay-candidate.mjs` | Simulate agent edits from a design state |
| `scripts/ui-builder/state-size-report.mjs` | Measure design document size overhead |
| `scripts/ui-builder-web-smoke/smoke.mjs` | Browser smoke test the Wasm editor |

**Example:**
```bash
# Download a design from preview.coo.ee
COMPOSE_PREVIEW_UI_BUILDER_TOKEN=... node scripts/ui-builder/design-sync.mjs export my-widget \
  --server https://preview.coo.ee \
  --out docs/design/fixtures/ui-builder/designs/my-widget.json

# Upload it back
COMPOSE_PREVIEW_UI_BUILDER_TOKEN=... node scripts/ui-builder/design-sync.mjs import my-widget \
  --server https://preview.coo.ee \
  --file docs/design/fixtures/ui-builder/designs/my-widget.json
```

---

## Testing Strategy for Agents

### Recommended Approach: Multi-Environment Testing

**Use all four environments together:**

1. **Gradle tests** for regression and fixture validation
   - Fast, reproducible, catches regressions early
   - Run `./gradlew check` before commits

2. **Local Wasm server** for browser testing
   - Test the UI in a real browser
   - Use Playwright for automated browser testing
   - Catch WebGL/Skiko issues early

3. **Local Desktop app** for offline editing workflows
   - Test file I/O and design portability
   - Verify native Skiko rendering
   - Test against checked-in `.uid` fixtures

4. **preview.coo.ee** for integration and collaboration
   - Test against the production system
   - Use MCP tools for design manipulation
   - Test collaboration features with multiple actors

---

## Summary: Which Environment to Use?

| Use Case | Best Environment | Runner | Notes |
| --- | --- | --- | --- |
| Local development | Desktop app or Wasm server | `./gradlew` | Full control, fast feedback |
| Integration with codebase | Desktop app | `./gradlew` | Open checked-in `.uid` files |
| Batch operations | Gradle tests | `./gradlew check` | Automated, reproducible |
| Collaborative testing | preview.coo.ee | Browser or MCP agent | Multiple actors, real time |
| Cloud agent automation | preview.coo.ee + Playwright | Agent in cloud | MCP tools + browser testing |
| Browser compatibility | Wasm server or preview.coo.ee | Playwright or Puppeteer | Test across browsers |
| Native rendering accuracy | preview.coo.ee Native Preview | MCP `ui_builder_render_native` | Real Compose/Android rendering |
| Wear/Remote Compose testing | preview.coo.ee | MCP tools or Wasm server | The only way to test Wear |
| Performance benchmarking | Gradle `canvasFrameBenchmark` | `./gradlew` | Frame times, latency |
| Design portability | All environments | Same design ID or file | Export/import `.uid` files |

---

## Testing from Claude Code Cloud Sessions

Cloud sessions can run all four testing environments **if the repository is available**. Repositories can be provided via:

- **GitHub clone** — Automatic if GitHub App is installed on the repo
- **Local upload** — Upload your repository when GitHub isn't available (up to 100 MB, excludes `.env`, `*.pem`, `id_rsa`)

### Cloud Session Capabilities

Pre-installed tools and services:
- **Java 21** with Maven and Gradle ✅
- **Node.js** (versions 20, 21, 22) ✅
- **Docker** (full engine with compose) ✅
- **Python 3.x** with pip/poetry ✅
- All package managers (npm, yarn, cargo, etc.) ✅

**Network access:** Default "Trusted" level includes package registries, cloud platforms, container registries, GitHub, and dev tools. Customize with additional domains if needed.

**Resource limits:** 4 vCPU, 16 GB RAM, 30 GB disk, up to 30-minute command timeout.

### What Cloud Sessions Can Test

**With repository access, cloud sessions can run all four testing methods:**

1. **Gradle tests** ✅
   ```bash
   ./gradlew check
   ./gradlew :ui-builder:test
   ```

2. **Local Wasm server** ✅
   ```bash
   ./gradlew :ui-builder:wasmFrontendDist
   python3 -m http.server 8080 -d ui-builder/build/wasmDist
   ```

3. **Local Desktop app** ✅
   ```bash
   ./gradlew :ui-builder-desktop:run
   ```

4. **preview.coo.ee via MCP and Playwright** ✅
   - Use MCP tools for design manipulation
   - Use Playwright for UI testing

### What Cloud Sessions Cannot Do

- ❌ Access files on your local machine (only the repository)
- ❌ Use interactive authentication (browser-based SSO login)
- ❌ Access localhost services on your machine
- ❌ Access local network resources directly

### Recommended Cloud Testing Pattern

**Multi-environment approach (same as local):**

1. Run Gradle tests for regression detection
2. Build and test the Wasm server with Playwright
3. Build and run the Desktop app for offline workflows
4. Use preview.coo.ee for integration testing with MCP tools

**For CI/Scheduled Agents:**

Cloud sessions are ideal for:
- Running `./gradlew check` on every PR or schedule
- Building and testing Wasm editor in headless mode
- Running design fixture validation
- Exercising MCP tools against preview.coo.ee

---

## Debugging and Troubleshooting

### preview.coo.ee is Slow or Unresponsive

- Check the server's health at `https://preview.coo.ee/health`
- The server may be under load; try again later
- Use a local server if available

### Wasm Editor Won't Load

- Browser console errors? Check WebGL support: `https://webglreport.com/`
- Serve with `http.server` on localhost? HTTPS is required for production URLs
- Test with Playwright: See if the page renders in headless mode

### Gradle Tests Fail

- Run `./gradlew check` to see all failures
- Check Java versions: `./gradlew --version` should show Java 21
- Clear Gradle cache: `rm -rf .gradle && ./gradlew check`

### Export Fails with "No export lane"

- Confirm Java 21 is running the server: `java -version`
- Server below Java 21? PNG/SVG export is disabled; rebuild on Java 21
- Check the server's startup logs for "render lane"

### Agent Grant Approval Timed Out

- The poll URL expires after ~10 minutes
- Request a new grant: `POST /agent-access/request`
- Make sure the operator opened the approval URL in their browser

### MCP Tool Returns "Unauthorized"

- Bearer token expired or invalid
- Request a new grant from `/agent-access/request`
- Confirm the token is in the `Authorization` header, not a query parameter

---

## Further Reading

- `docs/UI_BUILDER_GETTING_STARTED.md` — How to run the editor locally
- `docs/design/UI_BUILDER_LIVE_SESSION.md` — Design state and protocol
- `docs/design/UI_BUILDER_SIDECAR_ACCESS.md` — Native preview compilation
- `docs/design/UI_BUILDER_PROTOCOL_CLIENT.md` — MCP client implementation
- `.github/workflows/ci.yml` — The CI test suite
- `scripts/ui-builder/design-sync.mjs` — Design sync utility for agents
