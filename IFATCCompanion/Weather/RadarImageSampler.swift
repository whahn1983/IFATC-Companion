import Foundation
import CoreLocation
import CoreGraphics
import ImageIO

/// Derives coarse **moderate-or-greater** precipitation cells from a rendered
/// radar image (NOAA/EUMETNET OPERA base-reflectivity PNG) so the vector
/// weather-deviation logic can route around the actual precipitation core inside a
/// SIGMET instead of the entire advisory area.
///
/// This is the live counterpart to Mock Mode's hand-authored `RadarCell`s and
/// implements the "raster → cell sampling" step described in `docs/Weather.md`.
/// It is a **best-effort sample of an already-approximate colorized radar image**,
/// used for simulation/training only — never real-world storm avoidance. The color
/// → intensity mapping keys off the standard reflectivity ramp (green = light,
/// yellow = moderate, orange = heavy, red/magenta = extreme); only the
/// moderate-and-warmer bands matter here, so it is deliberately tolerant of shade
/// variation between providers.
///
/// The heavy lifting (color classification, connected-component clustering, and
/// the SIGMET-overlap geometry) is pure and unit-tested; only the PNG decode
/// touches CoreGraphics.
enum RadarImageSampler {

    // MARK: - Pixel → coordinate projection

    /// How a sampled image's pixel rows map to latitude. The live radar/satellite
    /// overlays are rendered in **Web Mercator (EPSG:3857)** — the projection MapKit
    /// draws in — so a pixel row is linear in Mercator *y*, not in latitude; reading it
    /// as linear latitude drifts the sampled cells (tens of NM at route scale, enough to
    /// push an on-route core outside the deviation corridor). Synthetic test grids and any
    /// equirectangular source stay `.equirectangular` (the default), where a row *is*
    /// linear in latitude. Longitude is linear in both (Mercator *x* ∝ longitude).
    enum PixelProjection {
        case equirectangular
        case webMercator
    }

    /// Web Mercator *y* (unitless; the Earth-radius factor cancels in the inverse) for a
    /// latitude in degrees, and its inverse. Used to map pixel rows of a 3857 image back
    /// to latitude non-linearly.
    private static func mercatorY(_ latDegrees: Double) -> Double {
        let clamped = min(85.05112878, max(-85.05112878, latDegrees))
        return log(tan(.pi / 4 + clamped * .pi / 180 / 2))
    }
    private static func inverseMercatorLatDegrees(_ y: Double) -> Double {
        (2 * atan(exp(y)) - .pi / 2) * 180 / .pi
    }

    // MARK: - Color → intensity

    /// The color ramp a rendered precipitation image uses, which determines how a
    /// pixel's hue maps to an intensity band.
    enum Palette {
        /// Standard radar base-reflectivity ramp (NOAA/NWS, EUMETNET OPERA):
        /// green/blue = light, yellow = moderate, orange = heavy, red/magenta = extreme.
        case reflectivity
        /// NASA GPM IMERG / GIBS precipitation-*rate* ramp (mm/hr) — the global
        /// satellite estimate. Structurally similar to the reflectivity ramp, but two
        /// things differ and are handled here: its low end is a broad blue→green wash
        /// (kept as `.light` so a stratiform rain field doesn't blob the whole map into
        /// one giant deviation), and satellite averaging (~10 km) *understates*
        /// convective cores, so the yellow-green (chartreuse) band that reflectivity
        /// treats as light is promoted to `.moderate` to catch cells the estimate paints
        /// paler than radar would. **Best-effort: the exact rate breakpoints are intended
        /// to be verified/tuned on device against live GIBS IMERG tiles**, consistent
        /// with the other overlay color scalings in this app.
        case imergRate
    }

