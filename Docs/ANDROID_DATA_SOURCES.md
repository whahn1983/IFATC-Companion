# Data sources — Android

IFATC Companion has **no backend of its own**. Every device talks directly to a small
set of free, public, **keyless** services, plus the local link to Infinite Flight on the
pilot's own Wi-Fi. No accounts, no API keys, no paid subscriptions, no telemetry.

Every identifier and endpoint below lives in one file:
`core/config/AppConfig.kt`. Attribution wording lives in `core/ui/LegalStrings.kt` and
is asserted by `LegalStringsTest`.

## Well-behaved client conventions

All HTTP goes through `core/net/AppHttp.kt` and `OkHttpFetcher`, which apply the same
rules the iOS build applies:

- A descriptive **User-Agent** identifying the app, its version and a contact URL —
  `IFATCCompanion/<version> (+https://github.com/whahn1983/IFATC-Companion)`. NWS asks
  clients to identify themselves; a public repository is a stable, non-personal contact
  point.
- A bounded, revalidating **on-disk cache**, so `ETag`/`If-None-Match` and
  `Last-Modified`/`If-Modified-Since` are honoured and unchanged data is not re-fetched.
- `Retry-After` is parsed (seconds **or** HTTP-date) and honoured.
- Exponential backoff on 429/502/503/504: `base · 2^(failures−1)`, capped.
- No parallel repeated requests for the same resource.

---

## 1. Infinite Flight Connect — local network only

| | |
| --- | --- |
| **Purpose** | Read live aircraft state and the filed flight plan from the pilot's own copy of Infinite Flight. |
| **Transport** | UDP broadcast on port **15000** for discovery; raw **TCP on port 10112** for the Connect API v2 session. |
| **Scope** | The pilot's own local network. Never leaves it. Never touches the internet. |
| **Licence** | Not a data licence — it is the pilot's own simulator, on their own network, exposing its own API which they enable in Infinite Flight's settings. |
| **Attribution** | Infinite Flight is a separate product, sold separately, and is required for Live Connected Mode. IFATC Companion is **not affiliated with, endorsed by, sponsored by, or approved by Infinite Flight LLC.** |
| **Cache** | None. Live telemetry is polled at 1 Hz and never persisted beyond the current session. |
| **Android implementation** | `core/connect/` — `IFDiscoveryService`, `IFConnectClient`, `TcpConnectTransport`, `IFManifestReader`, `IFConnectStateReader`, `IFFlightPlanParser`, `IFConnectManager` |

---

## 2. NOAA aviation weather — METAR, TAF, PIREP, SIGMET

| | |
| --- | --- |
| **Purpose** | The weather briefing: departure/destination/alternate METARs, destination TAF, pilot reports and significant-meteorological advisories along the route. |
| **Endpoint** | `https://aviationweather.gov/api/data` |
| **Key required** | **No.** Free, public, keyless. |
| **Licence / commercial-use basis** | A work of the U.S. federal government. Under 17 U.S.C. § 105 such works are **not subject to domestic copyright**, and NOAA/NWS publish them for open reuse, including commercially. |
| **Attribution** | Sources named in-app under Settings → Data Sources and on the Weather screen. |
| **Cache** | 300 s TTL in the service, plus HTTP revalidation. Backoff base 15 s, cap 600 s. |
| **Android implementation** | `core/weather/AviationWeatherService.kt` and the four parsers |

---

## 3. FAA Digital ATIS — via the vATIS project mirror

