package ee.schimke.composeai.uibuilder.intellij

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/** The group `plugin.xml` registers; a person can mute or re-style it under Notifications. */
private const val NOTIFICATION_GROUP = "Compose UI Builder"

/**
 * Tells the person something happened, without taking focus.
 *
 * A modal dialog was the answer to everything — a failed connection, an empty account, a copied
 * prompt — and each one stopped work until dismissed. Only a question keeps a dialog now.
 */
internal fun notifyUiBuilder(
  project: Project,
  content: String,
  type: NotificationType = NotificationType.INFORMATION,
) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup(NOTIFICATION_GROUP)
    .createNotification("Compose UI Builder", content, type)
    .notify(project)
}

/** Application settings, stored where every project reads them. */
internal object UiBuilderSettings {
  private const val DEFAULT_SERVER_PROPERTY = "compose.ui.builder.default.server"
  private const val FALLBACK_SERVER = "https://preview.coo.ee"

  /** The server a first "Browse server designs" offers; each project then remembers its own. */
  var defaultServer: String
    get() =
      PropertiesComponent.getInstance().getValue(DEFAULT_SERVER_PROPERTY)?.takeIf {
        it.isNotBlank()
      } ?: FALLBACK_SERVER
    set(value) {
      PropertiesComponent.getInstance().setValue(DEFAULT_SERVER_PROPERTY, value.trim())
    }
}

/** Settings › Tools › Compose UI Builder. */
internal class UiBuilderConfigurable : BoundConfigurable("Compose UI Builder") {
  override fun createPanel(): DialogPanel = panel {
    row("Default preview server:") {
      textField()
        .bindText(UiBuilderSettings::defaultServer)
        .columns(COLUMNS_LARGE)
        .comment("Offered by Browse Server Designs until a project connects to another server.")
    }
  }
}
