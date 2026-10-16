# Lovelace dashboard target

This implements the dashboard direction in PR #898. The dashboard is the single root; views
contain sections, sections contain cards. Harc owns and publishes the palette through the existing
`ui-builder.json` seam. This repository owns the editor and its lossless JSON projection. The
preview server selects that projection for the `lovelace` platform on its existing JSON lane,
independently of the Remote Compose authoring feature flag.

## Supported editing

Sections views are structural. Views of other types, including implicit masonry, panel and
strategy views, are opaque placements: they can be reordered or deleted and are exported unchanged.
They are never converted into sections automatically. Nested cards and unknown custom cards retain
their complete JSON inside `rawConfig`; supported card fields can be edited without decoding the
unknown fields. The editor validates the dashboard/view/section/card hierarchy. Cards use a
12-column grid with numeric or full width and automatic or explicit height. The inspector and the
host's size controls write the same properties. The canvas packs cards and sections, and plays
host-supplied Remote Compose bytes for card content. A missing preview is explicitly labelled.

Import moves editable fields out of raw config. Consequently deleting an editable property or a
child deletes it from the exported dashboard rather than resurrecting the original value. Unknown
fields, explicit nulls, unknown grid options, actions, visibility, nested cards and custom config
survive import, `.uid` persistence, edits and export. Opaque config is JSON text in a regular
string value, so the typed `.uid` value codec cannot reinterpret custom config or round unknown
numbers. Export sorts object keys without coercing numeric literals through floating point. Export refuses unsupported design expressions
and invalid topology instead of emitting a partial dashboard.

## Host boundary

Harc's `/studio/dashboard` hosts the packaged editor in an iframe. It supplies the published
capability catalog and a `compose-ui-builder/open` message with `lovelaceConfig` (JSON text). It
receives the ordinary `.uid` changed/selection/chrome events and an additional
`compose-ui-builder/lovelace-config` event containing the valid exported JSON text. Invalid edits
produce the existing error message and do not produce a new valid config. The host disables saving
until the matching valid export arrives.

Transient `compose-ui-builder/lovelace-previews` messages carry `previews` as JSON text (or a
structured object), keyed by node id, with
`config` and `properties` (the Remote Compose document and named values). The canvas only draws a
preview whose config matches the current card. JSON text also carries unknown large numbers
without floating-point coercion or structured-clone restrictions. These bytes and entity values are not persisted in
`.uid` files or dashboard JSON. The host validates iframe source and origin; the embedded editor
accepts messages from its parent. No Home Assistant connection or credential enters UI Builder.

The host owns OAuth, dashboard selection, live entity subscriptions, rendering requests, storage
mode checks, authorization, saves and undo saves. Only referenced states are sent for rendering;
credential-bearing image attributes are excluded. Rendering uses harc's actual converters/styles.
Unknown/custom cards retain config even when harc cannot render them.

## Host save behavior

Storage dashboards require Home Assistant administrator authorization. YAML dashboards allow draft
editing and JSON export only; Home Assistant remains the final save authority when mode is unknown.
Save re-reads the dashboard and compares it structurally with the loaded baseline. Undo save checks
against the last successful save before restoring its predecessor, preserving newer unsaved edits.
Conflicts and failed writes retain the draft. Home Assistant has no atomic revision/CAS save field,
so a concurrent external write after the read remains possible; this is observed conflict detection.

Harc publishes reusable section and view templates with domain/device-class entity slots. Placement
requires each slot to match an available entity. Only explicit entity-reference fields are bound;
labels and unrelated strings remain unchanged. Templates are copied before placement.

## Validation and release order

Exporter round-trip tests cover realistic mixed-view dashboards and unknown/custom configs. Editor
reducer tests cover hierarchy enforcement, moves, numeric grid resizing, deletion, undo and JSON
code output. Harc tests cover transaction conflicts, failed writes, undo, template matching, host
authorization and the actual renderer HTTP port. Browser evidence must use the fixture dashboard,
never an existing user's dashboard.

Release UI Builder first, then pin that version in compose-preview-server and release the server.
Harc's design-server image pins that server archive. Publish harc's Lovelace catalog and card preview
entry, update deployed catalog configuration, then deploy the host image. No contracts repository
change is needed: the existing catalog, JSON export and embedding seams carry this target.
