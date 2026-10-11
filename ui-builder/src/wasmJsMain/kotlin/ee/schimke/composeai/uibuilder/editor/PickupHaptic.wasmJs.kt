@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.editor

internal actual fun pickupHaptic() = vibrateBriefly()

/**
 * Ten milliseconds: felt rather than heard, and short enough not to feel like an error. Guarded:
 * `navigator.vibrate` is absent on Safari and throws in some embedded frames.
 */
@JsFun(
  """() => {
    try {
      if (typeof navigator.vibrate === 'function') navigator.vibrate(10);
    } catch (e) {}
  }"""
)
private external fun vibrateBriefly()
