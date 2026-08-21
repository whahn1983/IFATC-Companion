package com.h3consultingpartners.ifatccompanion.core.platform

/**
 * The persistence contract the engine depends on — the port that stands in for
 * `UserDefaults` on iOS. The Android implementation is backed by Jetpack DataStore
 * (see `app/.../data/DataStoreKeyValueStore.kt`); the in-memory implementation below
 * backs the unit tests.
 *
 * Keys are the *same strings* the iOS app uses, so the two platforms' settings
 * vocabularies stay in step and a future shared-settings feature has one namespace.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean?
    fun getInt(key: String): Int?
    fun getDouble(key: String): Double?

    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean?)
    fun putInt(key: String, value: Int?)
    fun putDouble(key: String, value: Double?)

    fun remove(key: String)
    fun contains(key: String): Boolean
}

/** Thread-safe in-memory store for tests and for engines constructed without storage. */
class InMemoryKeyValueStore(initial: Map<String, Any> = emptyMap()) : KeyValueStore {
    private val values = LinkedHashMap<String, Any>(initial)
    private val lock = Any()

    private fun <T> read(key: String, cast: (Any) -> T?): T? = synchronized(lock) {
        values[key]?.let(cast)
    }

    private fun write(key: String, value: Any?) = synchronized(lock) {
        if (value == null) values.remove(key) else values[key] = value
        Unit
    }

    override fun getString(key: String) = read(key) { it as? String }
    override fun getBoolean(key: String) = read(key) { it as? Boolean }
    override fun getInt(key: String) = read(key) { (it as? Number)?.toInt() }
    override fun getDouble(key: String) = read(key) { (it as? Number)?.toDouble() }

    override fun putString(key: String, value: String?) = write(key, value)
    override fun putBoolean(key: String, value: Boolean?) = write(key, value)
    override fun putInt(key: String, value: Int?) = write(key, value)
    override fun putDouble(key: String, value: Double?) = write(key, value)

    override fun remove(key: String) = write(key, null)
    override fun contains(key: String) = synchronized(lock) { values.containsKey(key) }

    fun snapshot(): Map<String, Any> = synchronized(lock) { LinkedHashMap(values) }
}
