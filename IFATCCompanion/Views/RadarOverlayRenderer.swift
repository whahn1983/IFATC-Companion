import SwiftUI
import MapKit

/// Renders the NOAA radar precipitation image for the map's visible region as a
/// semi-transparent overlay. Used only in Live mode where NOAA covers the region;
/// Mock Mode draws deterministic precipitation polygons on the map instead. The
/// image is fetched from the approved NOAA/NWS source via `urlProvider`.
///
/// Note: the overlay is aligned to the visible region and is intentionally
/// approximate — it is a simulation aid, never real-world flight guidance.
struct RadarOverlayRenderer: View {
    let opacity: Double
    /// Builds the export-image URL for a requested pixel size, or nil when the
    /// overlay should not render (off / uncovered / mock).
    let urlProvider: (CGSize) -> URL?

    @Environment(\.displayScale) private var displayScale

    var body: some View {
        GeometryReader { geo in
            let pixels = CGSize(width: geo.size.width * displayScale,
                                height: geo.size.height * displayScale)
            if let url = urlProvider(pixels) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .interpolation(.medium)
                            .scaledToFill()
                    default:
                        Color.clear
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .clipped()
                .opacity(opacity)
                .allowsHitTesting(false)
            } else {
                Color.clear
            }
        }
    }
}
