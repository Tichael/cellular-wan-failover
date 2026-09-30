package com.example.cellularwanfailover

import android.content.SharedPreferences
import com.example.cellularwanfailover.network.NetworkUtils
import com.example.cellularwanfailover.network.TrustedNetworkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrustedNetworkManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var manager: TrustedNetworkManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        manager = TrustedNetworkManager(fakePrefs)
    }

    @Test
    fun testNormalizeSsid() {
        // Normal SSID
        assertEquals("HomeWiFi", NetworkUtils.normalizeSsid("HomeWiFi"))
        // Quoted SSID
        assertEquals("HomeWiFi", NetworkUtils.normalizeSsid("\"HomeWiFi\""))
        assertEquals("HomeWiFi", NetworkUtils.normalizeSsid("  \"HomeWiFi\"  "))
        // Empty or whitespace
        assertNull(NetworkUtils.normalizeSsid(""))
        assertNull(NetworkUtils.normalizeSsid("   "))
        assertNull(NetworkUtils.normalizeSsid("\"\""))
        // Unknown SSID
        assertNull(NetworkUtils.normalizeSsid("<unknown ssid>"))
        assertNull(NetworkUtils.normalizeSsid("\"<unknown ssid>\""))
        assertNull(NetworkUtils.normalizeSsid(null))
    }

    @Test
    fun testInitialStateIsEmpty() {
        assertTrue(manager.trustedNetworks.value.isEmpty())
        assertFalse(manager.isTrusted("HomeWiFi"))
    }

    @Test
    fun testAddNetworkSuccess() {
        val added = manager.addNetwork("HomeWiFi", "aa:bb:cc:dd:ee:ff")
        assertTrue(added)

        val list = manager.trustedNetworks.value
        assertEquals(1, list.size)
        assertEquals("HomeWiFi", list[0].ssid)
        assertEquals("aa:bb:cc:dd:ee:ff", list[0].bssid)
        assertNotNull(list[0].id)
        assertTrue(manager.isTrusted("HomeWiFi"))
    }

    @Test
    fun testAddNetworkNormalizesQuotes() {
        val added = manager.addNetwork("\"MyMeshNetwork\"")
        assertTrue(added)

        val list = manager.trustedNetworks.value
        assertEquals(1, list.size)
        assertEquals("MyMeshNetwork", list[0].ssid)

        // Matching should work both with and without quotes
        assertTrue(manager.isTrusted("MyMeshNetwork"))
        assertTrue(manager.isTrusted("\"MyMeshNetwork\""))
    }

    @Test
    fun testAddDuplicateNetworkRejected() {
        assertTrue(manager.addNetwork("HomeWiFi"))
        assertFalse(manager.addNetwork("HomeWiFi"))
        assertFalse(manager.addNetwork("\"HomeWiFi\""))
        assertEquals(1, manager.trustedNetworks.value.size)
    }

    @Test
    fun testAddInvalidNetworkRejected() {
        assertFalse(manager.addNetwork(""))
        assertFalse(manager.addNetwork("   "))
        assertFalse(manager.addNetwork("<unknown ssid>"))
        assertTrue(manager.trustedNetworks.value.isEmpty())
    }

    @Test
    fun testRemoveNetwork() {
        manager.addNetwork("Net1")
        manager.addNetwork("Net2")
        assertEquals(2, manager.trustedNetworks.value.size)

        val idToRemove = manager.trustedNetworks.value.first { it.ssid == "Net1" }.id
        val removed = manager.removeNetwork(idToRemove)
        assertTrue(removed)

        assertEquals(1, manager.trustedNetworks.value.size)
        assertFalse(manager.isTrusted("Net1"))
        assertTrue(manager.isTrusted("Net2"))
    }

    @Test
    fun testRemoveNonExistentNetwork() {
        manager.addNetwork("Net1")
        val removed = manager.removeNetwork("non-existent-id")
        assertFalse(removed)
        assertEquals(1, manager.trustedNetworks.value.size)
    }

    @Test
    fun testClearAll() {
        manager.addNetwork("Net1")
        manager.addNetwork("Net2")
        manager.addNetwork("Net3")
        assertEquals(3, manager.trustedNetworks.value.size)

        manager.clearAll()
        assertTrue(manager.trustedNetworks.value.isEmpty())
        assertFalse(manager.isTrusted("Net1"))
        assertFalse(manager.isTrusted("Net2"))
        assertFalse(manager.isTrusted("Net3"))
    }

    @Test
    fun testPersistenceAcrossInstances() {
        manager.addNetwork("PersistedNet", "00:11:22:33:44:55")

        // Create new instance with same SharedPreferences
        val newManager = TrustedNetworkManager(fakePrefs)
        assertEquals(1, newManager.trustedNetworks.value.size)
        assertEquals("PersistedNet", newManager.trustedNetworks.value[0].ssid)
        assertEquals("00:11:22:33:44:55", newManager.trustedNetworks.value[0].bssid)
        assertTrue(newManager.isTrusted("PersistedNet"))
    }

    @Test
    fun testIsTrustedUntrustedScenarios() {
        manager.addNetwork("HomeNet")

        assertFalse(manager.isTrusted(null))
        assertFalse(manager.isTrusted(""))
        assertFalse(manager.isTrusted("<unknown ssid>"))
        assertFalse(manager.isTrusted("ForeignCafeWiFi"))
        assertTrue(manager.isTrusted("HomeNet"))
    }
}

class FakeSharedPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = data[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { pending[it] = values }
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { pending[it] = null }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) data.clear()
            for ((k, v) in pending) {
                if (v == null) data.remove(k) else data[k] = v
            }
        }
    }
}
