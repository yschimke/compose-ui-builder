@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.EditorViewportInsets
import ee.schimke.composeai.uibuilder.editor.LocalEditorViewportInsets

/**
 * The window's safe area and on-screen keyboard, measured by the page and handed to the editor.
 *
 * Compose for web leaves `WindowInsets` empty, so the two browser sources are read directly:
 * * **Safe area** — `env(safe-area-inset-*)`, which only a page that asked for `viewport-fit=cover`
 *   sees as anything but zero. A custom property holding `env()` is not resolved by
 *   `getComputedStyle`, so a fixed, invisible probe is padded by it and its computed padding read
 *   back in pixels.
 * * **Keyboard** — what `visualViewport` no longer covers at the bottom of the layout viewport.
 *   Safari overlays its keyboard, and this is how much of the page it hides; Chrome with
 *   `interactive-widget=resizes-content` resizes the page instead, and this is then zero, which is
 *   right: nothing is behind it.
 *
 * Re-measured on every viewport resize, scroll and orientation change.
 */
@Composable
internal fun ProvideBrowserViewportInsets(content: @Composable () -> Unit) {
  var insets by remember { mutableStateOf(browserViewportInsets()) }
  DisposableEffect(Unit) {
    val stop = watchViewportInsets { insets = browserViewportInsets() }
    onDispose { stopWatchingViewportInsets(stop) }
  }
  CompositionLocalProvider(LocalEditorViewportInsets provides insets, content = content)
}

private fun browserViewportInsets(): EditorViewportInsets {
  val parts = measureViewportInsets().split(',').map { it.toFloatOrNull() ?: 0f }
  if (parts.size < 5) return EditorViewportInsets.None
  return EditorViewportInsets(
    top = parts[0].dp,
    right = parts[1].dp,
    bottom = parts[2].dp,
    left = parts[3].dp,
    keyboard = parts[4].dp,
  )
}

/** `top,right,bottom,left,keyboard` in CSS pixels. */
@JsFun(
  """() => {
    try {
      let probe = document.getElementById('ui-builder-safe-area-probe');
      if (!probe) {
        probe = document.createElement('div');
        probe.id = 'ui-builder-safe-area-probe';
        probe.setAttribute('aria-hidden', 'true');
        probe.style.cssText =
          'position:fixed;top:0;left:0;width:0;height:0;visibility:hidden;pointer-events:none;' +
          'padding:env(safe-area-inset-top,0px) env(safe-area-inset-right,0px) ' +
          'env(safe-area-inset-bottom,0px) env(safe-area-inset-left,0px)';
        document.body.appendChild(probe);
      }
      const style = getComputedStyle(probe);
      const px = (value) => Math.max(0, parseFloat(value) || 0);
      const viewport = globalThis.visualViewport;
      let keyboard = 0;
      if (viewport) {
        // Under 80px is browser chrome settling (a collapsing address bar), not a keyboard.
        const covered = globalThis.innerHeight - (viewport.height + viewport.offsetTop);
        keyboard = covered > 80 ? covered : 0;
      }
      return [
        px(style.paddingTop), px(style.paddingRight), px(style.paddingBottom),
        px(style.paddingLeft), Math.round(keyboard),
      ].join(',');
    } catch (e) {
      return '0,0,0,0,0';
    }
  }"""
)
private external fun measureViewportInsets(): String

/** Calls [onChange] (coalesced to one per frame) when the insets may have moved; returns a stop. */
@JsFun(
  """(onChange) => {
    let pending = false;
    const schedule = () => {
      if (pending) return;
      pending = true;
      requestAnimationFrame(() => { pending = false; onChange(); });
    };
    const viewport = globalThis.visualViewport;
    viewport?.addEventListener('resize', schedule);
    viewport?.addEventListener('scroll', schedule);
    globalThis.addEventListener('resize', schedule);
    globalThis.addEventListener('orientationchange', schedule);
    // Safari scrolls the layout viewport to keep a focused input visible, and this page never
    // scrolls on purpose: put it back, or the whole editor shifts up under the keyboard.
    const unscroll = () => { if (globalThis.scrollY !== 0) globalThis.scrollTo(0, 0); };
    globalThis.addEventListener('scroll', unscroll);
    return () => {
      viewport?.removeEventListener('resize', schedule);
      viewport?.removeEventListener('scroll', schedule);
      globalThis.removeEventListener('resize', schedule);
      globalThis.removeEventListener('orientationchange', schedule);
      globalThis.removeEventListener('scroll', unscroll);
    };
  }"""
)
private external fun watchViewportInsets(onChange: () -> Unit): JsAny

@JsFun("(stop) => stop()") private external fun stopWatchingViewportInsets(stop: JsAny)
