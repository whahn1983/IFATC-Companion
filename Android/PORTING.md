# Porting conventions (iOS → Android)

The iOS app at the repository root is the **authoritative specification**. This
document is the house style for translating it, so that code written by different
people (or at different times) reads as one codebase.

## Where code goes

| Concern | Module | Package root |
| --- | --- | --- |
| Domain models, engines, parsers, networking, algorithms | `:core` | `com.h3consultingpartners.ifatccompanion.core` |
| Compose UI, ViewModels, service, audio, notifications, billing client, resources | `:app` | `com.h3consultingpartners.ifatccompanion` |

`:core` is **pure Kotlin/JVM**. It must not import anything from `android.*` or
`androidx.*`. That is what lets the whole engine be unit tested on a plain JVM, and it
enforces the layering the iOS app keeps by convention: the ATC engine does not know
about the UI, taxi routing does not know about the map renderer, and weather does not
know about either.

Anything `:core` needs from the platform is a small interface in
`core/platform/` (`Clock`, `DiagnosticsSink`, `KeyValueStore`, `FileStore`) or
`core/net/` (`HttpFetching`), implemented in `:app`.

Compose screens live in `app/.../ui/screens` and `app/.../ui/components` and must be
**pure**: they take state and callbacks as parameters and import nothing
Android-specific, so `settings-uicheck.gradle.kts` can type-check them without the
Android SDK. Android glue (Activity, ViewModel, resources, permissions) lives in
`app/.../ui/android` and other `:app` packages.

## Fidelity rules

1. **Never invent a constant.** Every threshold, interval, distance, tolerance,
   timeout, retry count, backoff, radius and score weight comes from the Swift, at its
   exact value, with its units named in a comment or a doc string.
2. **Never re-author phraseology.** Every string a pilot could see or hear is copied
   verbatim, including punctuation and casing. Where the Swift builds a string from
   parts, build it from the same parts in the same order.
3. **Preserve behaviour, including the awkward parts.** Early returns, guard clauses,
   ordering, and off-by-one details are all load bearing. If something looks like a
   bug, port it as-is and note it — do not fix it in passing.
4. **Carry the comments across.** The iOS source explains *why* it does what it does,
   often citing a field failure. That reasoning is the most valuable thing in the file
   and must survive the port, rewritten to describe the Kotlin.
5. **Raw values match.** Enum raw values, `UserDefaults`/DataStore keys, and JSON keys
   keep the exact strings the Swift uses, so persisted data means the same thing on
   both platforms.

## Kotlin style

- `data class` for value types; `sealed interface` for Swift enums with associated
  values; `enum class` (with an explicit `rawValue`) for plain Swift enums.
- Swift optionals become nullable types. Do not substitute a default where the Swift
  keeps `nil` — the difference between "unknown" and "zero" is meaningful throughout.
- Swift `actor` becomes a class guarded by a `kotlinx.coroutines.sync.Mutex`;
  `async/await` becomes `suspend`; `Task` becomes a coroutine on an injected scope.
- `@Published` state becomes `StateFlow` exposed from an engine, aggregated into one
  immutable state object per feature rather than dozens of independent flows.
- `Codable` becomes `@Serializable` with `kotlinx.serialization`.
- Prefer `expression bodies` and standard library functions over loops where it reads
  better, but never at the cost of matching the Swift's control flow.
- Public API gets KDoc. Internal helpers get a comment only where the reason isn't
  obvious from the name.

## Units

Infinite Flight reports metres per second and (on some builds) radians. The
conversions live where iOS put them — in the state reader — and the rest of the engine
works in knots, feet, feet per minute and degrees. Do not convert twice.

## Verifying

```bash
cd Android

# Engine: compiles and tests on a plain JVM, no Android SDK needed.
./gradlew -c settings-core.gradle.kts :core:test

# Compose screens: type-checks against JetBrains Compose, no Android SDK needed.
./gradlew -c settings-uicheck.gradle.kts :uicheck:compileKotlin

# The real thing, in Android Studio or on a machine with the SDK.
./gradlew :app:assembleDebug
```

Every change must leave `:core:test` and `:uicheck:compileKotlin` green.

## Tests

Mirror the iOS test suite in `IFATCCompanionTests/`. A ported test keeps the original's
name (camelCased), its assertions, and — importantly — the comment explaining which
field failure it guards against.
