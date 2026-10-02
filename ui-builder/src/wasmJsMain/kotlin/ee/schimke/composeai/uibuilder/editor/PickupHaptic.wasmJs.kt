@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder.editor

internal actual fun pickupHaptic() = vibrateBriefly()

/**
 * Ten milliseconds: felt rather than heard, and short enough that a device which buzzes on every
 * key press does not make a pickup feel like an error. Guarded, because `navigator.vibrate` is
 * absent on Safari and throws in some embedded frames.
 */
@JsFun(
  """() => {
    try {
      if (typeof navigator.vibrate === 'function') navigator.vibrate(10);
    } catch (e) {}
  }"""
)
private external fun vibrateBriefly()
