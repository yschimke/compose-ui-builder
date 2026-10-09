package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter

/**
 * Component records for tests, spelled through the contract's Builders (the constructors and `copy`
 * are internal since compose-preview-contracts 3.23.0).
 */
internal fun testRecord(
  canonicalId: String,
  symbol: ComponentSymbol,
  componentIds: List<String> = emptyList(),
  parameters: List<TargetParameter> = emptyList(),
  signatureKnown: Boolean = false,
): ComponentRecord =
  ComponentRecord.Builder(canonicalId, symbol)
    .apply {
      this.componentIds = componentIds
      this.parameters = parameters
      this.signatureKnown = signatureKnown
    }
    .build()

internal fun testSymbol(
  jvmOwner: String,
  callable: String,
  name: String,
  origin: ComponentOrigin,
): ComponentSymbol = ComponentSymbol.Builder(jvmOwner, callable, name, origin).build()

internal fun testParameter(
  name: String,
  type: String,
  typeFqn: String? = null,
  hasDefault: Boolean = false,
): TargetParameter =
  TargetParameter.Builder(name, type)
    .apply {
      this.typeFqn = typeFqn
      this.hasDefault = hasDefault
    }
    .build()
