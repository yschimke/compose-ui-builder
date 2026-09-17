# Production Remote StateLayout export proof

The separate [loop/function feasibility proof](../../docs/design/evidence/ui-builder-repetition-source/README.md)
also runs here. Its Python prototype generates source from the committed semantic repetition design;
it is explicitly separate from the production StateLayout emitter described below. Both ordinary
Compose and creation-compose compile real loops, typed row classes, reusable composables and
callbacks, then match the JSON/player pixels and all six click targets at densities 1 and 2.
`verify.sh` regenerates both kinds of source and runs both Android and desktop checks. The desktop
source attachment is opt-in through an init script; no production module or export route uses the
prototype.

This standalone Android project compiles the **actual output of the production
`InlineRemoteContentExporter`**, then records it with AndroidX and plays the resulting binary
Remote Compose document. It extends the existing builder's export path; it is not another editor.

Run from the repository root with JDK 21 and an Android SDK containing platform 37:

```shell
ANDROID_HOME=/path/to/android-sdk experiments/remote-state-selection/verify.sh
```

The script runs `RemoteStateSelectionExportTest`, regenerating Kotlin under
`ui-builder-export/build/remote-state-selection`, then runs Robolectric with native graphics.
AGP's variant source API includes that directory directly: no handwritten replacement is compiled.
Android dependencies stay inside this experiment, outside the production Wasm/JVM module graph.

The cases cover numeric values that differ from child indexes, booleans, decimals, no fallback,
13 cases (to exercise expression size), and both signed integer limits. The player test decodes the
captured bytes, changes the recorded state via the host override API, and checks selected indexes
and measured branch dimensions. It does not call internal expression evaluators to make updates work.
This proves host-driven selection; it does not yet prove click dispatch, transition appearance,
or retention of inactive branches.

Three player details affect production lowering:

* `StateLayout` indexes physical children. An unknown value must map to an explicit fallback index;
  an invalid index otherwise selects the first child. With no authored fallback, record an empty Box.
* Keep the authored Box around the StateLayout and its expressions. In AndroidX alpha18,
  expressions directly under the implicit root do not recompute during ordinary paint.
* AndroidX alpha18 integer equality can overflow its subtraction/absolute-value calculation.
  Compare the two 16-bit halves to preserve exact equality across the entire signed Int range.

Every case expression is materialized with `createReference()` to bound the size of the integer
expression's operand mask. String equality and nullable selectors remain explicit export refusals
on this target. The ordinary Compose target supports String cases through its `when` lowering.
