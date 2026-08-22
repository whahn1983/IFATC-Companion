package com.h3consultingpartners.ifatccompanion.core.persistence

import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The Diagnostics log as it travels with a saved flight, so a flight's history is
 * inspectable after it is loaded back.
 *
 * Ported from `DiagnosticsSnapshot` in
 * `IFATCCompanion/Diagnostics/DiagnosticsStore.swift`. The entry shape follows the
 * Kotlin [DiagnosticRecord] (which carries a level the Swift `Entry` does not), and
 * the two enums are written as their names through the serializers below — the enums
 * live in `core.platform`, which stays free of a serialization dependency.
 */
@Serializable
data class DiagnosticsSnapshot(
    val entries: List<Entry> = emptyList(),
    val weatherEndpointStatus: String = "Not checked",
    val atisEndpointStatus: String = "Not checked",
    val lastRawMessage: String = "",
) {

    /**
     * One logged line. [id] is a stored property, not a computed one, so a restored
     * entry keeps the identity it was saved with — the same reason the Swift `Entry`
     * declares `var id = UUID()` rather than a `let`.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Serializable
    data class Entry(
        val id: String = Uuid.random().toString(),
        /** Epoch milliseconds. */
        val timestampMillis: Long,
        @Serializable(with = DiagnosticCategorySerializer::class)
        val category: DiagnosticCategory,
        @Serializable(with = DiagnosticLevelSerializer::class)
        val level: DiagnosticLevel = DiagnosticLevel.INFO,
        val message: String,
    ) {
        fun toRecord(): DiagnosticRecord =
            DiagnosticRecord(timestampMillis, category, level, message)
    }

    /** The saved entries as the records the diagnostics store holds. */
    fun toRecords(): List<DiagnosticRecord> = entries.map { it.toRecord() }

    companion object {
        /** Capture a run of diagnostics records for a saved flight. */
        fun from(
            records: List<DiagnosticRecord>,
            weatherEndpointStatus: String = "Not checked",
            atisEndpointStatus: String = "Not checked",
            lastRawMessage: String = "",
        ): DiagnosticsSnapshot = DiagnosticsSnapshot(
            entries = records.map {
                Entry(
                    timestampMillis = it.timestampMillis,
                    category = it.category,
                    level = it.level,
                    message = it.message,
                )
            },
            weatherEndpointStatus = weatherEndpointStatus,
            atisEndpointStatus = atisEndpointStatus,
            lastRawMessage = lastRawMessage,
        )
    }
}

/**
 * Writes a [DiagnosticCategory] as its enum name. An unrecognized name decodes to
 * [DiagnosticCategory.GENERAL] rather than throwing, so a log written by a build with
 * a category this one doesn't know still loads the rest of the flight.
 */
object DiagnosticCategorySerializer : KSerializer<DiagnosticCategory> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DiagnosticCategory", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DiagnosticCategory) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): DiagnosticCategory {
        val raw = decoder.decodeString()
        return DiagnosticCategory.entries.firstOrNull { it.name == raw }
            ?: DiagnosticCategory.GENERAL
    }
}

/** Writes a [DiagnosticLevel] as its enum name; an unknown name decodes to INFO. */
object DiagnosticLevelSerializer : KSerializer<DiagnosticLevel> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DiagnosticLevel", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: DiagnosticLevel) =
        encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): DiagnosticLevel {
        val raw = decoder.decodeString()
        return DiagnosticLevel.entries.firstOrNull { it.name == raw } ?: DiagnosticLevel.INFO
    }
}