    /// Classify one precipitation pixel into an intensity for the given color ramp, or
    /// `nil` when the pixel is transparent / gray / below the moderate threshold we care
    /// about. For both ramps green and blue map to `.light` (ignored by the moderate-plus
    /// filter); the ramps differ only in where the moderate band begins (see `Palette`).
    static func intensity(r: UInt8, g: UInt8, b: UInt8, a: UInt8,
                          palette: Palette = .reflectivity) -> WeatherIntensity? {
        // Transparent background of the overlay → no precipitation here.
        guard a >= 40 else { return nil }
        let rf = Double(r), gf = Double(g), bf = Double(b)
        let maxc = max(rf, max(gf, bf))
        let minc = min(rf, min(gf, bf))
        let value = maxc / 255.0
        let sat = maxc <= 0 ? 0 : (maxc - minc) / maxc
        // Near-black, near-white, or washed-out gray pixels are map furniture /
        // borders bleeding through, not a colored precipitation return.
        guard value >= 0.25, sat >= 0.30 else { return nil }

        let hue = hueDegrees(r: rf, g: gf, b: bf, maxc: maxc, minc: minc)
        switch palette {
        case .reflectivity:
            switch hue {
            case 78...175:            return .light    // green / green-cyan
            case 175..<290:           return .light    // blue / cyan (lightest returns)
            case 46..<78:             return .moderate // yellow
            case 20..<46:             return .heavy    // orange
            case 290..<330:           return .extreme  // magenta / violet (very heavy)
            default:                  return .extreme  // red (hue < 20 or >= 330)
            }
        case .imergRate:
            switch hue {
            case 100...175:           return .light    // green (low-moderate estimated rate)
            case 175..<290:           return .light    // blue / cyan (lightest estimate)
            case 70..<100:            return .moderate // yellow-green — promoted; satellite understates cores
            case 46..<70:             return .moderate // yellow
            case 20..<46:             return .heavy    // orange
            case 290..<330:           return .extreme  // magenta / violet (very heavy)
            default:                  return .extreme  // red (hue < 20 or >= 330)
            }
        }
    }

    /// Hue in degrees (0–360) for an RGB triple, given its precomputed max/min
    /// channels. Returns 0 for achromatic input (callers gate on saturation first).
    static func hueDegrees(r: Double, g: Double, b: Double, maxc: Double, minc: Double) -> Double {
        let delta = maxc - minc
        guard delta > 0 else { return 0 }
        let hue: Double
        if maxc == r {
            hue = 60 * (((g - b) / delta).truncatingRemainder(dividingBy: 6))
        } else if maxc == g {
            hue = 60 * (((b - r) / delta) + 2)
        } else {
            hue = 60 * (((r - g) / delta) + 4)
        }
        return hue < 0 ? hue + 360 : hue
    }

    // MARK: - Sample resolution

    /// The `columns × rows` sample grid for a radar image covering a bbox of the given
    /// span (NM), sized to hold roughly `targetNMPerPixel` NM per pixel on each axis so a
    /// **whole-flight-plan** sample still resolves individual storms near the aircraft
    /// (finer for short routes, capped for very long ones). Bounded to `[minDim, maxDim]`
    /// per axis — the floor keeps a short route from over-sampling a tiny image, the cap
    /// keeps a transcon route from requesting a giant one. Pure and unit-tested.
    static func sampleGrid(latSpanNM: Double, lonSpanNM: Double,
                           targetNMPerPixel: Double = 2, minDim: Int = 160, maxDim: Int = 640)
        -> (columns: Int, rows: Int) {
        func dim(_ nm: Double) -> Int {
            let n = nm.isFinite ? Int((nm / max(0.1, targetNMPerPixel)).rounded()) : minDim
            return Swift.min(maxDim, Swift.max(minDim, n))
        }
        return (columns: dim(lonSpanNM), rows: dim(latSpanNM))
    }

