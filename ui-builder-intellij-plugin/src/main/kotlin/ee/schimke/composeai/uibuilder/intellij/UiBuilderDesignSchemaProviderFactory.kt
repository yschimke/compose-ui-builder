package ee.schimke.composeai.uibuilder.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType

/**
 * Associates the bundled document schema with `.uid` files and recognized legacy JSON documents.
 */
class UiBuilderDesignSchemaProviderFactory : JsonSchemaProviderFactory {
  override fun getProviders(project: Project): List<JsonSchemaFileProvider> =
    listOf(UiBuilderDesignSchemaProvider)
}

private object UiBuilderDesignSchemaProvider : JsonSchemaFileProvider {
  override fun isAvailable(file: VirtualFile): Boolean = isUiBuilderDesignFile(file)

  override fun getName(): String = "Compose UI Builder design"

  override fun getSchemaFile(): VirtualFile =
    checkNotNull(
      JsonSchemaProviderFactory.getResourceFile(
        UiBuilderDesignSchemaProvider::class.java,
        "/schemas/compose-ui-builder-document-v1.schema.json",
      )
    )

  override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
}
