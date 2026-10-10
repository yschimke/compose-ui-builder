# Compose UI Builder

A collaborative visual editor for Compose Multiplatform screens. A person or an agent assembles
real, compiled catalog components into a semantic Compose tree, sees that tree rendered by
Compose/Wasm, and exports the same saved design as Figma-compatible SVG or readable Compose source.

Extracted from [`yschimke/compose-preview-server`](https://github.com/yschimke/compose-preview-server),
where these modules were already a second project with an enforced boundary. That boundary is
now a repository boundary; the document that defined it came with them and is still the normative
statement of what may depend on what
([`docs/design/UI_BUILDER_PROJECT_BOUNDARY.md`](docs/design/UI_BUILDER_PROJECT_BOUNDARY.md)).

Start with [`docs/design/UI_BUILDER_PRODUCT_SPEC.md`](docs/design/UI_BUILDER_PRODUCT_SPEC.md) for
what the product is, and [`docs/UI_BUILDER_GETTING_STARTED.md`](docs/UI_BUILDER_GETTING_STARTED.md)
for running it. For agent integration, install `compose-catalogs` and `compose-skills` through
the [Compose Agent Plugins quick start](https://github.com/yschimke/compose-agent-plugins#quick-start).
The desktop editor also supports [installed Claude Code, Codex and OpenCode harnesses](docs/design/UI_BUILDER_LOCAL_AGENTS.md)
with local authentication and private design chat.

The experimental opt-in contract for regenerating stateless Compose source from checked-in `.uid` files is in
[`Build generation from project owned designs`](docs/design/UI_BUILDER_BUILD_GENERATION.md).

## The modules

| Module | Targets | Package | What it is |
| --- | --- | --- | --- |
| `:ui-builder` | `jvm`, `wasmJs` | `ee.schimke.composeai.uibuilder` (model, entry points) and `.editor`, `.canvas`, `.inspector`, `.codegen`, `.svg`, `.reference`, `.preview`, `.capability`, `.client`, `.icons`, `.local` | the editor — canvas, palette, inspector, reducer, exporters, offline service |
| `:ui-builder-desktop` | JVM desktop | `ee.schimke.composeai.uibuilder.desktop` | native offline desktop app: the window, File menu and installers |
| `:ui-builder-host-jvm` | JVM | `ee.schimke.composeai.uibuilder.host` | the hosting layer the desktop app and the IntelliJ plugin share: sessions, catalogs, design files, export |
| `:ui-builder-runtime` | JVM | `ee.schimke.composeai.uibuilder.service` | the design service: state, catalog validation, revision-pinned export |
| `:ui-builder-codegen-jvm` | JVM | `ee.schimke.composeai.uibuilder.codegen` | opt-in build generation from tracked production `.uid` contracts |
| `:ui-builder-gradle-plugin` | JVM Gradle | `ee.schimke.composeai.uibuilder.gradle` | [explicit Kotlin source-set integration](ui-builder-gradle-plugin/README.md) for durable generation |
| `:ui-builder-production-consumer` | JVM Compose | `example` | executable durable-generation consumer fixture |
| `:ui-builder-export` | `jvm`, `wasmJs` | `ee.schimke.composeai.uibuilder.export` | design → screen-model projection |
| `:ui-builder-renderer` | `wasmJs` | `ee.schimke.composeai.uibuilder.renderer` | the sandboxed renderer-only runtime |
| `:ui-builder-renderer-sdk` | `jvm`, `wasmJs` | `ee.schimke.composeai.uibuilder.renderer.sdk` | catalog-facing renderer protocol, inspection model and sandbox host |
| `:ui-builder-web` | — | — | packages the editor's Wasm output as an immutable archive |
| `:ui-builder-render-bundle` | — | — | packages the editor's JVM previews as the polyglot render bundle |
| `:ui-builder-artwork` | `jvm`, `wasmJs` | `ee.schimke.composeai.uibuilder.artwork` | offline artwork bindings |
| `:ui-builder-reference-jetcaster` | `wasmJs` | `ee.schimke.composeai.uibuilderreference.jetcaster` | reference-fidelity fixture |
| `:ui-builder-generated-jetcaster` | — | `generated.uibuilder` | generated-fidelity fixture |

## Building

```bash
./gradlew check
```

Two Java floors, both named in `gradle/libs.versions.toml`: the editor's JVM lane compiles at
**21**, everything a consumer resolves stays at **17**, and Gradle needs both toolchains.

## Consumers, and the four seams

`compose-preview-server` hosts this editor. It consumes exactly four modules —
`:ui-builder-runtime`, `:ui-builder-export`, `:ui-builder-web` and `:ui-builder-render-bundle` —
and nothing here may depend on it.

A release goes out in two halves, because the four seams are not the same kind of thing.

**Maven Central — libraries, an optional build tool and a BOM.** What a consumer compiles or resolves against:

| Coordinate | |
| --- | --- |
| `ee.schimke.composeai:compose-preview-ui-builder-bom` | version constraints for the artifacts below |
| `…:compose-preview-ui-builder-runtime` | the design service |
| `…:compose-preview-ui-builder-export` | the design → screen-model projection |
| `…:compose-preview-ui-builder-codegen-jvm` | opt-in durable build generation |
| `…:compose-preview-ui-builder-gradle-plugin` | Gradle integration; plugin ID `ee.schimke.compose-ui-builder` |
| `…:compose-preview-ui-builder-render-bundle` | the packaged preview a design renders through |

`-render-bundle` is on that list even though nobody names it directly: it is an `api` dependency of
the runtime, so its coordinate appears in the runtime's POM, and a POM naming an artifact nobody
uploaded is what made `compose-preview-serve` unresolvable for six consecutive releases.

**GitHub release assets — a Wasm ZIP and the Desktop app.**
`compose-preview-ui-builder-web-<version>.zip` is the Wasm editor, which a host unpacks; nothing
compiles against it or resolves it transitively. The release also carries
the native offline Compose Desktop application as a Linux `.deb`, a macOS `.dmg` and a Windows
`.msi` (unsigned). The IntelliJ plugin now lives and releases independently in
[compose-preview-ide](https://github.com/yschimke/compose-preview-ide).
The web bundle stays off Central because a 40 MB frontend distribution published there is permanent
and serves no one. compose-preview-server reaches it through a group-fenced ivy repository over
this repository's releases, so it remains an ordinary versioned dependency:

```kotlin
ivy("https://github.com/yschimke/compose-ui-builder/releases/download") {
  patternLayout { artifact("[revision]/[module]-[revision].[ext]") }
  content { includeModule("ee.schimke.composeai", "compose-preview-ui-builder-web") }
  metadataSources { artifact() }
}
```

`:ui-builder-web` therefore does **not** apply `composeai.maven-publishing`, and that absence is
load-bearing: the release set is derived from which modules apply it, so a module that does not
publish cannot be constrained by a BOM that promises it.

A consumer takes the BOM and then names no versions at all:

```kotlin
implementation(platform("ee.schimke.composeai:compose-preview-ui-builder-bom:<version>"))
implementation("ee.schimke.composeai:compose-preview-ui-builder-runtime")
implementation("ee.schimke.composeai:compose-preview-ui-builder-export")
```

That matters here beyond convenience: the runtime and the export share the screen-model generator,
and a version skew between them does not fail resolution — it produces an export that differs
between the browser and the service.

compose-preview-server pins a release through the BOM (`composeai-ui-builder` in its version
catalog). A pin only moves on a bump, so a change here that breaks the server surfaces on that bump,
in the server's own CI, and is fixed there. A deliberate seam break, a re-package for example, says
so in its release notes; why there is no cross-repository job for it is in AGENTS.md, "The seams".

### Publishing a release

The set is derived, never listed: a module publishes if and only if its build script applies
`composeai.maven-publishing`, and `:bom` constrains exactly that set. Adding a module to the
release is applying the plugin; there is no list to update.

Publishing needs Maven Central credentials and a signing key, which nothing in this repository
carries. Supply them as Gradle properties (`~/.gradle/gradle.properties`) or environment variables:

```properties
mavenCentralUsername=<Central portal token username>
mavenCentralPassword=<Central portal token password>
signingInMemoryKey=<ASCII-armoured secret key, newlines as \n>
signingInMemoryKeyPassword=<key passphrase>
```

Then, with the release version in `PLUGIN_VERSION`:

```bash
PLUGIN_VERSION=0.1.0 ./gradlew publishReleaseArtifacts
PLUGIN_VERSION=0.1.0 ./gradlew :ui-builder-web:webArchive
gh release upload v0.1.0 \
  ui-builder-web/build/distributions/compose-preview-ui-builder-web-0.1.0.zip
```

`.github/workflows/release.yml` does all of this from a `v*` tag, given the four secrets, and runs
the external-consumer gate first — so a POM naming an unresolvable coordinate fails before anything
reaches Central, where it cannot be taken back.

`publishReleaseArtifacts` publishes every module in the derived set and fails first if the set has
lost one of the four seams or the BOM. Without `PLUGIN_VERSION` every module takes the next patch
as a `-SNAPSHOT`, which is unsigned and safe for `publishToMavenLocal`.

To exercise the whole lane without credentials — which is what CI does on every pull request:

```bash
./scripts/check-ui-builder-external-consumer.sh
```

It stages the derived set into a throwaway repository at a snapshot version, then resolves it from
a synthetic consumer outside this source tree.

## Building against local checkouts of the upstream

This repository resolves compose-ai-tools, the preview daemon and the contracts line as published
coordinates. To build against your own checkout of one instead, name it:

```bash
./gradlew check -PlocalBuilds=tools
./gradlew check -PlocalBuilds=tools,daemon
./gradlew check -PlocalBuilds=all
```

Each defaults to a sibling directory beside this one (`../compose-ai-tools`,
`../compose-preview-daemon`, `../compose-preview-contracts`); `-PlocalBuild.tools=<path>` points
somewhere else. Naming a sibling whose directory is missing is an error rather than a silent
fallback to Maven. `settings.gradle.kts` holds the mechanism and the reasoning.

The older `scripts/stage-local-dependency.py` route — compile an upstream module into a
workspace-local Maven repository and pin it through a manifest — still works and is the right tool
when you want one *fixed* upstream build rather than a live one.

Single-file Kotlin export remains the default. Projects can explicitly opt into
[durable build generation](ui-builder-production-consumer/README.md) from tracked production `.uid`
contracts, with separate component and owned-model files.
