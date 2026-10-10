package ee.schimke.composeai.uibuilder.guidelines

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidelineKeyStoreTest {
  private class MapStore : GuidelineKeyValueStore {
    val values = mutableMapOf<String, String>()

    override fun get(key: String) = values[key]

    override fun set(key: String, value: String?) {
      if (value.isNullOrBlank()) values.remove(key) else values[key] = value
    }
  }

  private val device = MapStore()
  private val session = MapStore()
  private val store = GuidelineKeyStore(device, session)

  @Test
  fun `a key is kept for the session only by default`() {
    store.storeKey("sk-or-1")
    assertEquals("sk-or-1", store.key())
    assertFalse(store.remembered)
    assertEquals("sk-or-1", session.values[GuidelineKeyStore.KEY_STORAGE])
    assertNull(device.values[GuidelineKeyStore.KEY_STORAGE])
  }

  @Test
  fun `remembering moves the key to the device, and unticking removes it from there`() {
    store.storeKey("sk-or-1")
    store.setRemembered(true)
    assertTrue(store.remembered)
    assertEquals("sk-or-1", device.values[GuidelineKeyStore.KEY_STORAGE])
    assertNull(session.values[GuidelineKeyStore.KEY_STORAGE])
    assertEquals("sk-or-1", GuidelineKeyStore(device, MapStore()).key())

    store.setRemembered(false)
    assertFalse(store.remembered)
    assertNull(device.values[GuidelineKeyStore.KEY_STORAGE])
    assertNull(device.values[GuidelineKeyStore.REMEMBER_STORAGE])
    assertEquals("sk-or-1", store.key())
  }

  @Test
  fun `a key saved while remembering goes to the device`() {
    store.setRemembered(true)
    store.storeKey("sk-or-2")
    assertEquals("sk-or-2", device.values[GuidelineKeyStore.KEY_STORAGE])
    assertNull(session.values[GuidelineKeyStore.KEY_STORAGE])
  }

  @Test
  fun `forgetting removes the key from both places`() {
    store.setRemembered(true)
    store.storeKey("sk-or-1")
    store.storeKey(null)
    assertNull(store.key())
    assertTrue(device.values.keys.none { it == GuidelineKeyStore.KEY_STORAGE })
    assertTrue(session.values.isEmpty())
  }

  @Test
  fun `a key an earlier version left on the device without the opt-in moves to the session`() {
    device.values[GuidelineKeyStore.KEY_STORAGE] = "sk-or-old"
    assertEquals("sk-or-old", store.key())
    assertNull(device.values[GuidelineKeyStore.KEY_STORAGE])
    assertEquals("sk-or-old", session.values[GuidelineKeyStore.KEY_STORAGE])
  }

  @Test
  fun `a legacy key stays on the device when the session refuses it`() {
    val refusing =
      object : GuidelineKeyValueStore {
        override fun get(key: String): String? = null

        override fun set(key: String, value: String?) {}
      }
    device.values[GuidelineKeyStore.KEY_STORAGE] = "sk-or-old"
    assertEquals("sk-or-old", GuidelineKeyStore(device, refusing).key())
    assertEquals("sk-or-old", device.values[GuidelineKeyStore.KEY_STORAGE])
  }
}
