package com.h3consultingpartners.ifatccompanion.core.platform

/**
 * Where the engine reports what it is doing. Ported from
 * `IFATCCompanion/Diagnostics/DiagnosticsStore.swift`, which on iOS is an
 * `ObservableObject` the Diagnostics tab renders. Here the ring buffer lives in
 * :core (so engine code can log without an Android dependency) and the UI observes
 * it through a StateFlow.
 */
enum class DiagnosticCategory(val label: String) {
    CONNECTION("Connection"),
    DISCOVERY("Discovery"),
    MANIFEST("Manifest"),
    STATE("State"),
    FLIGHT_PLAN("Flight Plan"),
    ATC("ATC"),
    WEATHER("Weather"),
    ATIS("ATIS"),
    SURFACE("Airport Surface"),
    TAXI("Taxi"),
    AUDIO("Audio"),
    BILLING("Billing"),
    SESSION("Session"),
    GENERAL("General"),
}

enum class DiagnosticLevel { DEBUG, INFO, WARNING, ERROR }

data class DiagnosticRecord(
    val timestampMillis: Long,
    val category: DiagnosticCategory,
    val level: DiagnosticLevel,
    val message: String,
)

/** The sink engines write to. Implementations must be safe to call from any thread. */
interface DiagnosticsSink {
    fun log(
        category: DiagnosticCategory,
        level: DiagnosticLevel = DiagnosticLevel.INFO,
        message: String,
    )

    companion object {
        /** Drops everything. Used by tests and by engines constructed without a sink. */
        val noop = object : DiagnosticsSink {
            override fun log(
                category: DiagnosticCategory,
                level: DiagnosticLevel,
                message: String,
            ) = Unit
        }
    }
}
