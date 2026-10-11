package ee.schimke.composeai.uibuilder.guidelines

/** One string-keyed storage area, such as a browser's `localStorage` or `sessionStorage`. */
interface GuidelineKeyValueStore {
  /** The stored value, or null (or blank) when there is none. */
  fun get(key: String): String?

  /** Stores [value], or removes the entry when it is null or blank. */
  fun set(key: String, value: String?)
}

/**
 * Where a person's own OpenRouter key is kept: [session] (this tab) unless they opt into "Remember
 * on this device", which uses [device]. The device store is shared by every app on the origin, so
 * it's opt-in only, and the key lives in exactly one place.
 *
 * A key left in [device] without the opt-in is moved into [session] on first read.
 */
class GuidelineKeyStore(
  private val device: GuidelineKeyValueStore,
  private val session: GuidelineKeyValueStore,
) {
  /** Whether this person chose to keep the key on this device. */
  val remembered: Boolean
    get() = device.get(REMEMBER_STORAGE) == "1"

  fun key(): String? {
    if (remembered) return device.get(KEY_STORAGE)?.takeIf { it.isNotBlank() }
    device
      .get(KEY_STORAGE)
      ?.takeIf { it.isNotBlank() }
      ?.let { legacy ->
        if (session.get(KEY_STORAGE).isNullOrBlank()) session.set(KEY_STORAGE, legacy)
        // Only drop the device copy once the session holds a key: a session store that refuses
        // the write (unavailable, over quota) must not cost the person their only copy.
        if (session.get(KEY_STORAGE).isNullOrBlank()) return legacy
        device.set(KEY_STORAGE, null)
      }
    return session.get(KEY_STORAGE)?.takeIf { it.isNotBlank() }
  }

  /** Stores [key] where the person's choice says, or removes it everywhere when null. */
  fun storeKey(key: String?) {
    val value = key?.takeIf { it.isNotBlank() }
    if (value != null && remembered) {
      device.set(KEY_STORAGE, value)
      session.set(KEY_STORAGE, null)
    } else {
      session.set(KEY_STORAGE, value)
      device.set(KEY_STORAGE, null)
    }
  }

  /**
   * Turns "Remember on this device" on or off, moving the current key: on copies it to the device
   * store, off removes it there and keeps it for this tab.
   */
  fun setRemembered(remember: Boolean) {
    val current = key()
    device.set(REMEMBER_STORAGE, if (remember) "1" else null)
    storeKey(current)
  }

  companion object {
    const val KEY_STORAGE = "ui-builder.guidelines.openrouter-key"
    const val REMEMBER_STORAGE = "ui-builder.guidelines.remember-key"
  }
}