| | |
| --- | --- |
| **Purpose** | The real published D-ATIS broadcast at US airports that issue one, read out in proper phraseology, with its information letter carried into the taxi request and the arrival check-in. |
| **Endpoint** | `https://datis.clowd.io/api` |
| **Key required** | **No.** Free, public, keyless. |
| **Licence / commercial-use basis** | The underlying D-ATIS text is FAA data sourced from the FAA SWIM system — again a U.S. federal government work, not subject to domestic copyright. `datis.clowd.io` is a free public mirror operated by the vATIS project. |
| **Availability** | US airports that publish D-ATIS only. Where a field has no ATIS the feature simply does not appear and nothing is added to the pilot's calls. |
| **Cache** | Per-airport, refreshed on the iOS cadence. |
| **Android implementation** | `core/atis/` |
| **Note** | The app is a courteous guest on a volunteer-run mirror: one request per airport per refresh, cached, with backoff. If the owner ever wants to remove that dependency, the FAA SWIM feed is the upstream. |

---

## 4. NOAA / NWS radar — base reflectivity

| | |
| --- | --- |
| **Purpose** | The **only true radar** precipitation the app shows, and only where NOAA provides coverage (CONUS). |
| **Endpoint** | `https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer` (`exportImage`) |
| **Key required** | **No.** |
| **Licence / commercial-use basis** | U.S. federal government work; see NOAA above. |
| **Attribution** | Labelled on the map and in Settings as NOAA/NWS radar. |
| **Cache** | Bounded on-disk image cache with HTTP revalidation. |
| **Android implementation** | `core/weather/radar/` |

---

## 5. NASA GIBS — global satellite precipitation estimate

| | |
| --- | --- |
| **Purpose** | Precipitation **outside** NOAA radar coverage — including Europe. It is a satellite **estimate**, of lower confidence, and the app never calls it radar. Unavailable above roughly ±60° latitude. |
| **Endpoint** | `https://gibs.earthdata.nasa.gov/wms/epsg3857/best/wms.cgi` |
| **Key required** | **No.** |
| **Licence / commercial-use basis** | NASA imagery is generally not copyrighted and may be used for any purpose, including commercially, subject to NASA's media-usage guidelines (do not imply NASA endorsement). |
| **Attribution** | Labelled on the map and in Settings as a NASA satellite estimate, explicitly distinguished from radar. |
| **Android implementation** | `core/weather/radar/` |

---

## 6. EUMETNET OPERA — European radar composite (**DISABLED**)

| | |
| --- | --- |
| **Status** | **Disabled**, exactly as on iOS. |
| **Why** | Rendering the raw CIRRUS composite reliably on-device did not work out, and no cleanly-licensed, keyless, already-rendered European radar source is available. Europe therefore falls back to the NASA satellite estimate, which the UI labels as an estimate rather than radar. |
| **Endpoint (if re-enabled)** | `https://s3.waw3-1.cloudferro.com` |
| **Android implementation** | `core/weather/radar/` — ported and kept behind the same disabled flag, so re-enabling it is a one-line change if a usable source appears. |

---

## 7. OpenStreetMap via Overpass — airport surface geometry

| | |
| --- | --- |
| **Purpose** | Runways, taxiways, taxilanes, holding positions, aprons, gates and parking stands — the data behind the taxi map, the taxi route, the runway-crossing workflow and automatic gate assignment. |
| **Endpoints** | `https://overpass-api.de/api/interpreter`, then `https://overpass.kumi.systems/api/interpreter` (failover, in this order) |
| **Key required** | **No.** |
| **Licence** | **Open Database License (ODbL) 1.0** — © OpenStreetMap contributors. **Not** CC BY 4.0. |
| **Commercial-use basis** | The ODbL permits commercial use subject to attribution and share-alike on *derived databases*. The app **produces** a derived database (the normalised surface model and the routing graph) but does not distribute it: it is computed on-device and cached locally for the pilot's own use. Nothing derived is published, so no share-alike obligation is triggered; the attribution obligation is met in full (below). |
| **Attribution** | **"Surface data © OpenStreetMap contributors"** is displayed on **every** taxi-map view, tappable, linking to <https://www.openstreetmap.org/copyright>. Also in Settings → Data Sources, in Diagnostics, and in the legal page, with the ODbL 1.0 licence named and linked. Asserted by `LegalStringsTest`. |
| **Non-endorsement** | "OpenStreetMap® is open data licensed under the ODbL by the OpenStreetMap Foundation. IFATC Companion is not endorsed by or affiliated with OpenStreetMap or any Overpass operator." |
| **Cache** | On disk, refreshed after **75 days** (airport geometry changes slowly). Cached extracts carry their source endpoint and fetch time as metadata. |
| **Politeness** | Small airport-sized bounding boxes only. Server-side query budget 90 s, client timeout 95 s. Endpoint failover, exponential backoff, request de-duplication, and stale-serve while a refresh is in flight. Overpass reports overload as an HTML page served with HTTP 200, which is detected and treated as a failure rather than an empty airport. |
| **Android implementation** | `core/surface/` |
| **Explicitly NOT used** | OpenStreetMap's public raster tile servers (`tile.openstreetmap.org`) are **never** used — forbidden by the brief and by the OSMF Tile Usage Policy. Only vector geometry from Overpass. |

