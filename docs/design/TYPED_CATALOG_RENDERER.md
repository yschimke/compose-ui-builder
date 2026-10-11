# Typed catalog renderer

The authoring model and standard JSON generator live in compose-ai-tools. This repository adds
only the Compose bridge: a typed adapter definition registers its own published id, and a
`TypedCanvasNodeScope` reads its property/slot handles and delegates callbacks to the existing SDK.

The bridge is ordinary `ui-builder-renderer-sdk` source on the released tools API (it lived under
`experiments/typed-catalog` behind `-PtypedAdapterCatalog=true` until that API shipped; the property
is now ignored). Its checks:

```sh
build-brief ./gradlew \
  :ui-builder-renderer-sdk:jvmTest --tests '*TypedCanvasAdapterTest' \
  :ui-builder-runtime:test --tests '*TypedCatalogPublicationTest' \
  :ui-builder:jvmTest --tests '*TypedCatalogCanvasTest'
```

`scripts/validate-typed-catalog.sh` runs these checks, the bundle installer's unit tests and Wasm
compilation of the renderer bridge; extra arguments go to Gradle, so `-PlocalBuilds=tools` checks
it against a tools checkout. The typed-catalog CI workflow runs the same script.

Tests cover the actual published-catalog reader without an app dependency, property defaults and
resolved state, strict malformed-value rejection, two-way callbacks, cross-definition handle
rejection, and a compiled Compose button rendering a real slot child and receiving a click.
All declared values are decoded before a registered renderer is entered. Invalid values display a
node-local diagnostic; tests verify that a healthy sibling keeps drawing and correcting the value
recovers the original component. Arbitrary exceptions inside component composables are not caught.
Int properties are literal-only until the state schema supports integer constraints.

This does not install app classes into the IDE or package a Wasm runtime for publication. Use the
existing catalog-owned runtime delivery path; JVM addon/project-process loading remains separate.
A catalog consumes the SDK through a pinned source composite build.

Authoring, publication, guarantees and an application-owned validation test are documented in
[compose-ai-tools' typed catalog design](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/TYPED_UI_BUILDER_CATALOG.md).

## Validate an application's exported pair

The opt-in `ExternalTypedCatalogSmokeTest` reads the pair through the standard host reader in a
process with no app implementation on its classpath. After generating the app's catalog, run:

```sh
build-brief ./gradlew \
  -PtypedCatalogSmokeDirectory=../meshcore-mobile/ui-builder-catalog/build/catalog \
  :ui-builder-runtime:test --tests '*ExternalTypedCatalogSmokeTest'
```

Home Assistant's native export writes `ui-builder-catalog/build/catalog/testDebugUnitTest` instead.
Relative smoke-directory paths are resolved from the builder checkout root; absolute paths also work.
The property is required for this test; ordinary validation excludes it. This verifies
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
./gradlew -PtypedCatalogJvmBundle=/absolute/path/to/unzipped-bundle \
  :ui-builder:jvmTest --tests '*ExternalJvmCatalogEndToEndTest'
```

The bundle is produced and this cross-repository test is run by Meshcore's pinned catalog CI.
This does not install components into the shipped desktop/IDE UI or make native code run in Wasm.
The proof also exercises a generated catalog with no screen frame; the editor treats its explicit
JSON null frame as absent. Literal starters use the document's `bool`/`int` wrapper vocabulary,
while property capability JSON Schema types remain `boolean`/`integer`.

The committed [`evidence/typed-catalog/`](evidence/typed-catalog/) screenshots show the initial installed component and its edited, sent
state. Current runs regenerate these views under `ui-builder/build/typed-catalog/e2e`.

## Consume a published experimental generation

Both app workflows can publish a validated catalog generation as an experimental GitHub prerelease
using the manual `publish` input. The standard `components.json` and `ui-builder.json` files ship
beside the executable ZIP, `catalog.json`, and `bundle.json` (source/dependency commits and SHA-256).
Production preview catalog branches are unaffected.

Download an exact source commit and explicitly select its execution runtime:

```sh
scripts/download-typed-catalog.sh yschimke/meshcore-mobile FULL_SOURCE_SHA jvm-linux-x64 /tmp/mesh-catalog
scripts/download-typed-catalog.sh yschimke/homeassistant-remotecompose FULL_SOURCE_SHA android /tmp/ha-catalog
```

The destination must be new. Installation verifies the expected source commit, runtime, ZIP and
catalog checksums, and that the published JSON pair equals the pair inside the ZIP. It refuses
path traversal, duplicate entries and links before extracting. Checksums establish artifact
consistency; callers still explicitly trust the publisher's executable code and compatible runtime.

Use the Meshcore directory as `-PtypedCatalogJvmBundle=/tmp/mesh-catalog` for
`ExternalJvmCatalogEndToEndTest`. Home Assistant is an Android runtime: its independent
`:ui-builder-catalog-host:testDebugUnitTest` takes `-PtypedCatalogAndroidBundle=/tmp/ha-catalog`.
The Android host supplies Android Compose and Remote Compose engines, while all three app library
implementations are loaded from the downloaded JARs; AARs are also included for Android packaging.
This does not turn Android components into desktop or browser implementations.
