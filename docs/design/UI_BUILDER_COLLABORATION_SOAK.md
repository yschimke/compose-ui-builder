# UI builder collaboration soak

`PersistentCollaborationSoakTest` drives the transport-free `PersistentDesignService` and
`FileDesignStore` boundary with two browser-shaped clients and one MCP-shaped client. Its schedule
uses the fixed seed `0x5EED_C011_AB0A`; it does not use threads, random timing, or sleeps in the CI
path.

The bounded CI test applies 96 overlapping writes to one shared property. It deliberately keeps one
browser's subscription offline beyond the five-event retained window, reconnects from that
browser's last delivered sequence, and requires a replacement snapshot. Every 24 accepted writes it
closes the process-local subscriptions and reconstructs `PersistentDesignService` from the same
files. Seeded client selection, stale writes, periodic presence, retries of identical operation IDs,
and retry immediately after restart all happen in one scenario.

The final assertions require all three clients and a newly restarted service to have the same
revision, durable sequence, and document hash. The recovered event log's operation IDs must equal
the acknowledged IDs exactly and in order. Idempotent retries and presence must not enter the event
log or advance the durable cursor.

Run the bounded gate with:

```shell
./gradlew :ui-builder:jvmTest \
  --tests 'ee.schimke.composeai.uibuilder.PersistentCollaborationSoakTest.bounded*'
```

The product-spec 60-minute mode uses the same scenario and fixed seed, paces writes at ten per
second, restarts every 600 accepted writes, and remains opt-in so normal `check` is bounded:

```shell
./gradlew :ui-builder:jvmTest \
  --tests 'ee.schimke.composeai.uibuilder.PersistentCollaborationSoakTest.opt in*' \
  -PuiBuilderCollaborationSoakMinutes=60
```

The duration mode intentionally uses wall-clock duration and pacing. The normal CI test is strictly
operation-bounded and contains no timing assumptions.
