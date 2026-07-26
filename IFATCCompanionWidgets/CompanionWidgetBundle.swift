import WidgetKit
import SwiftUI

// This is the entry point for the IFATC Companion widget extension. It is added to the
// *widget-extension* target only (not the app target). See docs/LiveActivitySetup.md for
// how to create that target and which files to include.

@main
struct CompanionWidgetBundle: WidgetBundle {
    var body: some Widget {
        CompanionLiveActivityWidget()
    }
}
