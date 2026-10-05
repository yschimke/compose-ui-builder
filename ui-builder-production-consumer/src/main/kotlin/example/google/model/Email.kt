// Application data for the reusable Google app item examples.
package example.google.model

data class Email(
  val sender: String,
  val receivedAt: String,
  val subject: String,
  val snippet: String,
)
