package com.h3consultingpartners.ifatccompanion.core.phraseology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The declarative gate-to-gate call catalog and the audit invariants
 * `docs/PhraseologyAudit.md` records for it.
 *
 * `Resources/GateToGateCallCatalog.json` is copied verbatim into the module's Java
 * resources, so the first thing to prove is that it is still packaged and still
 * parses — a resource that silently fails to ship is the failure mode this guards.
 * The rest are the audit's own claims: 29 calls, every facility the catalog names is
 * one it declares, Ramp is marked simulated rather than FAA, and no template in the
 * catalog carries phraseology the validator blocks.
 */
class GateToGateCallCatalogTest {

    private val catalog = GateToGateCallCatalog.load()

    @Test
    fun testCatalogLoadsFromTheClasspath() {
        assertNotNull(GateToGateCallCatalog.loadOrNull())
        assertEquals("1.0", catalog.schemaVersion)
        assertEquals("FAA", catalog.phraseologyAuthority)
        assertEquals("FAA", catalog.mode)
    }

    @Test
    fun testCatalogHoldsEveryGateToGateCall() {
        assertEquals(29, catalog.calls.size)
        val ids = catalog.calls.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate call id in the catalog")
        assertTrue(ids.none { it.isBlank() })
        // Spot-check both ends of the flight so a truncated copy is caught.
        assertNotNull(catalog.call("voice-clearance"))
        assertNotNull(catalog.call("atis-received"))
    }

    @Test
    fun testEveryCallNamesADeclaredFacility() {
        val declared = catalog.facilities.toSet()
        for (call in catalog.calls) {
            assertTrue(
                declared.contains(call.facility),
                "call ${call.id} names undeclared facility ${call.facility}",
            )
        }
    }

    /**
     * Ramp / apron / company-ramp and the ground-crew interphone are *simulated local
     * procedure*, not FAA ATC. The audit's central correction was modelling that
     * separation, and the review status is where the catalog records it.
     */
    @Test
    fun testRampCallsAreMarkedSimulatedNotFAA() {
        val rampFacilities = setOf("ramp", "arrivalRamp", "groundCrewInterphone")
        val ramp = catalog.calls.filter { rampFacilities.contains(it.facility) }
        assertTrue(ramp.isNotEmpty(), "the catalog must describe the ramp calls")
        for (call in ramp) {
            assertEquals("simulated", call.reviewStatus, "ramp call ${call.id} is not marked simulated")
        }
    }

    /** No template anywhere in the catalog contains blocked phraseology. */
    @Test
    fun testCatalogTemplatesAreFreeOfBlockedPhraseology() {
        val validator = PhraseologyValidator()
        for (call in catalog.calls) {
            for (template in listOfNotNull(call.atcTemplate, call.rampTemplate, call.pilotReadbackTemplate)) {
                assertTrue(
                    validator.isClean(template),
                    "blocked phrase in ${call.id}: $template",
                )
            }
        }
    }
}
