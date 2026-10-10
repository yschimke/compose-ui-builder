# Typed catalog renderer prototype

The authoring model and standard JSON generator live in compose-ai-tools. This experiment adds
only the Compose bridge: a typed adapter definition registers its own published id, and a
`TypedCanvasNodeScope` reads its property/slot handles and delegates callbacks to the existing SDK.

It is opt-in because the authoring API has not been released. Default builds do not include these
sources or require an unreleased coordinate. Build with:

```sh
build-brief ./gradlew -PlocalBuilds=tools -PtypedAdapterCatalog=true \
  :ui-builder-renderer-sdk:jvmTest --tests '*TypedCanvasAdapterTest' \
  :ui-builder-runtime:test --tests '*TypedCatalogPublicationTest' \
  :ui-builder:jvmTest --tests '*TypedCatalogCanvasTest'
```

`scripts/validate-typed-catalog.sh` runs these checks, the tools consumer compilation tests, and
Wasm compilation of the renderer bridge. It takes an optional path to the tools checkout.

Tests cover the actual published-catalog reader without an app dependency, property defaults and
resolved state, strict malformed-value rejection, two-way callbacks, cross-definition handle
rejection, and a compiled Compose button rendering a real slot child and receiving a click.

This does not install app classes into the IDE or package a Wasm runtime for publication. Use the
existing catalog-owned runtime delivery path; JVM addon/project-process loading remains separate.
The runtime SDK currently comes from a pinned source composite build. After the tools API is
released and the builder's tools dependency is updated, move the bridge into the normal SDK sources
and remove this opt-in wiring.

Authoring, publication, guarantees and an application-owned validation test are documented in
[compose-ai-tools' typed catalog design](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/TYPED_UI_BUILDER_CATALOG.md).
