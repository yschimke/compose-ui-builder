package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentRecordFile
import kotlinx.serialization.json.Json

/**
 * The component record the Compose export reads, generated from `m3-catalog-components-v1.json` by
 * `:ui-builder:embedComponentRecord` so editor and server share it. Parsed lazily (until the
 * problems panel opens) and cached. Unknown keys are ignored, like `ComponentRecordSource`: the
 * generator judges schema versions.
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
