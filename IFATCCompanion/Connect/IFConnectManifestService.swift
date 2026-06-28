import Foundation

/// Discovers the Connect manifest and resolves it into a state mapping.
struct IFConnectManifestService {

    /// Fetch the manifest and resolve logical state mappings into `store`.
    /// Returns the full entry list (also for Diagnostics display).
    func discover(using client: IFConnectClient, into store: IFStateMappingStore) async throws -> [IFManifestEntry] {
        let entries = try await client.requestManifest()
        store.resolve(from: entries)
        return entries
    }
}
