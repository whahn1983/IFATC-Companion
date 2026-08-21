package com.h3consultingpartners.ifatccompanion.core.connect

/**
 * Discovers the Connect manifest and resolves it into a state mapping.
 *
 * Ported from `IFATCCompanion/Connect/IFConnectManifestService.swift`. Note the
 * ordering it fixes: the store is resolved **before** the entries are handed back, so a
 * caller logging `mappingStore.resolved.size` beside the entry count sees a populated
 * mapping rather than an empty one.
 */
class IFConnectManifestService {

    /**
     * Fetch the manifest and resolve logical state mappings into [store].
     * Returns the full entry list (also for Diagnostics display). [onEvent] forwards
     * the client's granular progress so the manager can log it and drive the
     * "Receiving manifest…" status.
     */
    suspend fun discover(
        client: IFConnectClient,
        store: IFStateMappingStore,
        onEvent: (IFConnectManifestEvent) -> Unit = {},
    ): List<IFManifestEntry> {
        val entries = client.requestManifest(onEvent = onEvent)
        store.resolve(entries)
        return entries
    }
}
