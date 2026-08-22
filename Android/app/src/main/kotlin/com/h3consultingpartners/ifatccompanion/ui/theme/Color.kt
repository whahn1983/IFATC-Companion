package com.h3consultingpartners.ifatccompanion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette is taken from the shipping iOS app so Android carries the same visual
 * identity: the accent is the asset catalog's AccentColor (sRGB 0.298, 0.871, 0.694 =
 * #4CDEB1), and the blues are sampled from the app icon's own radar gradient.
 *
 * Material You dynamic colour is deliberately NOT used. IFATC Companion has a fixed
 * brand accent on iOS, and letting the wallpaper repaint the app would break the
 * visual-identity parity this port is for. The choice is recorded in
 * Docs/ANDROID_PARITY_MATRIX.md.
 */
object IFATCPalette {
    val mint = Color(0xFF4CDEB1)

    // Light scheme.
    val primaryLight = Color(0xFF00695A)
    val onPrimaryLight = Color(0xFFFFFFFF)
    val primaryContainerLight = Color(0xFF7FF8D6)
    val onPrimaryContainerLight = Color(0xFF00201A)
    val secondaryLight = Color(0xFF1E4C86)
    val onSecondaryLight = Color(0xFFFFFFFF)
    val secondaryContainerLight = Color(0xFFD6E3FF)
    val onSecondaryContainerLight = Color(0xFF001B3D)
    val tertiaryLight = Color(0xFF1B635F)
    val onTertiaryLight = Color(0xFFFFFFFF)
    val tertiaryContainerLight = Color(0xFFA6F1EA)
    val onTertiaryContainerLight = Color(0xFF00201E)
    val backgroundLight = Color(0xFFF2F5F6)
    val onBackgroundLight = Color(0xFF11181C)
    val surfaceLight = Color(0xFFF2F5F6)
    val onSurfaceLight = Color(0xFF11181C)
    val surfaceVariantLight = Color(0xFFDBE5E0)
    val onSurfaceVariantLight = Color(0xFF3F4945)
    val surfaceContainerLight = Color(0xFFE8EDEE)
    val outlineLight = Color(0xFF6F7975)
    val outlineVariantLight = Color(0xFFBFC9C4)
    val errorLight = Color(0xFFBA1A1A)
    val onErrorLight = Color(0xFFFFFFFF)
    val errorContainerLight = Color(0xFFFFDAD6)
    val onErrorContainerLight = Color(0xFF410002)

    // Dark scheme.
    val primaryDark = Color(0xFF5EDCBC)
    val onPrimaryDark = Color(0xFF00382F)
    val primaryContainerDark = Color(0xFF005144)
    val onPrimaryContainerDark = Color(0xFF7FF8D6)
    val secondaryDark = Color(0xFFAAC7FF)
    val onSecondaryDark = Color(0xFF002F65)
    val secondaryContainerDark = Color(0xFF00458E)
    val onSecondaryContainerDark = Color(0xFFD6E3FF)
    val tertiaryDark = Color(0xFF8AD5CE)
    val onTertiaryDark = Color(0xFF003734)
    val tertiaryContainerDark = Color(0xFF004F4B)
    val onTertiaryContainerDark = Color(0xFFA6F1EA)
    val backgroundDark = Color(0xFF161A21)
    val onBackgroundDark = Color(0xFFDEE4E1)
    val surfaceDark = Color(0xFF161A21)
    val onSurfaceDark = Color(0xFFDEE4E1)
    val surfaceVariantDark = Color(0xFF3F4945)
    val onSurfaceVariantDark = Color(0xFFBFC9C4)
    val surfaceContainerDark = Color(0xFF1E242C)
    val outlineDark = Color(0xFF899390)
    val outlineVariantDark = Color(0xFF3F4945)
    val errorDark = Color(0xFFFFB4AB)
    val onErrorDark = Color(0xFF690005)
    val errorContainerDark = Color(0xFF93000A)
    val onErrorContainerDark = Color(0xFFFFDAD6)
}

/**
 * Colours the Material scheme has no slot for but the app needs consistently: the
 * per-facility controller chips, weather severity, ride quality, and the map layers.
 * Mirrors the semantic colours the SwiftUI views use inline.
 */
