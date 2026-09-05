# The UI builder is a second project in this repository

**Status: normative.** The rule is enforced by
[`.github/scripts/ui-builder-project-boundary.sh`](../../.github/scripts/ui-builder-project-boundary.sh),
which CI runs on every pull request. Cited from [`AGENTS.md`](../../AGENTS.md); not restated there.

## The decision

[#346](https://github.com/yschimke/compose-preview-server/issues/346) asked whether the UI-builder
frontend should become its own repository. The answer is **no — it stays here, and it is treated as
a second project inside this one, sharing this repository's release line.**

That is a deliberate middle position, and both halves of it are load-bearing.

*Not a repository.* The extraction is feasible — the seam is unusually clean — but nothing today is
blocked by the modules sharing a repository, and a boundary would convert every change spanning the
editor and the routes that serve it into two pull requests, a release and a pin bump. The
server↔CLI pin already shows what that costs: `composeai-preview-serve` is a point pin in
compose-ai-tools precisely so the server cannot move under the CLI without a pull request, and
`check_preview_server_pin.py` exists because that coupling is sharp enough to break installs.

*Not just "some modules".* The reason to draw the line anyway is that an undrawn one rots. Every
edge across it was correct when it was written and wrong later — `:ui-builder-runtime` copying the
frontend's build output into a published server jar
([#350](https://github.com/yschimke/compose-preview-server/pull/350)), `:ui-builder` hiding a type
its own public API is written in behind `implementation`
([#348](https://github.com/yschimke/compose-preview-server/pull/348)). Neither was a bad decision;
both were decisions nothing was checking.

## The two projects

**The UI builder** — the design surface and everything that serves one design:

`:ui-builder` · `:ui-builder-artwork` · `:ui-builder-export` · `:ui-builder-generated-jetcaster` ·
`:ui-builder-reference-jetcaster` · `:ui-builder-render-bundle` · `:ui-builder-renderer` ·
`:ui-builder-runtime` · `:ui-builder-web`

**The server** — the host, its transports and the surfaces that are not the builder:

`:mcp` · `:native-catalog-m3` · `:server` · `:slot-preview-runtime` · `:usage-source-psi` ·
`:wasm-ui`

`:ui-builder-runtime` is inside the builder, not the server, even though `:server` links it. It is
the builder's own service — design state, catalog validation, revision-pinned export — and the fact
that the host links a service does not make the service part of the host. This is the one
membership call worth stating: the alternative reading, "published and linked by the server, so
server-side", would have put the boundary in the middle of the builder's own stack.

## The rule

1. **The builder never names the server.** No module in the UI-builder project may declare a
   dependency on a module in the server project, in any configuration. A second project that
   reaches back into its host is not a second project.

2. **The server names only the seams.** `:server` and its siblings may depend on the UI-builder
   project through exactly four modules:

   | Seam | Published as | What crosses |
   | --- | --- | --- |
   | `:ui-builder-runtime` | `compose-preview-ui-builder-runtime` | the service port and design state |
   | `:ui-builder-export` | `compose-preview-ui-builder-export` | the design → screen-model projection |
   | `:ui-builder-web` | `compose-preview-ui-builder-web` | the editor, as a Wasm distribution archive |
   | `:ui-builder-render-bundle` | `compose-preview-ui-builder-render-bundle` | the packaged preview a design renders through |

   The test for a seam is not "is it convenient": it is **published, with a POM a consumer outside
   this repository could resolve.** If the server could not depend on it across a repository
   boundary, it must not depend on it across this one.

   `:ui-builder` itself is deliberately not a seam. The editor is reached as a distribution, never
   as a classpath.

3. **Every module belongs to exactly one project.** A new module that joins neither list fails the
   check. This is the guard that matters most in practice — the usual way a boundary rots is not a
   forbidden edge but a module nobody classified, which the check then silently stops covering.

## What the boundary is *not*

- **Not a release boundary.** One version line, one `.release-please-manifest.json`, one tag. Both
  projects ship together, and a change spanning them is still one pull request.
- **Not a directory boundary.** The modules stay where they are. Moving them would rewrite every
  path in CI and every reference in `docs/`, to express in the tree what the check already
  expresses in one file.
- **Not a promise to split later.** If the split ever happens, this boundary is the prerequisite
  that makes it mechanical. If it never happens, the boundary earns its keep anyway, for the reason
  the two regressions above give.

## Why a text scan rather than a Gradle task

The check reads the `project(":…")` declarations in each module's build file. That is a weaker
mechanism than resolving a classpath and a deliberately chosen one: a resolved classpath reports
compile and runtime edges and nothing else, and the edge this boundary lost most recently was
neither. `:ui-builder-runtime` reached `project(":ui-builder").tasks` to copy a build output — a
real dependency between the two projects, invisible to every classpath in the build.

The rule is about what a module *declares*, so the check reads declarations. The same reasoning the
agent-attribution gate uses for its own scanner.

## Changing the rule

Moving a module between projects, or adding a seam, is a change to this document and to the lists in
the script — in the same pull request as the code, so the reviewer sees the boundary move rather
than discovering it later. Adding a seam means publishing the module first: the table above is a
list of coordinates, not of conveniences.
