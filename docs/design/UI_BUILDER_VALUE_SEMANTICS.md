# UI builder: a committed document is one the canvas can draw

The decision behind
[#487](https://github.com/yschimke/compose-preview-server/issues/487), made executable by
[#489](https://github.com/yschimke/compose-preview-server/issues/489). It closes
[#476](https://github.com/yschimke/compose-preview-server/issues/476) (a colour written as
`string` commits, then fails export with *"is Color, which Text is not"*) and
[#484](https://github.com/yschimke/compose-preview-server/issues/484) (an `asset/image` with an
unresolvable key commits, then kills every render with an `IllegalStateException`).

## The pattern

The reducer validated **shape** — does the component exist, does it declare this property, is the
scalar the right JSON type, is an enumerated value in `allowedValues`, does the slot have enough
children — and refused precisely, naming the node, the field and the rule. Everything on the far
side of that line was **semantics**: does this value have a type the renderer and the generator can
use, does this node name something that resolves. Nothing asked those questions at commit, so the
answer arrived later, from a different actor, on a different call: at export, as a sentence about a
Kotlin type the author never wrote; or at render, as a dead canvas for every collaborator.

The catalog already knew most of it. It knows `m3/text.color` is a colour; it knows
`asset/image.assetKey` must resolve. The information was present at commit time and not consulted.

## The decision

**A committed document is one the canvas can draw, and the author is told at the door.** Three
rules, applied in the two reducers and the renderer:

1. **A property's name states its value kind.** `color` and `…Color` hold a colour; `assetKey`
   holds a key into the catalog's asset registry or the design's own `assets` map; a property
   with `allowedValues` is an
   enumeration (the rule [#339](https://github.com/yschimke/compose-preview-server/issues/339)
   settled). Named rather than declared per property because `CatalogCapabilityV1` is published
   from compose-preview-contracts and cannot grow a field from here, and because the whole
   vocabulary already agrees — every colour property in every catalog is spelled this way, the way
   the inspector's `…Dp` rule already relies on. Stated once in
   [`PropertyValueKinds`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/PropertyValueKinds.kt),
   mirrored by the runtime's catalog executor, which cannot reach that module (the same
   arrangement `slotAccepts` has with `SlotCapability.accepts`).
2. **The kind is checked where the value is chosen, and nowhere else.** A `setProperty`, and each
   property of an `insertNode`. Never document-wide: a design committed before a rule existed stays
   editable everywhere but the field that holds the old value, for the reason
   `CapabilityValidator.writeWrapperIssue` gives. The refusal is `INVALID_PROPERTY`, located to the
   node, the field and the operation, in the wording the `background` modifier's export refusal
   already had: *property `color` is a colour, which is written as a `#RRGGBB` literal or as a
   theme role — a `color` or `colorToken` wrapper, not `string`*.
3. **No node may fail the frame.** Whatever slips past validation — a design committed before the
   rule, a registry that changed underneath it — renders as a visible placeholder. The canvas draws
   a dashed frame naming the key for an asset nothing resolves, and a component's own default for a
   colour role it does not know. It used to throw on both, and one node then failed every render
   of the design.

The editor's inspector follows the same rule: every colour property gets the colour control, and a
commit writes a theme role as `colorToken` and a literal as `color`. Two colours had the control by
override and the other twenty were text fields writing `string`, so without this the editor's own
writes would have been the first thing the rule turned away.

## What the catalog says

The rules are visible where an author reads the catalog, not only where a refusal lands:

- Every colour property carries a `notes` saying it is a colour, which wrappers write one, and
  that a `string` wrapper is refused.
- `statusSemantics.colorTokens.roles` lists the fourteen theme roles the canvas draws. A role the
  export can write (`primaryContainer` is in `MaterialTheme.colorScheme`) but this list does not
  carry is refused at commit, because the canvas would not draw it.
- `statusSemantics.assetRegistry.keys` lists every `assetKey` the catalog itself ships: the two
  project-owned Jetcaster covers in `:ui-builder-artwork`, the generated gate-0 cover, and the
  editor's own insert placeholder. A catalog that declares no registry says nothing about keys.
  The design's own `assets` map extends the set with the pictures its asset lane pinned
  ([`UI_BUILDER_ASSETS.md`](UI_BUILDER_ASSETS.md), closing
  [#478](https://github.com/yschimke/compose-preview-server/issues/478)); both reducers read it.

Tests pin the two lists to the code's own (`ValueKindWriteRulesTest`,
`ProductionUiBuilderRuntimeTest`), so the document cannot promise what the canvas will not draw.

## Unsetting a property

The same mutation surface, opened in the other direction
([#480](https://github.com/yschimke/compose-preview-server/issues/480)). `setProperty` had no
inverse: an author who tried a property and found it wrong — an export diagnostic, say — could
change its value but not the shape of the node, and the only ways back were to delete and rebuild
the node or to guess the renderer's default.

On the wire, a `setProperty` whose value is `{"type": "null"}` unsets the property
([#495](https://github.com/yschimke/compose-preview-server/pull/495)): it leaves the node, the
component's default applies, and the change record's `afterPresent` is what marks a removal for
undo and redo. The batch still passes catalog validation, so unsetting a required property is
refused with the usual located message — *required property `text` is missing*, node and field
named — and nothing lands. Unsetting what is not set is accepted as the no-op it is.

The editor spells the same thing as `DesignOperation.RemoveNodeProperty`, which refuses a required
property at the operation — *required property `text` cannot be unset; give it a value or delete
the node* — and is undone and redone like any other scalar write. The protocol bridge sends it as
`RemoveNodePropertyMutationV1`, the explicit wire spelling compose-preview-contracts 2.10.0 added,
and the runtime routes it through the one property-write path the null `setProperty` uses, so the
two cannot drift. The null spelling stays accepted for clients written against it, and the
client's delta path removes the property for either rather than storing a null the canvas would
read as a value.

## Where the line is

"The renderer cannot draw it", not "the generator cannot write it". A design that is a mockup is a
legitimate output — [#477](https://github.com/yschimke/compose-preview-server/issues/477) and
[#488](https://github.com/yschimke/compose-preview-server/issues/488) argue that being
unexportable must stay allowed — so exportability is not a commit-time refusal here. The export
still refuses what it cannot write, and a colour property now gets the same sentence there as at
the reducer, rather than one about `Text`.

An `internal` error from the export lane no longer carries a Java exception class name: the render
daemon reports a failed composition as `IllegalStateException: …`, and the service strips the class
name before the message reaches a client. A design the renderer cannot draw is an ordinary state;
a stack-trace class name in a user-facing error is not.