    /// The `columns × rows` sample size for a radar export of `bbox`, sized so its aspect
    /// ratio matches the bbox's exact **Web-Mercator** width:height.
    ///
    /// The live NOAA/NASA overlays render in EPSG:3857 (the projection MapKit draws, and
    /// the one the pixel→lat inversion in `boundingPolygon` assumes). If the requested
    /// image's aspect ratio differs from the bbox's Mercator aspect, the source does not
    /// give back the extent we asked for: an ArcGIS ImageServer **expands the returned
    /// extent** to fit the image, and a WMS **stretches** the render — either way the image
    /// covers a different area than the `bbox` the sampler uses to place pixels, so every
    /// sampled cell drifts (tens of NM, pulled toward the corridor's centre) from the
    /// displayed radar. Sizing from `sampleGrid` alone did this: it clamps each axis to
    /// `[minDim, maxDim]` independently and sizes from lat/lon NM (whose single-`cos(lat)`
    /// aspect also diverges over a tall corridor), so the two axes rarely held the Mercator
    /// aspect.
    ///
    /// This keeps `sampleGrid`'s ~`targetNMPerPixel` resolution/cap on the **longer**
    /// Mercator axis, then derives the shorter axis from the exact Mercator aspect so the
    /// image is registered to `bbox` and the inverse-Mercator cell coordinates land on the
    /// real returns. Pure and unit-tested.
    static func mercatorSampleSize(bbox: RadarBoundingBox,
                                   targetNMPerPixel: Double = 2, minDim: Int = 160, maxDim: Int = 640)
        -> (columns: Int, rows: Int) {
        let midLat = (bbox.minLatitude + bbox.maxLatitude) / 2
        let latSpanNM = (bbox.maxLatitude - bbox.minLatitude) * 60
        let lonSpanNM = (bbox.maxLongitude - bbox.minLongitude) * 60 * max(0.2, cos(midLat * .pi / 180))
        let base = sampleGrid(latSpanNM: latSpanNM, lonSpanNM: lonSpanNM,
                              targetNMPerPixel: targetNMPerPixel, minDim: minDim, maxDim: maxDim)

        // Exact Web-Mercator span (radius omitted — it cancels in the aspect). x ∝ longitude
        // in radians so it matches the units of `mercatorY`; y is the Mercator projection of
        // latitude, so a row is linear in it (see `PixelProjection.webMercator`).
        let mercWidth = (bbox.maxLongitude - bbox.minLongitude) * .pi / 180
        let mercHeight = mercatorY(bbox.maxLatitude) - mercatorY(bbox.minLatitude)
        guard mercWidth > 0, mercHeight > 0 else { return base }

        // Keep the resolution budget on the longer axis; derive the shorter from the exact
        // Mercator aspect so `columns / rows == mercWidth / mercHeight`.
        if mercWidth >= mercHeight {
            let columns = base.columns
            let rows = Swift.max(8, Int((Double(columns) * mercHeight / mercWidth).rounded()))
            return (columns: columns, rows: rows)
        } else {
            let rows = base.rows
            let columns = Swift.max(8, Int((Double(rows) * mercWidth / mercHeight).rounded()))
            return (columns: columns, rows: rows)
        }
    }

    // MARK: - Grid → cells

