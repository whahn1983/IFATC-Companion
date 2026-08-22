package com.h3consultingpartners.ifatccompanion.core.settings

import com.h3consultingpartners.ifatccompanion.core.platform.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The automatic gate assignment as the pilot meets it: a Settings toggle that is **off** on a
 * fresh install, fills only a gate field that was left blank, and hands back what it filled in
 * when it is switched off again.
 *
 * Ported from `IFATCCompanionTests/GateAutoAssignSettingTests.swift` — the settings half. The
 * assignment behaviour itself belongs to the surface/gate-assigner port and is tested there.
 */
class GateAutoAssignSettingTest {

    @Test
    fun testTheFeatureIsOffOnAFreshInstallAndPersistsWhenSwitchedOn() {
        val store = InMemoryKeyValueStore()
        val settings = SettingsRepository(store)
        assertFalse(
            settings.settings.autoAssignGates,
            "automatic gate assignment is off on a fresh install",
        )

        settings.setAutoAssignGates(true)
        val relaunched = SettingsRepository(store)
        assertTrue(
            relaunched.settings.autoAssignGates,
            "the choice survives the next launch",
        )
    }
}
