package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ModifierMenuUiTest {
  @Test
  fun `compact medium and large selection menus keep actions reachable`() =
    runDesktopComposeUiTest(width = 1000, height = 750) {
      var count by mutableStateOf(3)
      var expanded by mutableStateOf(true)
      val calls = mutableListOf<String>()
      val registry =
        MENU_MODIFIERS.filter { it.offeredIn(EditorLayoutScope.Column) }
          .mapIndexed { i, m -> EditorModifierToggle(m.key, m.label, i == 1) }
      setContent {
        MaterialTheme(colorScheme = darkColorScheme()) {
          Box(Modifier.fillMaxSize().background(Color(0xff17181a))) {
            Text(
              "Selection context menu · $count modifiers",
              Modifier.align(Alignment.TopEnd).padding(24.dp),
              color = Color.White,
            )
            Box(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 64.dp)) {
              MaterialUiBuilderChrome.PopupMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = DpOffset.Zero,
                entries =
                  editorSelectionMenuEntries(
                    modifierToggles = registry.take(count),
                    onToggleModifier = { calls += it },
                    canDuplicate = true,
                    canCopy = true,
                    canCut = true,
                    canPaste = false,
                    canDelete = true,
                    wrapCandidates = emptyList(),
                    canUnwrap = false,
                    onOpenProperties = {},
                    onQuickEdit = {},
                    onCopyLink = {},
                    onCopyCode = {},
                    onDismiss = { expanded = false },
                    dispatch = {},
                  ),
              )
            }
          }
        }
      }
      onNodeWithText("Fill the width").assertIsSelected()
      capture("compact.png")
      runOnIdle { count = 5 }
      onNodeWithText("Modifiers").performClick()
      onNodeWithText("Fill the width").assertIsSelected()
      capture("medium.png")
      onNodeWithText("Fill the width").performClick()
      runOnIdle {
        assertEquals(listOf("fillMaxWidth"), calls)
        assertFalse(expanded)
        count = registry.size
        expanded = true
      }
      onNodeWithText("Layout & sizing").assertExists()
      capture("large.png")
      // Keyboard users can open with Right, return with Left, and activate with Enter.
      onNodeWithText("Layout & sizing").performSemanticsAction(SemanticsActions.RequestFocus) {
        it()
      }
      onNodeWithText("Layout & sizing").performKeyInput { pressKey(Key.DirectionRight) }
      onNodeWithText("Fill the parent").assertIsFocused()
      onNodeWithText("Fill the parent").performKeyInput { pressKey(Key.DirectionDown) }
      onNodeWithText("Fill the width").assertIsFocused().assertIsSelected()
      onNodeWithText("Fill the width").performKeyInput { pressKey(Key.DirectionUp) }
      onNodeWithText("Fill the parent").assertIsFocused()
      onNodeWithText("Fill the parent").performKeyInput { pressKey(Key.DirectionDown) }
      val panels = onAllNodes(hasScrollAction()).fetchSemanticsNodes().map { it.boundsInWindow }
      capture("large-layout.png")
      assertTrue(panels.last().left >= panels.first().right, "$panels")
      onNodeWithText("Fill the width").performSemanticsAction(SemanticsActions.RequestFocus) {
        it()
      }
      onNodeWithText("Fill the width").performKeyInput { pressKey(Key.DirectionLeft) }
      onNodeWithText("Layout & sizing").assertIsFocused()
      onNodeWithText("Layout & sizing").performKeyInput { pressKey(Key.Enter) }
      onNodeWithText("Fill the parent").performKeyInput { pressKey(Key.Escape) }
      onNodeWithText("Layout & sizing").assertExists()
      onNodeWithText("Fill the width").assertDoesNotExist()
      onNodeWithText("Layout & sizing").performClick()
      onNodeWithText("Fill the width").performSemanticsAction(SemanticsActions.RequestFocus) {
        it()
      }
      onNodeWithText("Fill the width").performKeyInput { pressKey(Key.Enter) }
      runOnIdle { assertEquals(listOf("fillMaxWidth", "fillMaxWidth"), calls) }
    }

  @Test
  fun `oversized future category scrolls within a short window`() =
    runDesktopComposeUiTest(width = 640, height = 360) {
      var picked: String? = null
      setContent {
        MaterialTheme {
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Box(Modifier.size(1.dp)) {
              MaterialUiBuilderChrome.PopupMenu(
                expanded = true,
                onDismissRequest = {},
                offset = DpOffset.Zero,
                entries =
                  modifierMenuEntries(
                    (1..25).map { EditorModifierToggle("future$it", "Future $it", false) },
                    {},
                    { picked = it },
                  ),
              )
            }
          }
        }
      }
      onNodeWithText("Behavior (1–12)").performClick()
      val popup = onAllNodes(hasScrollAction()).fetchSemanticsNodes().last().boundsInWindow
      assertTrue(popup.top >= 0f && popup.bottom <= 360f, "$popup")
      assertTrue(popup.left >= 0f && popup.right <= 640f, "$popup")
      val parentBounds = onAllNodes(hasScrollAction()).fetchSemanticsNodes().first().boundsInWindow
      assertTrue(
        popup.right <= parentBounds.left,
        "submenu should open to the left: $popup, $parentBounds",
      )
      onNodeWithText("Future 12").performScrollTo().assertIsDisplayed().performClick()
      runOnIdle { assertEquals("future12", picked) }
    }

  @Test
  fun `RTL reverses submenu direction and exposes expansion state`() =
    runDesktopComposeUiTest(width = 700, height = 600) {
      setContent {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          MaterialTheme {
            MaterialUiBuilderChrome.PopupMenu(
              expanded = true,
              onDismissRequest = {},
              offset = DpOffset.Zero,
              entries =
                modifierMenuEntries(
                  (1..5).map { EditorModifierToggle("future$it", "Future $it", false) },
                  {},
                  {},
                ),
            )
          }
        }
      }
      val parent = onNodeWithText("Modifiers")
      parent.assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed submenu")
      )
      parent.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
      parent.performKeyInput { pressKey(Key.DirectionLeft) }
      onNodeWithText("Future 1").assertIsFocused()
      parent.assert(
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded submenu")
      )
      onNodeWithText("Future 1").performKeyInput { pressKey(Key.DirectionRight) }
      parent.assertIsFocused()
      onNodeWithText("Future 1").assertDoesNotExist()
    }

  private fun SkikoComposeUiTest.capture(name: String) {
    val dir = System.getenv("MODIFIER_MENU_EVIDENCE") ?: return
    val output = File(dir).apply { mkdirs() }
    ImageIO.write(captureToImage().toAwtImage(), "png", File(output, name))
  }
}
