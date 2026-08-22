package com.h3consultingpartners.ifatccompanion.core.surface

import kotlinx.serialization.json.Json

/**
 * The JSON codec for everything in the airport-surface pipeline: the Overpass extract
 * on the way in, and the normalized model on the way to (and from) the cache.
 *
 * `ignoreUnknownKeys` matches `JSONDecoder`'s behaviour of ignoring members the
 * `Codable` type doesn't declare — an Overpass document carries `version`, `osm3s`
 * and other envelope keys the model never reads.
 *
 * `encodeDefaults` matters for the cache file: Swift's synthesized `encode(to:)`
 * always writes every stored property, including ones whose value happens to equal
 * their default (the provenance's provider / license / attribution, and the schema
 * version). kotlinx omits defaults unless told otherwise, and a cache file missing
 * `schemaVersion` decodes as legacy version 1 — which would make every freshly
 * written cache look outdated and re-fetch on the next load.
 */
val SurfaceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
