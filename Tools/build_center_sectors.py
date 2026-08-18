#!/usr/bin/env python3
"""Build the bundled enroute Center-sector dataset (CenterSectors.json).

Source: the VATSIM VATSpy Data Project, the only openly licensed dataset that
covers FIR / UIR / ARTCC boundaries *globally* with the radio names controllers
actually use. OpenStreetMap deliberately does not map airspace, so it cannot
supply this geometry (see docs/CenterSectors.md).

    Boundaries.geojson  MultiPolygon geometry, keyed by boundary id
    VATSpy.dat          [FIRs] section: id | name | callsign prefix | boundary

Licence: CC BY-SA 4.0. The generated file is an adapted database and carries the
attribution + licence in its header so it can be redistributed under the same
terms.

Usage:
    python3 Tools/build_center_sectors.py                # download + write
    python3 Tools/build_center_sectors.py --cache-dir /tmp/vatspy
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import urllib.request
from datetime import date

RAW = "https://raw.githubusercontent.com/vatsimnetwork/vatspy-data-project/master"
BOUNDARIES_URL = f"{RAW}/Boundaries.geojson"
VATSPY_URL = f"{RAW}/VATSpy.dat"

DEFAULT_OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "IFATCCompanion", "Enroute", "CenterSectors.json")

# Coordinate precision kept in the bundled file. 3 decimals ≈ 110 m at the
# equator — far finer than the several-mile hysteresis the handoff logic applies,
# and it roughly halves the file size versus the source's 6 decimals.
PRECISION = 3

# Boundaries that are not an enroute radar sector the companion should ever hand
# off to: terminal areas that sit *inside* an FIR (approach, not Center), military
# overlays that cover the same ground as the civil sector, and flight-information
# / information positions. Including any of them would put a bogus "contact …"
# call on the radio over ground an enroute sector already owns.
EXCLUDED_NAME_PATTERN = re.compile(
    r"\bTMA\b|\bTCA\b|\bTerminal\b|\bApproach\b|\bDeparture\b"
    r"|\bMilitary\b|\bInformation\b|\bFIS\b", re.I)

# ICAO region prefixes whose area control centres are called "Center"/"Centre" on
# the radio. Everywhere else the ICAO term "Control" is used ("London Control",
# "Tokyo Control"). Sectors whose name already ends in a radio word (Radar,
# Oceanic, …) keep it and get no suffix at all.
CENTER_REGIONS = ("K", "C", "P", "M", "Y", "TJ", "TI")

# Words that already read as a radio name — "Adria Radar", "Gander Oceanic".
RADIO_WORDS = ("radar", "control", "centre", "center", "oceanic", "radio",
               "information", "acc")

# Hand-curated radio names where the derived one would be wrong.
RADIO_OVERRIDES = {
    "KZAK": "Oakland Oceanic",
    "KZA1": "San Francisco Oceanic",
    "CZQO": "Gander Oceanic",
    "EGGX": "Shanwick Oceanic",
    "LPPO": "Santa Maria Oceanic",
    "BIRD": "Reykjavik Control",
    "NZZO": "Auckland Oceanic",
}


def fetch(url: str, cache_dir: str | None) -> str:
    name = url.rsplit("/", 1)[-1]
    if cache_dir:
        os.makedirs(cache_dir, exist_ok=True)
        path = os.path.join(cache_dir, name)
        if os.path.exists(path):
            with open(path, encoding="utf-8", errors="replace") as handle:
                return handle.read()
    req = urllib.request.Request(url, headers={"User-Agent": "IFATCCompanion-sector-build"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        text = resp.read().decode("utf-8", errors="replace")
    if cache_dir:
        with open(os.path.join(cache_dir, name), "w", encoding="utf-8") as handle:
            handle.write(text)
    return text


def parse_fir_names(dat: str) -> dict[str, str]:
    """Map boundary id -> display name, preferring the entry with no callsign
    prefix (the base FIR line) over the per-position ones that follow it."""
    names: dict[str, str] = {}
    in_firs = False
    for line in dat.splitlines():
        line = line.strip()
        if line.startswith("["):
            in_firs = line.upper() == "[FIRS]"
            continue
        if not in_firs or not line or line.startswith(";"):
            continue
        parts = line.split("|")
        if len(parts) < 4:
            continue
        _icao, name, prefix, boundary = parts[0], parts[1], parts[2], parts[3]
        if not boundary:
            continue
        if boundary not in names or (prefix == "" and boundary not in names):
            names.setdefault(boundary, name)
        if prefix == "":
            names[boundary] = name
    return names


# Australia (and a few others) name each enroute sector by the frequency it works
# on — "Melbourne 128.85", "Brisbane 133.15*" (the star marks a secondary). Where
# the source hands us a real frequency, the app uses it instead of a synthesized
# one, and the name reads as the controller's ("Melbourne Center").
FREQUENCY_IN_NAME = re.compile(r"\b(1[0-3]\d\.\d{1,3})\*?")


def frequency_in(raw: str) -> float | None:
    match = FREQUENCY_IN_NAME.search(raw)
    if not match:
        return None
    value = float(match.group(1))
    return round(value, 3) if 118.0 <= value <= 137.0 else None


def clean_name(raw: str, sector_id: str) -> str:
    """"Ahmedabad ACC - Mumbai" -> "Ahmedabad"; "Amazonico (Completo)" ->
    "Amazonico". The parenthetical and the trailing parent-FIR reference are
    VATSpy bookkeeping, not part of what a controller says."""
    name = raw.split(" - ")[0]
    name = re.sub(r"\s*\([^)]*\)", "", name)
    name = FREQUENCY_IN_NAME.sub("", name)
    name = re.sub(r"\bACC\b|\bFIR\b|\bUIR\b|\bCTA\b|\bSector\b", "", name)
    name = re.sub(r"\s+", " ", name).strip(" -")
    return name or sector_id


def radio_name(name: str, sector_id: str) -> str:
    if sector_id in RADIO_OVERRIDES:
        return RADIO_OVERRIDES[sector_id]
    last = name.split()[-1].lower() if name.split() else ""
    if last in RADIO_WORDS:
        return name
    suffix = "Center" if sector_id.startswith(CENTER_REGIONS) else "Control"
    return f"{name} {suffix}"


def round_ring(ring: list[list[float]]) -> list[float]:
    """Flatten a GeoJSON ring to [lon, lat, lon, lat, …] at reduced precision,
    dropping the repeated closing vertex and any duplicate the rounding creates."""
    out: list[float] = []
    for lon, lat in ring:
        lon = round(float(lon), PRECISION)
        lat = round(float(lat), PRECISION)
        if out and out[-2] == lon and out[-1] == lat:
            continue
        out.extend((lon, lat))
    # Drop the closing vertex: the point-in-polygon test closes the ring itself.
    if len(out) >= 4 and out[0] == out[-2] and out[1] == out[-1]:
        del out[-2:]
    return out


def ring_area(ring: list[float]) -> float:
    """Planar shoelace area of a flat [lon, lat, …] ring, with longitude scaled by
    cos(latitude) so the number is proportional to real area rather than to
    square degrees. Only ever compared against other sectors, never reported."""
    count = len(ring) // 2
    if count < 3:
        return 0.0
    scale = math.cos(math.radians(sum(ring[1::2]) / count))
    total = 0.0
    for i in range(count):
        j = (i + 1) % count
        total += ring[2 * i] * scale * ring[2 * j + 1] - ring[2 * j] * scale * ring[2 * i + 1]
    return abs(total) / 2


def sector_area(entry: dict) -> float:
    return sum(ring_area(poly[0]) - sum(ring_area(hole) for hole in poly[1:])
               for poly in entry["polygons"])


def point_in_ring(lon: float, lat: float, ring: list[float]) -> bool:
    inside = False
    count = len(ring) // 2
    j = count - 1
    for i in range(count):
        xi, yi = ring[2 * i], ring[2 * i + 1]
        xj, yj = ring[2 * j], ring[2 * j + 1]
        if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def contains(entry: dict, lon: float, lat: float) -> bool:
    if not (entry["minLon"] <= lon <= entry["maxLon"]
            and entry["minLat"] <= lat <= entry["maxLat"]):
        return False
    for poly in entry["polygons"]:
        if point_in_ring(lon, lat, poly[0]) and not any(
                point_in_ring(lon, lat, hole) for hole in poly[1:]):
            return True
    return False


def build(boundaries: dict, names: dict[str, str]) -> list[dict]:
    ids = {f["properties"]["id"] for f in boundaries["features"]}

    # Sub-sectors ("KZJX-A", "EGPX-N") tile a parent that is also present. Keep
    # the parent only: the companion hands off between whole ARTCCs / FIRs, and
    # overlapping polygons would make the containing-sector lookup ambiguous.
    def is_subdivision(sector_id: str) -> bool:
        return "-" in sector_id and sector_id.split("-")[0] in ids

    merged: dict[str, dict] = {}
    for feature in boundaries["features"]:
        props = feature["properties"]
        sector_id = props["id"]
        if is_subdivision(sector_id):
            continue
        raw_name = names.get(sector_id, sector_id)
        if EXCLUDED_NAME_PATTERN.search(raw_name):
            continue
        name = clean_name(raw_name, sector_id)
        entry = merged.setdefault(sector_id, {
            "id": sector_id,
            "name": name,
            "radio": radio_name(name, sector_id),
            "oceanic": props.get("oceanic", "0") == "1",
            "polygons": [],
        })
        published = frequency_in(raw_name)
        if published is not None:
            entry["frequency"] = published
        for polygon in feature["geometry"]["coordinates"]:
            rings = [round_ring(ring) for ring in polygon]
            rings = [r for r in rings if len(r) >= 6]  # a ring needs 3 vertices
            if rings:
                entry["polygons"].append(rings)

    sectors = []
    for entry in merged.values():
        if not entry["polygons"]:
            continue
        lons = [c for poly in entry["polygons"] for ring in poly for c in ring[0::2]]
        lats = [c for poly in entry["polygons"] for ring in poly for c in ring[1::2]]
        entry["minLon"], entry["maxLon"] = min(lons), max(lons)
        entry["minLat"], entry["maxLat"] = min(lats), max(lats)
        sectors.append(entry)
    for entry in sectors:
        entry["area"] = sector_area(entry)
    # Smallest first. Where boundaries still overlap — an upper sector stacked on a
    # lower one, a rounded edge — the lookup takes the first sector that contains the
    # aircraft, so ordering by area makes that the most specific one, deterministically.
    sectors.sort(key=lambda s: (s["area"], s["id"]))
    for entry in sectors:
        del entry["area"]
    return sectors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=DEFAULT_OUT)
    parser.add_argument("--cache-dir", default=None,
                        help="reuse (and populate) downloaded source files here")
    args = parser.parse_args()

    boundaries = json.loads(fetch(BOUNDARIES_URL, args.cache_dir))
    names = parse_fir_names(fetch(VATSPY_URL, args.cache_dir))
    sectors = build(boundaries, names)

    document = {
        "schemaVersion": 1,
        "generated": date.today().isoformat(),
        "source": "VATSIM VATSpy Data Project",
        "sourceURL": "https://github.com/vatsimnetwork/vatspy-data-project",
        "license": "CC BY-SA 4.0",
        "licenseURL": "https://creativecommons.org/licenses/by-sa/4.0/",
        "note": ("Adapted from the VATSpy Data Project (sub-sectors and terminal "
                 "areas removed, coordinates rounded). Simulation use only — not "
                 "for real-world navigation."),
        "sectors": sectors,
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(document, handle, separators=(",", ":"))
        handle.write("\n")
    size = os.path.getsize(args.out)
    print(f"{len(sectors)} sectors -> {args.out} ({size/1024:.0f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
