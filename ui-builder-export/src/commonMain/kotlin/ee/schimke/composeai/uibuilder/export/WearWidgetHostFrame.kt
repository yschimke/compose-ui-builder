package ee.schimke.composeai.uibuilder.export

/**
 * How a Wear widget's host frame — the padding and corner radius `WearWidgetParams` hands the
 * widget per host shape — gets into its exported document: the widget container's `hostFrame`
 * property (`remote-m3/widget-container-*`).
 *
 * @property id the property's value.
 * @property documentClass the `WearWidgetData` the generated widget returns.
 */
enum class WearWidgetHostFrame(val id: String, val documentClass: String) {
  /**
   * Upstream's `WearWidgetDocument`: the frame of the host the widget is captured for is written in
   * as constants. Right for a widget captured on the watch it plays on.
   */
  Host("host", "androidx.glance.wear.WearWidgetDocument"),

  /**
   * compose-ai-tools' `HostFramedWearWidgetDocument`: the same frame, declared as named floats the
   * device playing the document writes its own host's values into, so one capture fits every host
   * shape. For a widget captured once and served to many watches.
   */
  Device("device", "ee.schimke.composeai.wear.preview.HostFramedWearWidgetDocument");

  /** [documentClass] without its package: what the generated source calls. */
  val simpleName: String
    get() = documentClass.substringAfterLast('.')

  companion object {
    /** The container property that picks one. */
    const val PROPERTY: String = "hostFrame"

    /** The artifact [Device]'s document ships in, which a widget using it depends on. */
    const val DEVICE_ARTIFACT: String = "ee.schimke.composeai:wear-preview-runtime"

    /** [container]'s choice: [Host] when unset, null for a value that is neither. */
    fun of(container: UiBuilderNode): WearWidgetHostFrame? {
      val value = container.properties[PROPERTY]?.stringOrNull()?.trim()
      if (value.isNullOrEmpty()) return Host
      return entries.firstOrNull { it.id == value }
    }
  }
}
