import SwiftUI

#if canImport(UIKit)
import UIKit

/// Installs an app-wide tap gesture that dismisses the keyboard when the user
/// taps anywhere outside the active text field. The recognizer is configured so
/// it never swallows touches — buttons, lists, and other controls keep working
/// exactly as before; the tap simply also resigns the current first responder.
extension UIApplication {
    /// Adds a single keyboard-dismissing tap recognizer to the app's main window.
    /// Safe to call more than once: it removes any previously installed recognizer
    /// first so we never stack duplicates.
    func installKeyboardDismissTapGesture() {
        guard let window = connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow }) else { return }

        window.gestureRecognizers?
            .filter { $0 is KeyboardDismissTapGestureRecognizer }
            .forEach { window.removeGestureRecognizer($0) }

        let tap = KeyboardDismissTapGestureRecognizer(
            target: window,
            action: #selector(UIView.endEditing)
        )
        tap.requiresExclusiveTouchType = false
        tap.cancelsTouchesInView = false
        tap.delegate = KeyboardDismissGestureDelegate.shared
        window.addGestureRecognizer(tap)
    }
}

/// Marker subclass so we can identify (and avoid duplicating) our own recognizer.
private final class KeyboardDismissTapGestureRecognizer: UITapGestureRecognizer {}

/// Allows our tap to be recognized alongside every other gesture so it never
/// blocks scrolling, button taps, or any existing interaction.
private final class KeyboardDismissGestureDelegate: NSObject, UIGestureRecognizerDelegate {
    static let shared = KeyboardDismissGestureDelegate()

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        true
    }
}
#endif