data class IFATCSemanticColors(
    val pilot: Color,
    val controller: Color,
    val system: Color,
    val facilityClearance: Color,
    val facilityRamp: Color,
    val facilityGround: Color,
    val facilityTower: Color,
    val facilityDeparture: Color,
    val facilityCenter: Color,
    val facilityApproach: Color,
    val connected: Color,
    val connecting: Color,
    val disconnected: Color,
    val severityLight: Color,
    val severityModerate: Color,
    val severitySevere: Color,
    val severityExtreme: Color,
    val routeLine: Color,
    val deviationLine: Color,
    val aircraft: Color,
    val runway: Color,
    val taxiway: Color,
    val taxiRoute: Color,
    val holdShort: Color,
    val runwayCrossing: Color,
    val gate: Color,
    val apron: Color,
    /**
     * The base map under the route: coastlines, the lat/lon grid, and the labels and
     * attribution drawn over it.
     *
     * These have to come from the theme rather than be constants. The map card sits on the
     * surface colour, which is near-white in light theme, so grid lines and labels defined
     * as translucent white simply disappear there — including the NASA credit, which is
     * shown precisely because it is asked for.
     */
    val mapCoastline: Color,
    val mapGraticule: Color,
    val mapGraticuleLabel: Color,
) {
    companion object {
        val light = IFATCSemanticColors(
            pilot = Color(0xFF1E4C86),
            controller = Color(0xFF00695A),
            system = Color(0xFF6F7975),
            facilityClearance = Color(0xFF7A5AC7),
            facilityRamp = Color(0xFFB2670B),
            facilityGround = Color(0xFF2B7A4B),
            facilityTower = Color(0xFF1E4C86),
            facilityDeparture = Color(0xFF00695A),
            facilityCenter = Color(0xFF3E5C99),
            facilityApproach = Color(0xFF9A4B7E),
            connected = Color(0xFF1E7A46),
            connecting = Color(0xFFB2670B),
            disconnected = Color(0xFFBA1A1A),
            severityLight = Color(0xFF3E9A5C),
            severityModerate = Color(0xFFC79000),
            severitySevere = Color(0xFFD2610C),
            severityExtreme = Color(0xFFBA1A1A),
            routeLine = Color(0xFF1E4C86),
            deviationLine = Color(0xFF00A883),
            aircraft = Color(0xFF11181C),
            runway = Color(0xFF4A5257),
            taxiway = Color(0xFF9AA5A0),
            taxiRoute = Color(0xFF00A883),
            holdShort = Color(0xFFC79000),
            runwayCrossing = Color(0xFFD2610C),
            gate = Color(0xFF7A5AC7),
            apron = Color(0xFFCBD4CF),
            mapCoastline = Color(0xFF7C8B96),
            mapGraticule = Color(0x2E11181C),
            mapGraticuleLabel = Color(0x8A11181C),
        )

        val dark = IFATCSemanticColors(
            pilot = Color(0xFFAAC7FF),
            controller = Color(0xFF5EDCBC),
            system = Color(0xFF899390),
            facilityClearance = Color(0xFFCBB6FF),
            facilityRamp = Color(0xFFFFB870),
            facilityGround = Color(0xFF7CD9A0),
            facilityTower = Color(0xFFAAC7FF),
            facilityDeparture = Color(0xFF5EDCBC),
            facilityCenter = Color(0xFF9FB4E8),
            facilityApproach = Color(0xFFEFA9CF),
            connected = Color(0xFF7CD9A0),
            connecting = Color(0xFFFFB870),
            disconnected = Color(0xFFFFB4AB),
            severityLight = Color(0xFF7CD9A0),
            severityModerate = Color(0xFFF2CD5B),
            severitySevere = Color(0xFFFFB870),
            severityExtreme = Color(0xFFFFB4AB),
            routeLine = Color(0xFFAAC7FF),
            deviationLine = Color(0xFF4CDEB1),
            aircraft = Color(0xFFFFFFFF),
            runway = Color(0xFF8B948F),
            taxiway = Color(0xFF5A625E),
            taxiRoute = Color(0xFF4CDEB1),
            holdShort = Color(0xFFF2CD5B),
            runwayCrossing = Color(0xFFFFB870),
            gate = Color(0xFFCBB6FF),
            apron = Color(0xFF2A3138),
            mapCoastline = Color(0xFF5A6B78),
            mapGraticule = Color(0x33FFFFFF),
            mapGraticuleLabel = Color(0x99FFFFFF),
        )
    }
}
