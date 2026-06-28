import Foundation

/// Sends commands (e.g. UNICOM broadcasts) to Infinite Flight via Connect.
/// Reports success/failure so callers can degrade gracefully.
struct IFConnectCommandSender {

    /// Run a resolved command id. Returns true on success.
    @discardableResult
    func send(commandID: Int, using client: IFConnectClient) async -> Bool {
        do {
            try await client.runCommand(id: commandID)
            return true
        } catch {
            return false
        }
    }
}
