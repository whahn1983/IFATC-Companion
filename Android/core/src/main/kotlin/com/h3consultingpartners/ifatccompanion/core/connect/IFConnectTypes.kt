package com.h3consultingpartners.ifatccompanion.core.connect

/**
 * High-level connection state for the Infinite Flight Connect link.
 * Ported from `IFATCCompanion/Connect/IFConnectConnectionState.swift`.
 */
sealed interface IFConnectConnectionState {
    data object Disconnected : IFConnectConnectionState
    data object Discovering : IFConnectConnectionState
    data object Connecting : IFConnectConnectionState

    /**
     * TCP is up and the manifest is being read — partial data is still arriving.
     * Distinct from [Connecting] so the UI can show "Receiving manifest…" and the user
     * knows the link is progressing rather than stalled.
     */
    data object ReceivingManifest : IFConnectConnectionState
    data object Connected : IFConnectConnectionState

    /**
     * [reason] is the short, space-constrained summary shown in compact UI (e.g. the
     * ATC view status pill). [detail], when present, is the fuller message — including
     * any recovery instructions — surfaced where there's room (Settings).
     */
    data class Failed(val reason: String, val detail: String? = null) : IFConnectConnectionState

    /** Compact status string for space-constrained UI. Always uses the short reason. */
    val title: String
        get() = when (this) {
            Disconnected -> "Disconnected"
            Discovering -> "Searching…"
            Connecting -> "Connecting…"
            ReceivingManifest -> "Receiving manifest…"
            Connected -> "Connected"
            is Failed -> "Failed: $reason"
        }

    /**
     * Fuller status string for UI with room for detail (e.g. the Settings page). Falls
     * back to [title] when there's no extended detail.
     */
    val detailedTitle: String
        get() = when (this) {
            is Failed -> "Failed: ${detail ?: reason}"
            else -> title
        }

    val isConnected: Boolean get() = this is Connected

    val isActive: Boolean
        get() = when (this) {
            Connecting, Discovering, ReceivingManifest, Connected -> true
            else -> false
        }
}

/**
 * Errors surfaced by the Connect client. All are non-fatal — the app degrades to
 * manual/mock operation.
 */
sealed class IFConnectError(message: String) : Exception(message) {
    data object NotConnected : IFConnectError("Not connected to Infinite Flight.")
    data object InvalidHost : IFConnectError("Invalid host or port.")
    data object Timeout : IFConnectError("The connection timed out.")
    data class ConnectionFailed(val reason: String) : IFConnectError("Connection failed: $reason")
    data object ManifestUnavailable : IFConnectError("Manifest Unavailable")
    data object UnknownState : IFConnectError("Requested state is not available.")
    data object DecodingFailed : IFConnectError("Failed to decode a response.")
    data object Cancelled : IFConnectError("Operation cancelled.")

    /** Mirrors the Swift `errorDescription`. */
    val errorDescription: String get() = message ?: "Connection error."

    val recoverySuggestion: String?
        get() = when (this) {
            ManifestUnavailable -> MANIFEST_RECOVERY_SUGGESTION
            else -> null
        }

    companion object {
        const val MANIFEST_RECOVERY_SUGGESTION =
            "Try force closing Infinite Flight and IFATC Companion, then open Infinite " +
                "Flight first and then the Companion again. Make sure Infinite Flight is " +
                "fully loaded into an active flight — not the main menu — before connecting."
    }
}

/** A decoded value read from a Connect state. */
sealed interface IFStateValue {
    data class BoolValue(val value: Boolean) : IFStateValue
    data class IntValue(val value: Int) : IFStateValue
    data class FloatValue(val value: Float) : IFStateValue
    data class DoubleValue(val value: Double) : IFStateValue
    data class LongValue(val value: Long) : IFStateValue
    data class StringValue(val value: String) : IFStateValue

    val doubleValue: Double?
        get() = when (this) {
            is BoolValue -> if (value) 1.0 else 0.0
            is IntValue -> value.toDouble()
            is FloatValue -> value.toDouble()
            is DoubleValue -> value
            is LongValue -> value.toDouble()
            is StringValue -> null
        }

    val boolValue: Boolean?
        get() = when (this) {
            is BoolValue -> value
            is IntValue -> value != 0
            else -> null
        }

    val stringValue: String?
        get() = (this as? StringValue)?.value
}

/** Data types used by the Infinite Flight Connect API v2 manifest. */
enum class IFDataType(val rawValue: Int) {
    BOOLEAN(0),
    INT32(1),
    FLOAT(2),
    DOUBLE(3),
    STRING(4),
    LONG(5),
    UNKNOWN(-1),
    ;

    val shortName: String
        get() = when (this) {
            BOOLEAN -> "bool"
            INT32 -> "int"
            FLOAT -> "float"
            DOUBLE -> "double"
            STRING -> "string"
            LONG -> "long"
            UNKNOWN -> "?"
        }

    /** Byte length for fixed-width types (null for variable-length string). */
    val byteLength: Int?
        get() = when (this) {
            BOOLEAN -> 1
            INT32, FLOAT -> 4
            DOUBLE, LONG -> 8
            STRING, UNKNOWN -> null
        }

    companion object {
        fun fromRaw(raw: Int): IFDataType = entries.firstOrNull { it.rawValue == raw } ?: UNKNOWN
    }
}

/** A single entry (state or command) from the Connect manifest. */
data class IFManifestEntry(
    val id: Int,
    val type: IFDataType,
    val name: String,
) {
    /** Normalised name for keyword matching: lowercased, separators removed. */
    val matchKey: String
        get() = name.lowercase().filter { it.isLetter() || it.isDigit() }
}

/**
 * Parses the raw manifest string returned by Connect v2.
 * Expected per-entry format: `id,type,name` separated by newlines.
 */
object IFManifestParser {
    fun parse(raw: String): List<IFManifestEntry> {
        val entries = mutableListOf<IFManifestEntry>()
        for (line in raw.split('\n', '\r')) {
            if (line.isEmpty()) continue
            // maxSplits 2 in Swift means at most 3 pieces — the name may contain commas.
            val parts = line.split(",", limit = 3)
            if (parts.size < 3) continue
            val id = parts[0].trim().toIntOrNull() ?: continue
            val typeRaw = parts[1].trim().toIntOrNull() ?: continue
            entries += IFManifestEntry(id, IFDataType.fromRaw(typeRaw), parts[2].trim())
        }
        return entries
    }
}
