package com.h3consultingpartners.ifatccompanion.core.config

/**
 * Every external identifier, endpoint and link the app uses, in one place.
 *
 * Nothing here is a secret. IFATC Companion has no backend and no API keys: every
 * upstream service it talks to is free, public and keyless. If a provider ever
 * requires a true secret, it must not be embedded or obfuscated here — see
 * Docs/ANDROID_DATA_SOURCES.md for the standing rule.
 */
object AppConfig {

    /** Product identity. */
    object App {
        const val NAME = "IFATC Companion"
        const val PUBLISHER = "H3 Consulting Partners"
        const val APPLICATION_ID = "com.h3consultingpartners.ifatccompanion"
    }

    /** Google Play Billing products. See Docs/ANDROID_BILLING.md. */
    object Billing {
        /** Auto-renewing subscription, base plan `monthly`. */
        const val MONTHLY_PRODUCT_ID = "com.h3consultingpartners.ifatccompanion.live.monthly"

        /** Auto-renewing subscription, base plan `annual`. */
        const val ANNUAL_PRODUCT_ID = "com.h3consultingpartners.ifatccompanion.live.annual"

        /** One-time purchase (INAPP product) granting permanent Live access. */
        const val LIFETIME_PRODUCT_ID = "com.h3consultingpartners.ifatccompanion.live.lifetime"

        /**
         * Play requires a subscription's base plan id when launching the billing flow.
         * These match the base plans configured in Play Console.
         */
        const val MONTHLY_BASE_PLAN_ID = "monthly"
        const val ANNUAL_BASE_PLAN_ID = "annual"

        /** Deep link to the Play subscription-management screen for one product. */
        fun manageSubscriptionUrl(productId: String): String =
            "https://play.google.com/store/account/subscriptions" +
                "?sku=$productId&package=${App.APPLICATION_ID}"

        /** Deep link to the user's subscription list. */
        const val MANAGE_SUBSCRIPTIONS_URL =
            "https://play.google.com/store/account/subscriptions"
    }

    /** Legal and support links. Platform-independent unless noted. */
    object Links {
        const val PRIVACY_POLICY =
            "https://whahn1983.github.io/IFATC-Companion/privacy-policy.html"
        const val PROJECT_HOME = "https://github.com/whahn1983/IFATC-Companion"
        const val SUPPORT = "https://github.com/whahn1983/IFATC-Companion/issues"

        /** OpenStreetMap ODbL attribution target, required on every surface map. */
        const val OPENSTREETMAP_COPYRIGHT = "https://www.openstreetmap.org/copyright"
        const val ODBL_LICENSE = "https://opendatacommons.org/licenses/odbl/1-0/"

        /** VATSpy sector data provenance. */
        const val VATSPY_DATA_PROJECT =
            "https://github.com/vatsimnetwork/vatspy-data-project"
        const val CENTER_SECTORS_DOC =
            "https://github.com/whahn1983/IFATC-Companion/blob/main/docs/CenterSectors.md"
        const val OPENSTREETMAP_LICENSING_DOC =
            "https://github.com/whahn1983/IFATC-Companion/blob/main/docs/OpenStreetMapLicensing.md"
        const val CC_BY_SA_4_0 = "https://creativecommons.org/licenses/by-sa/4.0/"

        /** SimBrief — opened in an in-app browser. Not affiliated. */
        const val SIMBRIEF = "https://dispatch.simbrief.com"

        const val INFINITE_FLIGHT = "https://infiniteflight.com"

        /**
         * Google Play's own subscription terms. Android must NOT use Apple's standard
         * EULA link, which is what the iOS build points "Terms of Use" at.
         */
        const val GOOGLE_PLAY_TERMS = "https://play.google.com/about/play-terms/"
    }

    /**
     * Public data services. Every one is keyless. See Docs/ANDROID_DATA_SOURCES.md for
     * the licence and commercial-use basis of each.
     */
    object Endpoints {
        /** NOAA aviation weather (METAR/TAF/PIREP/SIGMET). */
        const val AVIATION_WEATHER_BASE = "https://aviationweather.gov/api/data"

        /** FAA Digital ATIS via the vATIS project's free public mirror. */
        const val DATIS_BASE = "https://datis.clowd.io/api"

        /**
         * NOAA/NWS radar base reflectivity (ArcGIS ImageServer, `exportImage`). The
         * only *true radar* precipitation source the app uses, and only where NOAA
         * provides coverage (CONUS).
         */
        const val NWS_RADAR_IMAGE_SERVER =
            "https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer"

        /**
         * NASA GIBS imagery (WMS, EPSG:3857). Supplies the global *satellite
         * precipitation estimate* used outside NOAA radar coverage — a lower-confidence
         * estimate, never presented as radar.
         */
        const val NASA_GIBS_WMS = "https://gibs.earthdata.nasa.gov/wms/epsg3857/best/wms.cgi"

        /**
         * EUMETNET OPERA open data (CloudFerro S3). Currently **disabled** — rendering
         * the raw CIRRUS composite reliably on-device did not work out, and no cleanly
         * licensed, keyless, already-rendered European radar source is available. Europe
         * falls back to the NASA satellite estimate. See Docs/ANDROID_DATA_SOURCES.md.
         */
        const val EUMETNET_OPERA_S3_BASE = "https://s3.waw3-1.cloudferro.com"

        /**
         * OpenStreetMap Overpass API endpoints, tried in order. These are **shared
         * community infrastructure** — the app requests only small airport areas, caches
         * results, backs off politely, and never runs parallel repeated queries for the
         * same airport.
         */
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
    }

    /** Infinite Flight Connect. */
    object Connect {
        const val DEFAULT_PORT = 10112
        /** Infinite Flight broadcasts its presence as UDP JSON on this port. */
        const val DISCOVERY_PORT = 15000
    }
}
