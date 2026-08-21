package com.h3consultingpartners.ifatccompanion.data

import com.h3consultingpartners.ifatccompanion.core.platform.FileStore
import java.io.File

/**
 * [FileStore] over the app's private storage — the blob persistence the engine uses
 * for the airport-surface cache, saved flights and exported phraseology profiles.
 *
 * Each namespace is a directory under [root]. Entry names are sanitised because they
 * come from data (an ICAO code, a flight name a pilot typed), and a name carrying a
 * path separator must never escape its namespace.
 */
class AndroidFileStore(private val root: File) : FileStore {

    private fun directory(namespace: String): File =
        File(root, sanitize(namespace)).apply { if (!exists()) mkdirs() }

    private fun file(namespace: String, name: String): File =
        File(directory(namespace), sanitize(name))

    override fun read(namespace: String, name: String): ByteArray? {
        val target = file(namespace, name)
        return if (target.isFile) runCatching { target.readBytes() }.getOrNull() else null
    }

    override fun write(namespace: String, name: String, bytes: ByteArray) {
        val target = file(namespace, name)
        // Write to a sibling temp file and rename, so an interrupted write (the process
        // being killed while a flight is saved) cannot leave a half-written entry that
        // decodes into a corrupt session.
        val temp = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }.onFailure { temp.delete() }
    }

    override fun delete(namespace: String, name: String) {
        runCatching { file(namespace, name).delete() }
    }

    override fun list(namespace: String): List<String> =
        directory(namespace).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.map { it.name }
            .orEmpty()

    override fun lastModified(namespace: String, name: String): Long? {
        val target = file(namespace, name)
        return if (target.isFile) target.lastModified() else null
    }

    private fun sanitize(value: String): String =
        value.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("")
            .ifEmpty { "unnamed" }
}
