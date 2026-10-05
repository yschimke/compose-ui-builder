package ee.schimke.composeai.uibuilder.export.production

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive

class ProductionAdapterAbiTest {
  @Test
  fun `released constructors and default copy methods still link and retain new metadata`() {
    val component =
      ProductionComponentUse(
        "node",
        "child",
        listOf("items"),
        emptyMap(),
        ProductionNullPolicy.SKIP,
        listOf("id"),
      )
    val type = ProductionComponentUse::class.java
    type.getConstructor(String::class.java, String::class.java, List::class.java, Map::class.java)
    type.getConstructor(
      String::class.java,
      String::class.java,
      List::class.java,
      Map::class.java,
      Int::class.javaPrimitiveType,
      Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
    )
    val copy =
      type.getMethod(
        "copy\$default",
        type,
        String::class.java,
        String::class.java,
        List::class.java,
        Map::class.java,
        Int::class.javaPrimitiveType,
        Any::class.java,
      )
    assertEquals(component, copy.invoke(null, component, null, null, null, null, 15, null))
    val binding =
      ProductionBinding(
        "node",
        "text",
        listOf("title"),
        ProductionType.Scalar(ScalarType.STRING),
        JsonPrimitive("Fallback"),
      )
    val bindingType = ProductionBinding::class.java
    bindingType.getConstructor(
      String::class.java,
      String::class.java,
      List::class.java,
      ProductionType::class.java,
    )
    val bindingCopy =
      bindingType.getMethod(
        "copy\$default",
        bindingType,
        String::class.java,
        String::class.java,
        List::class.java,
        ProductionType::class.java,
        Int::class.javaPrimitiveType,
        Any::class.java,
      )
    assertEquals(binding, bindingCopy.invoke(null, binding, null, null, null, null, 15, null))
  }
}
