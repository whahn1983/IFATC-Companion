package com.h3consultingpartners.ifatccompanion.core.platform

/**
 * Blob persistence for things too large for [KeyValueStore]: the airport-surface
 * cache, saved flights, exported phraseology profiles. The Android implementation
 * writes under the app's private files directory; the in-memory one backs tests.
 */
interface FileStore {
    fun read(namespace: String, name: String): ByteArray?
    fun write(namespace: String, name: String, bytes: ByteArray)
    fun delete(namespace: String, name: String)
    fun list(namespace: String): List<String>
    /** Epoch millis the entry was last written, or null when it does not exist. */
    fun lastModified(namespace: String, name: String): Long?

    /**
     * Bytes the entry occupies, or null when it does not exist.
     *
     * The default reads the entry, which is what a size query had to do before this
     * existed — and summing a whole cache that way pulls every file into memory just to
     * discard it. A store backed by a real filesystem should override this with a stat.
     */
    fun sizeBytes(namespace: String, name: String): Long? =
        read(namespace, name)?.size?.toLong()
}

class InMemoryFileStore(private val clock: Clock = Clock.system) : FileStore {
    private data class Entry(val bytes: ByteArray, val modified: Long)

    private val files = LinkedHashMap<String, Entry>()
    private val lock = Any()

    private fun key(namespace: String, name: String) = "$namespace/$name"

    override fun read(namespace: String, name: String): ByteArray? =
        synchronized(lock) { files[key(namespace, name)]?.bytes }

    override fun write(namespace: String, name: String, bytes: ByteArray) =
        synchronized(lock) { files[key(namespace, name)] = Entry(bytes, clock.nowMillis()); Unit }

    override fun delete(namespace: String, name: String) =
        synchronized(lock) { files.remove(key(namespace, name)); Unit }

    override fun list(namespace: String): List<String> = synchronized(lock) {
        files.keys.filter { it.startsWith("$namespace/") }.map { it.removePrefix("$namespace/") }
    }

    override fun lastModified(namespace: String, name: String): Long? =
        synchronized(lock) { files[key(namespace, name)]?.modified }
}
