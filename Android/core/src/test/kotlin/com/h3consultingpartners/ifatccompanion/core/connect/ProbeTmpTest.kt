package com.h3consultingpartners.ifatccompanion.core.connect

import kotlin.test.Test

class ProbeTmpTest {
    @Test
    fun probe() {
        val json = """{ "Waypoints": ["KTEB", "DPT", "SBJ", "TOC", "LRP", "TOD", "KPHL"] }"""
        println("SIMPLE=" + IFFlightPlanParser.parse(json))
        println("RWY09=" + IFFlightPlanParser.runwayIdent("RWY09"))
        val full = """{ "Waypoints": ["KTEB", "SBJ", "LRP", "KPHL"] }"""
        println("COORDS=" + IFFlightPlanParser.parse(full = full, route = null, coordinates = "40.58, -74.73; 40.12, -76.29"))
    }
}
