package com.h3consultingpartners.ifatccompanion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.h3consultingpartners.ifatccompanion.core.platform.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ifatc_settings",
)

private val Context.entitlementDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "ifatc_entitlement",
)

/**
 * [KeyValueStore] backed by Jetpack DataStore — the Android replacement for the iOS
 * app's `UserDefaults`.
 *
 * DataStore is asynchronous, while the engine reads settings synchronously the way
 * `UserDefaults` does. Rather than push `suspend` through every call site, this holds
 * an in-memory snapshot that is loaded once by [load] before the first screen is
 * shown, then serves reads from that snapshot and writes through to disk on
 * [scope]. That is the same contract `UserDefaults` offers — a synchronous read of a
 * value that is persisted in the background — so the ported engine behaves the same.
 *
 * Keys are the exact strings the iOS app uses, so the two platforms' settings
 * vocabularies stay in step.
 */
class DataStoreKeyValueStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : KeyValueStore {

    private val cache = ConcurrentHashMap<String, Any>()

    /**
     * Read the persisted values into memory. Call once during application start-up,
     * before anything reads a setting.
     */
    suspend fun load() {
        val snapshot = dataStore.data.first()
        cache.clear()
        snapshot.asMap().forEach { (key, value) -> cache[key.name] = value }
    }

    override fun getString(key: String): String? = cache[key] as? String
    override fun getBoolean(key: String): Boolean? = cache[key] as? Boolean
    override fun getInt(key: String): Int? = (cache[key] as? Number)?.toInt()
    override fun getDouble(key: String): Double? = (cache[key] as? Number)?.toDouble()

    override fun putString(key: String, value: String?) =
        write(key, value) { prefs, v -> prefs[stringPreferencesKey(key)] = v }

    override fun putBoolean(key: String, value: Boolean?) =
        write(key, value) { prefs, v -> prefs[booleanPreferencesKey(key)] = v }

    override fun putInt(key: String, value: Int?) =
        write(key, value) { prefs, v -> prefs[intPreferencesKey(key)] = v }

    override fun putDouble(key: String, value: Double?) =
        write(key, value) { prefs, v -> prefs[doublePreferencesKey(key)] = v }

    override fun remove(key: String) {
        cache.remove(key)
        scope.launch {
            dataStore.edit { prefs ->
                prefs.asMap().keys.firstOrNull { it.name == key }?.let { prefs.remove(it) }
            }
        }
    }

    override fun contains(key: String): Boolean = cache.containsKey(key)

    private inline fun <T : Any> write(
        key: String,
        value: T?,
        crossinline apply: (androidx.datastore.preferences.core.MutablePreferences, T) -> Unit,
    ) {
        if (value == null) {
            remove(key)
            return
        }
        // Update the in-memory snapshot first so the very next read sees the new value,
        // exactly as UserDefaults would.
        cache[key] = value
        scope.launch { dataStore.edit { prefs -> apply(prefs, value) } }
    }

    companion object {
        fun settings(context: Context, scope: CoroutineScope) =
            DataStoreKeyValueStore(context.applicationContext.settingsDataStore, scope)

        /**
         * A separate store for the cached Play entitlement. Kept apart from settings so
         * the backup rules can carry preferences between devices while never carrying an
         * entitlement — see res/xml/backup_rules.xml.
         */
        fun entitlement(context: Context, scope: CoroutineScope) =
            DataStoreKeyValueStore(context.applicationContext.entitlementDataStore, scope)
    }
}
