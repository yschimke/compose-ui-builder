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
for running it.

## The modules

| Module | Targets | What it is |
| --- | --- | --- |
| `:ui-builder` | `jvm`, `wasmJs` | the editor — canvas, palette, inspector, reducer, exporters, offline service |
| `:ui-builder-desktop` | JVM desktop | native offline desktop app: the window, File menu and installers |
| `:ui-builder-host-jvm` | JVM | the hosting layer the desktop app and the IntelliJ plugin share: sessions, catalogs, design files, export |
| `:ui-builder-intellij-plugin` | IntelliJ Platform | proof-of-concept Jewel tool-window host for the native editor |
| `:ui-builder-runtime` | JVM | the design service: state, catalog validation, revision-pinned export |
| `:ui-builder-export` | `jvm`, `wasmJs` | design → screen-model projection |
| `:ui-builder-renderer` | `wasmJs` | the sandboxed renderer-only runtime |
| `:ui-builder-renderer-sdk` | `jvm`, `wasmJs` | catalog-facing renderer protocol, inspection model and sandbox host |
| `:ui-builder-web` | — | packages the editor's Wasm output as an immutable archive |
| `:ui-builder-render-bundle` | — | packages the editor's JVM previews as the polyglot render bundle |
| `:ui-builder-artwork` | `jvm`, `wasmJs` | offline artwork bindings |
| `:ui-builder-reference-jetcaster` | `wasmJs` | reference-fidelity fixture |
| `:ui-builder-generated-jetcaster` | — | generated-fidelity fixture |

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

**Maven Central — three jars and a BOM.** What a consumer compiles or resolves against:

| Coordinate | |
| --- | --- |
| `ee.schimke.composeai:compose-preview-ui-builder-bom` | version constraints for the three below |
| `…:compose-preview-ui-builder-runtime` | the design service |
| `…:compose-preview-ui-builder-export` | the design → screen-model projection |
| `…:compose-preview-ui-builder-render-bundle` | the packaged preview a design renders through |

`-render-bundle` is on that list even though nobody names it directly: it is an `api` dependency of
the runtime, so its coordinate appears in the runtime's POM, and a POM naming an artifact nobody
uploaded is what made `compose-preview-serve` unresolvable for six consecutive releases.

**GitHub release assets — a Wasm ZIP, a Linux Desktop app, and an IntelliJ plugin.**
`compose-preview-ui-builder-web-<version>.zip` is the Wasm editor, which a host unpacks; nothing
compiles against it or resolves it transitively. The release also carries
`compose-ui-builder-desktop_<version>_amd64.deb`, the native offline Compose Desktop application,
and `compose-ui-builder-intellij-plugin-<version>.zip`, which installs the offline editor as an
IntelliJ tool window.
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

**Nothing has been published yet.** The lane is configured and verified, but no release has gone
out, so compose-preview-server still resolves these four by including a checkout of this repository
as a composite build.

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
PLUGIN_VERSION=0.1.0 ./gradlew :ui-builder-intellij-plugin:buildPlugin
gh release upload v0.1.0 \
  ui-builder-web/build/distributions/compose-preview-ui-builder-web-0.1.0.zip \
  ui-builder-intellij-plugin/build/distributions/compose-ui-builder-intellij-plugin-0.1.0.zip
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
