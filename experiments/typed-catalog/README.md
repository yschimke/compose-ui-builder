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
Wasm compilation of the renderer bridge, plus formatting checks with the experiment sources enabled.
It takes an optional path to the tools checkout. The dedicated typed-catalog CI workflow runs the
same script against an immutable tools commit until the API is released.

Tests cover the actual published-catalog reader without an app dependency, property defaults and
resolved state, strict malformed-value rejection, two-way callbacks, cross-definition handle
rejection, and a compiled Compose button rendering a real slot child and receiving a click.
All declared values are decoded before a registered renderer is entered. Invalid values display a
node-local diagnostic; tests verify that a healthy sibling keeps drawing and correcting the value
recovers the original component. Arbitrary exceptions inside component composables are not caught.
Int properties are literal-only until the state schema supports integer constraints.

This does not install app classes into the IDE or package a Wasm runtime for publication. Use the
existing catalog-owned runtime delivery path; JVM addon/project-process loading remains separate.
The runtime SDK currently comes from a pinned source composite build. After the tools API is
released and the builder's tools dependency is updated, move the bridge into the normal SDK sources
and remove this opt-in wiring.

Authoring, publication, guarantees and an application-owned validation test are documented in
[compose-ai-tools' typed catalog design](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/TYPED_UI_BUILDER_CATALOG.md).

## Validate an application's exported pair

The opt-in `ExternalTypedCatalogSmokeTest` reads the pair through the standard host reader in a
process with no app implementation on its classpath. After generating the app's catalog, run:

```sh
build-brief ./gradlew -PlocalBuilds=tools -PtypedAdapterCatalog=true \
  -PtypedCatalogSmokeDirectory=../meshcore-mobile/ui-builder-catalog/build/catalog \
  :ui-builder-runtime:test --tests '*ExternalTypedCatalogSmokeTest'
```

Home Assistant's native export writes `ui-builder-catalog/build/catalog/testDebugUnitTest` instead.
Relative smoke-directory paths are resolved from the builder checkout root; absolute paths also work.
The property is required for this test; ordinary experiment validation excludes it. This verifies
metadata consumption and native-only placeholder capabilities, while each app's own native render
test verifies its installed adapter against the actual component. It does not install app code in
an external host.
## JVM executable bundle proof

Meshcore's opt-in catalog module packages its generated JSON pair, a saved ChatInput design and
executable adapter/runtime JARs. `JvmCanvasAdapterProvider` is the experimental JVM service entry
point; `loadJvmCanvasAdapterRegistry` requires exactly one provider for the chosen catalog. The
host passes the bundle's inventory to the provider, rather than running discovery against host
classes. The loader checks every required adapter ID and wraps each adapter with a Compose
`JvmResourceReader` for the same class loader: strings and other resources must come from the
installed JARs too. Only trusted code belongs in this in-process loader.

`ExternalJvmCatalogEndToEndTest` has no Meshcore compile dependency. It reads the bundle through
the standard catalog reader, loads the actual component classes from JAR URLs, renders a saved
design with the builder canvas, commits inspector edits through the editor reducer, exercises
the host's existing Column/Text vocabulary alongside the imported components, checks
two-way text state and Send actions, and reopens the saved edits with fresh preview state. It
saves screenshots and the edited design. The test is
excluded unless `typedCatalogJvmBundle` is supplied; paths resolve from the builder checkout root.

```sh
./gradlew -PtypedAdapterCatalog=true -PlocalBuilds=tools \
  -PlocalBuild.tools=../compose-ai-tools -PtypedCatalogJvmBundle=/absolute/path/to/unzipped-bundle \
  :ui-builder:jvmTest --tests '*ExternalJvmCatalogEndToEndTest'
```

The bundle is produced and this cross-repository test is run by Meshcore's pinned catalog CI.
This does not install components into the shipped desktop/IDE UI or make native code run in Wasm.
The proof also exercises a generated catalog with no screen frame; the editor treats its explicit
JSON null frame as absent. Literal starters use the document's `bool`/`int` wrapper vocabulary,
while property capability JSON Schema types remain `boolean`/`integer`.

The committed `evidence/` screenshots show the initial installed component and its edited, sent
state. Current runs regenerate these views under `ui-builder/build/typed-catalog/e2e`.
