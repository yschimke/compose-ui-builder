package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a path claims, stated as the cases that would break a consumer.
 *
 * The load-bearing one is the first: with no repetition in the document — which is every document
 * the format can express today — a path is spelled exactly like the node id it names, so every
 * bounds key, `testTag` and comment anchor is the one that was there before.
 */
class UiBuilderInstancePathTest {
  @Test
  fun `without a repeat a path is the node id, spelling included`() {
    val root = UiBuilderInstancePath.of("root")
    val child = root.child("row").child("cell-0")

    assertEquals("root", root.value)
    assertEquals("cell-0", child.value)
    assertEquals("cell-0", child.nodeId)
    assertTrue(child.isAuthored)
  }

  @Test
  fun `a copy is named by the repeated node and its index`() {
    val copy = UiBuilderInstancePath.of("cell").occurrence(3)

    assertEquals("cell#3", copy.value)
    assertEquals("cell", copy.nodeId)
    assertFalse(copy.isAuthored)
  }

  @Test
  fun `below a copy a descendant keeps the copy it is in`() {
    val label = UiBuilderInstancePath.of("cell").occurrence(3).child("label")

    assertEquals("cell#3/label", label.value)
    assertEquals("label", label.nodeId)
    assertFalse(label.isAuthored)
  }

  /**
   * Two copies of the same subtree are two paths, which is the whole reason the type exists: keyed
   * by node id they were one box, and the second measurement overwrote the first.
   */
  @Test
  fun `the same node in two copies is two paths`() {
    val first = UiBuilderInstancePath.of("cell").occurrence(0).child("label")
    val second = UiBuilderInstancePath.of("cell").occurrence(1).child("label")

    assertTrue(first != second)
    assertEquals(first.nodeId, second.nodeId)
  }

  /**
   * A repeat inside a repeat carries both copies, because both are needed to say which box this is.
   * What is never carried is the way down from the root to the outermost repeat: above every
   * repeat, a node id is already unique.
   */
  @Test
  fun `a nested repeat carries every copy above it, and nothing above those`() {
    val inner =
      UiBuilderInstancePath.of("screen")
        .child("list")
        .child("row")
        .occurrence(2)
        .child("cell")
        .occurrence(4)
        .child("label")

    assertEquals("row#2/cell#4/label", inner.value)
    assertEquals("label", inner.nodeId)
  }

  /**
   * A node id is whatever the document says it is.
   *
   * `InsertNode` rejects a blank id and an already-used one, and nothing else, so an id carrying
   * the punctuation a path renders with is legitimate. Read back out of a joined string these
   * answered `title` and `foo` — a node the renderer would then fail to find, having published its
   * bounds under a name no consumer could match.
   */
  @Test
  fun `an id carrying path punctuation survives, and is still one box`() {
    val slashed = UiBuilderInstancePath.of("section/title")
    val hashed = UiBuilderInstancePath.of("foo#bar")
    val insideACopy = UiBuilderInstancePath.of("row").occurrence(1).child("section/title")

    assertEquals("section/title", slashed.nodeId)
    assertEquals("foo#bar", hashed.nodeId)
    assertEquals("section/title", insideACopy.nodeId)
    assertTrue(slashed != hashed)
  }

  /**
   * The spelling is a rendering, and identity is the segments: a path that happens to print like
   * another is not that other one.
   */
  @Test
  fun `two paths that print alike are still two paths`() {
    val copy = UiBuilderInstancePath.of("cell").occurrence(3)
    val punctuatedId = UiBuilderInstancePath.of("cell#3")

    assertEquals(copy.value, punctuatedId.value)
    assertTrue(copy != punctuatedId)
    assertEquals("cell", copy.nodeId)
    assertEquals("cell#3", punctuatedId.nodeId)
  }
}
