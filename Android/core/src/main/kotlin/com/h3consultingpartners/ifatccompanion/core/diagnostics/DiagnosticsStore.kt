package com.h3consultingpartners.ifatccompanion.core.diagnostics

import com.h3consultingpartners.ifatccompanion.core.persistence.DiagnosticsSnapshot
import com.h3consultingpartners.ifatccompanion.core.platform.Clock
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticCategory
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticLevel
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticRecord
import com.h3consultingpartners.ifatccompanion.core.platform.DiagnosticsSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The bounded log the Diagnostics screen renders, and the sink every engine writes
 * to. Ported from `IFATCCompanion/Diagnostics/DiagnosticsStore.swift`, which on iOS
 * is an `ObservableObject` holding a capped array.
 *
 * Safe to call from any thread: writes are synchronized and the resulting snapshot is
 * published on a [StateFlow] the UI collects.
 */
class DiagnosticsStore(
    private val clock: Clock = Clock.system,
    private val capacity: Int = DEFAULT_CAPACITY,
) : DiagnosticsSink {

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticRecord>(capacity)
    private val _records = MutableStateFlow<List<DiagnosticRecord>>(emptyList())

    /** Newest last, matching the transcript-style reading order the iOS list uses. */
    val records: StateFlow<List<DiagnosticRecord>> = _records.asStateFlow()

    override fun log(
        category: DiagnosticCategory,
        level: DiagnosticLevel,
        message: String,
    ) {
        val record = DiagnosticRecord(clock.nowMillis(), category, level, message)
        val snapshot = synchronized(lock) {
            buffer.addLast(record)
            while (buffer.size > capacity) buffer.removeFirst()
            buffer.toList()
        }
        _records.value = snapshot
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _records.value = emptyList()
    }

    /** The whole log as shareable plain text, for the Diagnostics screen's share action. */
    fun exportText(): String = _records.value.joinToString("\n") { format(it) }

    /**
     * Replace the log with the one saved alongside a flight.
     *
     * Loading a saved flight puts the app back where that flight was, and the log is part
     * of that: it is what makes a flight's history inspectable after the fact, which is the
     * whole reason a saved flight carries one. Capped like any other run, so a long saved
     * log cannot push the store past its bound.
     */
    fun restore(snapshot: DiagnosticsSnapshot) {
        val records = snapshot.toRecords().takeLast(capacity)
        // Through the buffer, not straight to the flow: the buffer is what the next log()
        // appends to, so filling only the flow would publish the saved log once and then
        // have the very next line replace it with a one-entry list.
        val published = synchronized(lock) {
            buffer.clear()
            buffer.addAll(records)
            buffer.toList()
        }
        _records.value = published
    }

    companion object {
        /**
         * Matches the iOS store's cap. Large enough to hold a full gate-to-gate flight's
         * connection and ATC trace, small enough that the list stays responsive.
         */
        const val DEFAULT_CAPACITY = 500

        private val timeFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        fun format(record: DiagnosticRecord): String {
            val time = timeFormatter.format(Instant.ofEpochMilli(record.timestampMillis))
            val level = when (record.level) {
                DiagnosticLevel.DEBUG -> "DEBUG"
                DiagnosticLevel.INFO -> "INFO"
                DiagnosticLevel.WARNING -> "WARN"
                DiagnosticLevel.ERROR -> "ERROR"
            }
            return "$time  [${record.category.label}] $level  ${record.message}"
        }
    }
}
