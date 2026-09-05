package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecordFile
import kotlinx.serialization.json.Json

/**
 * The component record the Compose export reads, parsed once.
 *
 * The bytes are generated from `m3-catalog-components-v1.json` by
 * `:ui-builder:embedComponentRecord` so the editor cannot judge a design against a record the
 * server does not have. Parsed lazily because it costs nothing until the problems panel is opened,
 * and cached because it is immutable.
 *
 * Unknown keys are ignored for the same reason `ComponentRecordSource` ignores them: a record from
 * a newer producer should still parse, and the version judgement belongs to the generator, which
 * refuses a schema it does not understand and says so.
 */
private val recordJson = Json { ignoreUnknownKeys = true }

private val embedded: ComponentRecordFile? by
  lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
      recordJson.decodeFromString<ComponentRecordFile>(EMBEDDED_COMPONENT_RECORD_JSON)
    }
      .getOrNull()
  }

/** The embedded record, or null when it did not parse — which the gate reports by name. */
internal fun embeddedComponentRecord(): ComponentRecordFile? = embedded
