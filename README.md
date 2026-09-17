# Compose UI Builder

A collaborative visual editor for Compose Multiplatform screens. A person or an agent assembles
real, compiled catalog components into a semantic Compose tree, sees that tree rendered by
Compose/Wasm, and exports the same saved design as Figma-compatible SVG or readable Compose source.

Extracted from [`yschimke/compose-preview-server`](https://github.com/yschimke/compose-preview-server),
where these nine modules were already a second project with an enforced boundary. That boundary is
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
| `:ui-builder-runtime` | JVM | the design service: state, catalog validation, revision-pinned export |
| `:ui-builder-export` | `jvm`, `wasmJs` | design → screen-model projection |
| `:ui-builder-renderer` | `wasmJs` | the sandboxed renderer-only runtime |
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

Those four are **not on Maven Central yet**. Until they are, the server resolves them by including
a checkout of this repository as a composite build. When the publishing lane lands, the root
`build.gradle.kts` says what shape it has to take and why.

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
