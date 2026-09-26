package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.CommentAuthorLabel
import ee.schimke.composeai.uibuilder.editor.DesignComment
import ee.schimke.composeai.uibuilder.editor.DesignCommentAuthorKind
import ee.schimke.composeai.uibuilder.editor.commentAuthorLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class CommentAuthorLabelTest {
  @Test
  fun `a github poster shows their name and their login`() {
    val label = label("github:yschimke", "Yuri")

    assertEquals(CommentAuthorLabel("Yuri", "@yschimke"), label)
    assertEquals("Yuri · @yschimke", label.text)
  }

  @Test
  fun `a name that is somebody else's still shows the account that posted it`() {
    assertEquals("Octocat · @yschimke", label("github:yschimke", "Octocat").text)
  }

  @Test
  fun `a name equal to the login collapses to the handle`() {
    assertEquals(CommentAuthorLabel("@yschimke"), label("github:yschimke", "yschimke"))
    assertEquals(CommentAuthorLabel("@yschimke"), label("github:yschimke", "YSchimke"))
    assertEquals(CommentAuthorLabel("@yschimke"), label("github:yschimke", "@yschimke"))
  }

  @Test
  fun `a blank name shows the handle alone`() {
    assertEquals("@octocat", label("github:octocat", "  ").text)
  }

  @Test
  fun `an agent id is labelled as an agent rather than by its fingerprint`() {
    val label = label("agent:3f9a0c1d2e4b", "Review agent", kind = DesignCommentAuthorKind.Agent)

    assertEquals(CommentAuthorLabel("Review agent", agent = true), label)
    assertEquals("Review agent · agent", label.text)
  }

  @Test
  fun `an agent id is an agent even when the kind says otherwise`() {
    assertEquals("Agent · agent", label("agent:3f9a0c1d2e4b", "").text)
  }

  @Test
  fun `another id is shown as it is`() {
    assertEquals("Ops · operator", label("operator", "Ops").text)
    assertEquals("operator:library", label("operator:library", "").text)
    assertEquals("operator", label("operator", "operator").text)
  }

  @Test
  fun `a long raw id is shortened`() {
    val label = label("oidc:" + "x".repeat(60), "Sam")

    assertEquals("Sam", label.name)
    assertEquals(24, label.account!!.length)
    assertEquals("oidc:xxxxxxxxxxxxxxxxxx…", label.account)
  }

  @Test
  fun `a pseudonym from a public read shows the name as sent and no account`() {
    assertEquals(
      CommentAuthorLabel("Collaborator"),
      label("collaborator-1a2b3c4d", "Collaborator"),
    )
    assertEquals(CommentAuthorLabel("Collaborator"), label("collaborator-1a2b3c4d", ""))
    assertEquals(
      "Collaborator · agent",
      label("collaborator-1a2b3c4d", "Collaborator", kind = DesignCommentAuthorKind.Agent).text,
    )
  }

  @Test
  fun `a comment with no id shows its name`() {
    assertEquals(CommentAuthorLabel("Yuri"), label("", "Yuri"))
  }

  @Test
  fun `the comment's one-line author is the label`() {
    assertEquals(
      "Yuri · @yschimke",
      DesignComment(id = "c", authorId = "github:yschimke", displayName = "Yuri", body = "").author,
    )
  }

  private fun label(
    authorId: String,
    displayName: String,
    kind: DesignCommentAuthorKind = DesignCommentAuthorKind.Human,
  ) =
    commentAuthorLabel(
      DesignComment(
        id = "c",
        authorId = authorId,
        displayName = displayName,
        kind = kind,
        body = "",
      )
    )
}