    /// Cluster a grid of per-pixel intensities into moderate-or-greater
    /// precipitation cells, each an axis-aligned lat/lon box covering its cluster.
    /// `grid[row][col]` is row-major with row 0 at the **top** (max latitude) of
    /// `bbox`. Clusters smaller than `minCells` pixels are dropped as noise.
    static func cells(from grid: [[WeatherIntensity?]],
                      bbox: RadarBoundingBox,
                      minCells: Int = 3,
                      projection: PixelProjection = .equirectangular) -> [RadarCell] {
        let rows = grid.count
        guard rows > 0 else { return [] }
        let cols = grid[0].count
        guard cols > 0 else { return [] }

        func significant(_ row: Int, _ col: Int) -> Bool {
            guard row >= 0, row < rows, col >= 0, col < cols else { return false }
            guard let intensity = grid[row][col] else { return false }
            return intensity >= .moderate
        }

        var visited = Array(repeating: Array(repeating: false, count: cols), count: rows)
        let neighbors = [(-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (-1, 1), (1, -1), (1, 1)]
        var cells: [RadarCell] = []

        for startRow in 0..<rows {
            for startCol in 0..<cols where significant(startRow, startCol) && !visited[startRow][startCol] {
                var stack = [(startRow, startCol)]
                visited[startRow][startCol] = true
                var minRow = startRow, maxRow = startRow, minCol = startCol, maxCol = startCol
                var peak: WeatherIntensity = grid[startRow][startCol] ?? .moderate
                var count = 0

                while let (row, col) = stack.popLast() {
                    count += 1
                    minRow = min(minRow, row); maxRow = max(maxRow, row)
                    minCol = min(minCol, col); maxCol = max(maxCol, col)
                    if let intensity = grid[row][col], intensity > peak { peak = intensity }
                    for (dr, dc) in neighbors {
                        let nr = row + dr, nc = col + dc
                        if significant(nr, nc) && !visited[nr][nc] {
                            visited[nr][nc] = true
                            stack.append((nr, nc))
                        }
                    }
                }

                guard count >= minCells else { continue }
                let polygon = boundingPolygon(minRow: minRow, maxRow: maxRow,
                                              minCol: minCol, maxCol: maxCol,
                                              rows: rows, cols: cols, bbox: bbox,
                                              projection: projection)
                cells.append(RadarCell(polygon: polygon, intensity: peak))
            }
        }
        return cells
    }

    /// The lat/lon corners of the grid-cell block `minRow…maxRow` × `minCol…maxCol`.
    /// Row 0 is the top (max latitude); the block spans whole cells, so its edges
    /// run from `minRow` to `maxRow + 1` and `minCol` to `maxCol + 1`.
    private static func boundingPolygon(minRow: Int, maxRow: Int, minCol: Int, maxCol: Int,
                                        rows: Int, cols: Int, bbox: RadarBoundingBox,
                                        projection: PixelProjection = .equirectangular) -> [CLLocationCoordinate2D] {
        let latSpan = bbox.maxLatitude - bbox.minLatitude
        let lonSpan = bbox.maxLongitude - bbox.minLongitude
        // Longitude is linear in both projections (Mercator x ∝ longitude).
        func lon(atColEdge edge: Int) -> Double {
            bbox.minLongitude + (Double(edge) / Double(cols)) * lonSpan
        }
        // Latitude: linear for an equirectangular grid; for a Web Mercator image the row
        // is linear in Mercator y, so interpolate in y and invert the projection.
        func lat(atRowEdge edge: Int) -> Double {
            switch projection {
            case .equirectangular:
                return bbox.maxLatitude - (Double(edge) / Double(rows)) * latSpan
            case .webMercator:
                let f = Double(edge) / Double(rows)   // 0 at top (maxLat) → 1 at bottom (minLat)
                let yTop = mercatorY(bbox.maxLatitude)
                let yBottom = mercatorY(bbox.minLatitude)
                return inverseMercatorLatDegrees(yTop - f * (yTop - yBottom))
            }
        }
        let north = lat(atRowEdge: minRow)
        let south = lat(atRowEdge: maxRow + 1)
        let west = lon(atColEdge: minCol)
        let east = lon(atColEdge: maxCol + 1)
        return [CLLocationCoordinate2D(latitude: south, longitude: west),
                CLLocationCoordinate2D(latitude: south, longitude: east),
                CLLocationCoordinate2D(latitude: north, longitude: east),
                CLLocationCoordinate2D(latitude: north, longitude: west)]
    }

    // MARK: - PNG decode

    /// Decode a radar PNG into a `rows × cols` intensity grid by drawing it into a
    /// small RGBA buffer and classifying each sampled pixel with the given color ramp.
    /// Returns `nil` when the bytes can't be decoded. The only impure step in this type.
    static func grid(fromPNG data: Data, columns: Int, rows: Int,
                     palette: Palette = .reflectivity) -> [[WeatherIntensity?]]? {
        guard columns > 0, rows > 0,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }

        let bytesPerPixel = 4
        let bytesPerRow = bytesPerPixel * columns
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue
        // Let CoreGraphics own the backing store (data: nil): the pointer from
        // `context.data` stays valid for the context's lifetime, so it is safe to
        // read after drawing.
        guard let context = CGContext(data: nil, width: columns, height: rows,
                                      bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                                      space: colorSpace, bitmapInfo: bitmapInfo),
              let raw = context.data else { return nil }

        context.interpolationQuality = .low
        context.draw(image, in: CGRect(x: 0, y: 0, width: columns, height: rows))

        let buffer = raw.bindMemory(to: UInt8.self, capacity: bytesPerRow * rows)
        // CoreGraphics origin is bottom-left; our grid is top-left (row 0 = north),
        // so flip the row index when reading the buffer back.
        var out = [[WeatherIntensity?]](repeating: [WeatherIntensity?](repeating: nil, count: columns), count: rows)
        for row in 0..<rows {
            let bufferRow = rows - 1 - row
            for col in 0..<columns {
                let i = bufferRow * bytesPerRow + col * bytesPerPixel
                out[row][col] = intensity(r: buffer[i], g: buffer[i + 1], b: buffer[i + 2], a: buffer[i + 3],
                                          palette: palette)
            }
        }
        return out
    }

