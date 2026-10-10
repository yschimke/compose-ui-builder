package ee.schimke.composeai.uibuilder.guidelines

/** One string-keyed storage area, such as a browser's `localStorage` or `sessionStorage`. */
interface GuidelineKeyValueStore {
  /** The stored value, or null (or blank) when there is none. */
  fun get(key: String): String?

  /** Stores [value], or removes the entry when it is null or blank. */
  fun set(key: String, value: String?)
}

/**
 * Where a person's own OpenRouter key is kept: in [session] (this tab, gone when it closes) unless
 * they chose "Remember on this device", which keeps it in [device] instead.
 *
 * The device store is shared by every page on the editor's origin, and that origin may serve other
 * apps, so the key only goes there on an explicit opt-in, and turning the opt-in off removes it. A
 * key only ever lives in one of the two places.
 *
 * A key a previous version left in [device] without the opt-in is moved into [session] the first
 * time it is read, so an existing user keeps working in this tab and the copy every other page on
 * the origin could read is gone.
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
   * Turns "Remember on this device" on or off, moving the current key with it: on copies it to the
   * device store, off removes it from there and keeps it for this tab only.
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
