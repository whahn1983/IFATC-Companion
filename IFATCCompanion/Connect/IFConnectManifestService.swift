import Foundation

/// Discovers the Connect manifest and resolves it into a state mapping.
struct IFConnectManifestService {

    /// Fetch the manifest and resolve logical state mappings into `store`.
    /// Returns the full entry list (also for Diagnostics display). `onEvent` forwards
    /// the client's granular progress so the manager can log it and drive the
    /// "Receiving manifest…" status.
    func discover(using client: IFConnectClient,
                  into store: IFStateMappingStore,
                  onEvent: @Sendable @escaping (IFConnectManifestEvent) -> Void = { _ in }) async throws -> [IFManifestEntry] {
        let entries = try await client.requestManifest(onEvent: onEvent)
        store.resolve(from: entries)
        return entries
    }
}