    /// Decode `data` and cluster it into moderate-or-greater precipitation cells for
    /// the region `bbox`, at the given sample resolution and color ramp. Returns `nil`
    /// on decode failure so the caller can fall back to the full SIGMET area.
    static func cells(fromPNG data: Data, columns: Int, rows: Int, bbox: RadarBoundingBox,
                      palette: Palette = .reflectivity,
                      projection: PixelProjection = .equirectangular) -> [RadarCell]? {
        guard let grid = grid(fromPNG: data, columns: columns, rows: rows, palette: palette) else { return nil }
        return cells(from: grid, bbox: bbox, projection: projection)
    }

    // MARK: - SIGMET precipitation cores

    /// The moderate-or-greater precipitation cells that lie within (or overlap) a
    /// SIGMET's advisory `area`, as the geometry to route around instead of the
    /// whole polygon. Returns each overlapping cell's polygon; an empty result means
    /// no significant precipitation was found in the area, and the caller should
    /// fall back to the full advisory.
    static func precipitationCores(in area: [CLLocationCoordinate2D],
                                   cells: [RadarCell]) -> [[CLLocationCoordinate2D]] {
        let advisory = area.filter { $0.isValid }
        guard advisory.count >= 3 else { return [] }
        return cells.compactMap { cell in
            guard cell.intensity >= .moderate else { return nil }
            let polygon = cell.polygon.filter { $0.isValid }
            guard polygon.count >= 3 else { return nil }
            return polygonsOverlap(advisory, polygon) ? polygon : nil
        }
    }

    /// Whether two lat/lon polygons overlap: a vertex of one inside the other, or a
    /// pair of edges crossing. Planar test, consistent with the rest of the
    /// route-conflict geometry (adequate at SIGMET / precipitation-cell scale).
    static func polygonsOverlap(_ a: [CLLocationCoordinate2D], _ b: [CLLocationCoordinate2D]) -> Bool {
        if a.contains(where: { WeatherRouteAnalyzer.pointInPolygon($0, b) }) { return true }
        if b.contains(where: { WeatherRouteAnalyzer.pointInPolygon($0, a) }) { return true }
        var j = a.count - 1
        for i in a.indices {
            var l = b.count - 1
            for k in b.indices {
                if Geo.segmentsIntersect(a[j], a[i], b[l], b[k]) { return true }
                l = k
            }
            j = i
        }
        return false
    }
}
