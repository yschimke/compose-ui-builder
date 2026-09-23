package ee.schimke.composeai.uibuilder.intellij

import com.intellij.json.JsonFileType
import com.intellij.json.JsonLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * A UI Builder document with JSON language services, selected unambiguously by its `.uid` suffix.
 */
class UiBuilderDesignFileType private constructor() : LanguageFileType(JsonLanguage.INSTANCE) {
  override fun getName(): String = "Compose UI Builder Design"

  override fun getDescription(): String = "Compose UI Builder design document"

  override fun getDefaultExtension(): String = "uid"

  override fun getIcon(): Icon? = JsonFileType.INSTANCE.icon

  companion object {
    @JvmField val INSTANCE = UiBuilderDesignFileType()
  }
}
