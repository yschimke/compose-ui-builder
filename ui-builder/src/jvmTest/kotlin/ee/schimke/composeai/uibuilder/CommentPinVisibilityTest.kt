package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.DesignComment
import ee.schimke.composeai.uibuilder.editor.DesignCommentAnchor
import ee.schimke.composeai.uibuilder.editor.DesignCommentThread
import ee.schimke.composeai.uibuilder.inspector.CommentPinOverlay
import ee.schimke.composeai.uibuilder.inspector.LocalHoveredCommentThread
import kotlin.test.Test

/** A comment's pin is not drawn over the design by default: only for the thread being looked at. */
@OptIn(ExperimentalTestApi::class)
class CommentPinVisibilityTest {
  private val threads =
    listOf("first", "second").map { id ->
      DesignCommentThread(
        id = id,
        anchor = DesignCommentAnchor(x = 0.5f, y = 0.5f),
        comments =
          listOf(DesignComment(id = "$id-1", authorId = "ada", displayName = "Ada", body = "Hi")),
      )
    }

  private fun draw(selected: String?, hovered: String?, check: (Boolean, Boolean) -> Unit) =
    runDesktopComposeUiTest(width = 300, height = 300) {
      setContent {
        CompositionLocalProvider(LocalHoveredCommentThread provides mutableStateOf(hovered)) {
          MaterialTheme {
            Box(Modifier.size(200.dp)) {
              CommentPinOverlay(
                threads = threads,
                marks = emptyList(),
                selectedThreadId = selected,
                onSelect = {},
              )
            }
          }
        }
      }
      fun shown(label: String) =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(label, substring = true))
          .fetchSemanticsNodes()
          .isNotEmpty()
      check(shown("Comment 1 "), shown("Comment 2 "))
    }

  @Test
  fun `no pin is drawn while no thread is hovered or open`() =
    draw(selected = null, hovered = null) { first, second ->
      kotlin.test.assertFalse(first)
      kotlin.test.assertFalse(second)
    }

  @Test
  fun `hovering a thread in the panel shows its pin alone`() =
    draw(selected = null, hovered = "second") { first, second ->
      kotlin.test.assertFalse(first)
      kotlin.test.assertTrue(second)
    }

  @Test
  fun `an open thread keeps its pin, which is how a touch screen shows it`() =
    draw(selected = "first", hovered = null) { first, second ->
      kotlin.test.assertTrue(first)
      kotlin.test.assertFalse(second)
    }
}
