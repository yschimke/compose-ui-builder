# The UI builder is a second project in this repository

> **Superseded in part, and kept because the rest of it is still the rule.**
>
> The extraction this document argued against has happened: these nine modules are now
> `yschimke/compose-ui-builder`, and the boundary below is a repository boundary. What changed is
> the *mechanism* — rule 3 and `ui-builder-project-boundary.sh` are gone, because a module cannot
> join the wrong project when the projects are different repositories, and the script's own
> reasoning ("the usual way a boundary rots is a module nobody classified") no longer has a way to
> happen.
>
> What did **not** change, and is why this document travelled with the code: the membership call in
> *The two projects*, the seam table in rule 2 — still exactly the four modules the server
> consumes — and *The test for a seam*. Those decide what a consumer may reach for, and they now
> decide what this repository publishes.
>
> The cost estimate in *The decision* also stands, and is now being paid rather than predicted:
> every change spanning the editor and the routes that serve it is two pull requests. Read it as
> the record of what was known before, not as a claim about where the code is.


**Status: normative.** The repository boundary now enforces rule 1 structurally. The four allowed
consumer seams in rule 2 are documented and release-checked here, and are also named in
[`AGENTS.md`](../../AGENTS.md).

## The decision

[#346](https://github.com/yschimke/compose-preview-server/issues/346) asked whether the UI-builder
frontend should become its own repository. The original answer was no; the later extraction
superseded that part of the decision. The remaining decision is the strict project boundary and the
small, explicit consumer seam.

That is a deliberate middle position, and both halves of it are load-bearing.

*Now a repository.* The extraction was feasible because the seam was unusually clean. Its predicted
cost is now real: a change spanning the editor and the routes that serve it needs two pull requests,
a release and a pin bump. That cost makes keeping the seam narrow more important, not less.

*Not just "some modules".* The reason to draw the line anyway is that an undrawn one rots. Every
edge across it was correct when it was written and wrong later — `:ui-builder-runtime` copying the
frontend's build output into the server jar
([#350](https://github.com/yschimke/compose-preview-server/pull/350)), `:ui-builder` hiding a type
its own public API is written in behind `implementation`
([#348](https://github.com/yschimke/compose-preview-server/pull/348)). Neither was a bad decision;
both were decisions nothing was checking.

## The two projects

**The UI builder** — the design surface and everything that serves one design:

`:ui-builder` · `:ui-builder-artwork` · `:ui-builder-desktop` · `:ui-builder-host-jvm` · `:ui-builder-intellij-plugin` · `:ui-builder-export` · `:ui-builder-generated-jetcaster` ·
`:ui-builder-reference-jetcaster` · `:ui-builder-render-bundle` · `:ui-builder-renderer` ·
`:ui-builder-renderer-sdk` · `:ui-builder-runtime` · `:ui-builder-web`

**The server** — the host, its transports and the surfaces that are not the builder:

`:mcp` · `:native-catalog-m3` · `:server` · `:usage-source-psi` · `:wasm-ui`

`:ui-builder-runtime` is inside the builder, not the server, even though `:server` links it. It is
the builder's own service — design state, catalog validation, revision-pinned export — and the fact
that the host links a service does not make the service part of the host. This is the one
membership call worth stating: the alternative reading, "linked by the server, so
server-side", would have put the boundary in the middle of the builder's own stack.

## The rule

1. **The builder never names the server.** No module in the UI-builder project may declare a
   dependency on a module in the server project, in any configuration. A second project that
   reaches back into its host is not a second project.

2. **The server names only the seams.** `:server` and its siblings may depend on the UI-builder
   project through exactly four modules:

   | Seam | What crosses |
   | --- | --- |
   | `:ui-builder-runtime` | the service port and design state |
   | `:ui-builder-export` | the design → screen-model projection |
   | `:ui-builder-web` | the editor, as a Wasm distribution archive |
   | `:ui-builder-render-bundle` | the packaged preview a design renders through |

   The test for a seam is not "is it convenient": it is a deliberately named contract or packaged
   distribution edge that the boundary check can keep narrow. This repository stopped publishing
   Maven coordinates in #794, so resolvability from an external POM is no longer a criterion.

   `:ui-builder` itself is deliberately not a seam. The editor is reached as a distribution, never
   as a classpath.

   Within the builder, `:ui-builder-runtime` also uses `:ui-builder-export` for shared scalar state
   binding validation. That module and its screen-model dependency contain no Compose UI or
   transport code. The runtime's resolved-classpath allowlist names them explicitly, so browser
   and service validation can agree without linking the editor or server into the runtime.

3. **Every module belongs to exactly one project.** A new module that joins neither list fails the
   check. This is the guard that matters most in practice — the usual way a boundary rots is not a
   forbidden edge but a module nobody classified, which the check then silently stops covering.

## What the boundary was *not* before extraction

This section records the old in-repository mechanism. It is historical, not a description of the
current repository layout or release process.

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
than discovering it later. The table above is a list of reviewed internal contracts and packaged
artifacts, not a list of conveniences or Maven coordinates.
