package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.discovery.ComponentCode
import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentSlot
import ee.schimke.composeai.discovery.ComponentSymbol
import ee.schimke.composeai.discovery.TargetParameter

/*
 * Component records for tests, with the constructors' parameter names, spelled through the
 * contract's Builders (the constructors and `copy` are internal since compose-preview-contracts
 * 3.23.0).
 */

internal fun targetParameter(
  name: String,
  type: String,
  typeFqn: String? = null,
  hasDefault: Boolean = false,
  composableSlot: Boolean = false,
  nullable: Boolean = false,
): TargetParameter =
  TargetParameter.Builder(name, type)
    .apply {
      this.typeFqn = typeFqn
      this.hasDefault = hasDefault
      this.composableSlot = composableSlot
      this.nullable = nullable
    }
    .build()

internal fun componentSymbol(
  jvmOwner: String,
  callable: String,
  name: String,
  origin: ComponentOrigin,
): ComponentSymbol = ComponentSymbol.Builder(jvmOwner, callable, name, origin).build()

internal fun componentSlot(name: String, required: Boolean): ComponentSlot =
  ComponentSlot.Builder(name, required).build()

internal fun componentCode(
  call: String? = null,
  imports: List<String> = emptyList(),
): ComponentCode =
  ComponentCode.Builder()
    .apply {
      this.call = call
      this.imports = imports
    }
    .build()

internal fun componentRecord(
  canonicalId: String,
  symbol: ComponentSymbol,
  componentIds: List<String> = emptyList(),
  parameters: List<TargetParameter> = emptyList(),
  slots: List<ComponentSlot> = emptyList(),
  code: ComponentCode? = null,
  signatureKnown: Boolean = false,
): ComponentRecord =
  ComponentRecord.Builder(canonicalId, symbol)
    .apply {
      this.componentIds = componentIds
      this.parameters = parameters
      this.slots = slots
      this.code = code
      this.signatureKnown = signatureKnown
    }
    .build()
