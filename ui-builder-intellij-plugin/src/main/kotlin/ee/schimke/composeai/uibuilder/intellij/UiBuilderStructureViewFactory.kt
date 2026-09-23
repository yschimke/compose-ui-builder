package ee.schimke.composeai.uibuilder.intellij

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TextEditorBasedStructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.structureView.JsonCustomStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.pom.Navigatable
import javax.swing.Icon

/** Adds a component hierarchy to Structure only for recognised UI Builder design documents. */
internal class UiBuilderStructureViewFactory : JsonCustomStructureViewFactory {
  override fun getStructureViewBuilder(file: JsonFile): StructureViewBuilder? {
    if (!isProjectDesign(file.virtualFile)) return null

    return object : TreeBasedStructureViewBuilder() {
      override fun createStructureViewModel(editor: Editor?): StructureViewModel =
        UiBuilderStructureViewModel(editor, file)
    }
  }
}

private class UiBuilderStructureViewModel(
  editor: Editor?,
  private val designFile: JsonFile,
) : TextEditorBasedStructureViewModel(editor, designFile) {
  override fun getRoot(): StructureViewTreeElement = UiBuilderDocumentTreeElement(designFile)

  override fun getSuitableClasses(): Array<Class<*>> = arrayOf(JsonProperty::class.java)
}

private class UiBuilderDocumentTreeElement(private val file: JsonFile) : StructureViewTreeElement {
  private val document: JsonObject?
    get() = file.topLevelValue as? JsonObject

  override fun getValue(): Any = file

  override fun getPresentation(): ItemPresentation =
    UiBuilderItemPresentation(
      document?.string("title")?.let { "Design · $it" } ?: "Compose UI Builder design"
    )

  override fun getChildren(): Array<TreeElement> {
    val nodes = document?.objectValue("nodes") ?: return emptyArray()
    val nodesById = nodes.propertyList.associateBy { it.name }
    val roots = document?.arrayValue("roots")?.stringValues().orEmpty()
    return roots
      .mapNotNull(nodesById::get)
      .map { property -> UiBuilderNodeTreeElement(property, nodesById, emptySet()) }
      .toTypedArray()
  }

  override fun navigate(requestFocus: Boolean) = (file as Navigatable).navigate(requestFocus)

  override fun canNavigate(): Boolean = (file as Navigatable).canNavigate()

  override fun canNavigateToSource(): Boolean = (file as Navigatable).canNavigateToSource()
}

private class UiBuilderNodeTreeElement(
  private val property: JsonProperty,
  private val nodesById: Map<String, JsonProperty>,
  private val ancestors: Set<String>,
) : StructureViewTreeElement {
  private val node: JsonObject?
    get() = property.value as? JsonObject

  private val id: String
    get() = node?.string("id") ?: property.name

  override fun getValue(): Any = property

  override fun getPresentation(): ItemPresentation =
    UiBuilderItemPresentation("$id · ${node?.string("componentId") ?: "unknown component"}")

  override fun getChildren(): Array<TreeElement> {
    if (id in ancestors) return emptyArray()

    val childIds =
      node?.objectValue("slots")?.propertyList.orEmpty().flatMap {
        it.arrayValue()?.stringValues().orEmpty()
      }
    return childIds
      .mapNotNull(nodesById::get)
      .map { child -> UiBuilderNodeTreeElement(child, nodesById, ancestors + id) }
      .toTypedArray()
  }

  override fun navigate(requestFocus: Boolean) = (property as Navigatable).navigate(requestFocus)

  override fun canNavigate(): Boolean = (property as Navigatable).canNavigate()

  override fun canNavigateToSource(): Boolean = (property as Navigatable).canNavigateToSource()
}

private class UiBuilderItemPresentation(private val text: String) : ItemPresentation {
  override fun getPresentableText(): String = text

  override fun getIcon(unused: Boolean): Icon? = null
}

private fun JsonObject.string(name: String): String? =
  (findProperty(name)?.value as? JsonStringLiteral)?.value

private fun JsonObject.objectValue(name: String): JsonObject? =
  findProperty(name)?.value as? JsonObject

private fun JsonProperty.arrayValue(): JsonArray? = value as? JsonArray

private fun JsonObject.arrayValue(name: String): JsonArray? = findProperty(name)?.arrayValue()

private fun JsonArray.stringValues(): List<String> = valueList.mapNotNull {
  (it as? JsonStringLiteral)?.value
}
