//
//  IFATCCompanionWidgetsBundle.swift
//  IFATCCompanionWidgets
//
//  Created by William Hahn on 7/25/26.
//

import WidgetKit
import SwiftUI

@main
struct IFATCCompanionWidgetsBundle: WidgetBundle {
    var body: some Widget {
        IFATCCompanionWidgets()
        IFATCCompanionWidgetsControl()
        IFATCCompanionWidgetsLiveActivity()
    }
}