---

## 8. VATSIM VATSpy Data Project — en-route sector boundaries

| | |
| --- | --- |
| **Purpose** | ARTCC / FIR / UIR boundaries, so Center hand-offs name the sector actually working the flight. |
| **Delivery** | **Bundled with the app** (`core/src/main/resources/CenterSectors.json`). No network request, works offline. |
| **Licence** | **CC BY-SA 4.0.** |
| **Commercial-use basis** | CC BY-SA 4.0 permits commercial use with attribution and share-alike. The bundled dataset is an **adaptation** (sub-sectors and terminal areas removed, coordinates rounded) and is therefore redistributed **under the same licence**, which the app states. |
| **Attribution** | "Sector boundaries © VATSIM VATSpy Data Project", with the CC BY-SA 4.0 licence named and linked, and a link to the derivation notes. Asserted by `LegalStringsTest`. |
| **Non-endorsement** | VATSIM does not endorse and is not affiliated with IFATC Companion. |
| **Simulated frequencies** | "Sector frequencies are simulated — real ARTCC/FIR sector frequencies are not published as open data." |
| **Android implementation** | `core/enroute/` |

---

## 9. SimBrief (Navigraph) — opened, never scraped

| | |
| --- | --- |
| **Purpose** | The pilot builds a flight plan on SimBrief's own site, loads it into Infinite Flight, and returns to the app to refresh. |
| **How** | Opened in a **Custom Tab** — the pilot's own browser engine and their own SimBrief session. |
| **What the app does NOT do** | It does not scrape SimBrief, does not read its data, does not inject any script into the page, does not alter what the site shows, does not remove SimBrief or Navigraph branding, and claims no affiliation. |
| **Attribution** | "SimBrief is a Navigraph service. IFATC Companion is not affiliated with SimBrief or Navigraph." |
| **Android implementation** | `app/simbrief/SimBriefLauncher.kt` |

---

## 10. Google Play Billing

Purchases and entitlement only. See `Docs/ANDROID_BILLING.md`. No pilot data is sent;
the app reads Play's own purchase records for the signed-in Google account.

---

## Secrets

**There are none, and that is a design property rather than an accident.** No endpoint
above requires an API key, a token, an account or a signed request. Nothing in
`AppConfig.kt` is confidential, so nothing in it needs protecting, obfuscating or moving
to a server.

If a provider is ever added that requires a **true secret**, it must not be embedded in
the APK and must not be obfuscated — an APK is readable, and obfuscation only changes
how long extraction takes. Such a provider requires either a server-side proxy (new
infrastructure, an explicit decision) or a different provider. This is recorded here so
the rule survives the next person who needs a keyed service in a hurry.

## Privacy

None of these requests carries an account, a name, an email address, a device
identifier or a location beyond the aviation identifiers the feature is *about* — an
ICAO code, a small map bounding box, a route. See
`Docs/GOOGLE_PLAY_DATA_SAFETY.md`.
