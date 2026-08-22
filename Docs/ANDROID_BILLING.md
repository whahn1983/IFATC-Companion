# Billing and entitlements on Android

## The model, unchanged from iOS

| | |
| --- | --- |
| **Mock Mode** | Free, always. No purchase, no account, no network, no Infinite Flight. |
| **Live Connected Mode** | Paid. Unlocked by **any one** of the three products below. |

| Product | Play product type | Product ID | Base plan |
| --- | --- | --- | --- |
| Live Connected Monthly | Subscription | `com.h3consultingpartners.ifatccompanion.live.monthly` | `monthly` |
| Live Connected Annual | Subscription | `com.h3consultingpartners.ifatccompanion.live.annual` | `annual` |
| Live Connected Lifetime | One-time (INAPP) | `com.h3consultingpartners.ifatccompanion.live.lifetime` | — |

Every identifier lives in one place: `core/config/AppConfig.Billing`. Nothing in the app
hard-codes a product ID anywhere else.

Reference pricing carried from the iOS StoreKit configuration — **$2.99/month,
$24.99/year, $79.99 lifetime** — is used only as a fallback label when Play's own
localized price cannot be loaded. Play's `formattedPrice` always wins.

## The entitlement rule

> Live Access is granted when the customer has an active **Monthly** subscription, **OR**
> an active **Annual** subscription, **OR** the **Lifetime** one-time entitlement.

Implemented once, in `core/billing/LiveAccessRules.kt`, and unit tested. It is the same
rule the iOS `EntitlementManager` applies over `Transaction.currentEntitlements`.

**No expiry arithmetic is needed on either platform.** Play keeps an entitled
auto-renewing subscription in `queryPurchasesAsync` for exactly as long as it is
entitled — including the cancelled-but-not-yet-expired window and Play's grace period —
and drops it when it is not. StoreKit's `currentEntitlements` behaves the same way. Both
platforms answer "is this customer entitled right now?" directly, so the app never has to
compute it.

## What the client does

`app/billing/PlayBillingRepository.kt`.

- **Connect** to Play, with `enablePendingPurchases(enableOneTimeProducts())` — the
  Lifetime purchase is a one-time product and Play can hold one pending while a delayed
  payment form clears.
- **Load products**: subscriptions and one-time products are queried separately (Play
  requires it), then merged in the order the subscription screen shows them — monthly,
  annual, lifetime. A product that fails to load falls back to its hard-coded name and
  reference price rather than vanishing from the screen.
- **Query purchases** on every launch and after every purchase event, and derive
  entitlement from the result.
- **Acknowledge** every `PURCHASED` purchase the moment it is seen — before entitlement
  is even applied. Play automatically refunds and revokes anything left unacknowledged
  for three days.
- **Restore**: re-querying purchases *is* the restore on Play; there is no
  `AppStore.sync()` equivalent, and the query already runs on every launch. The button is
  still offered, because a customer who has just reinstalled looks for one.
- **Pending purchases** are surfaced explicitly ("Your purchase is pending. Live
  Connected Mode unlocks as soon as Google Play completes the payment.") rather than
  silently leaving the customer in Mock Mode wondering.
- **Cache** the last entitlement Play confirmed, so a launch with no connectivity does
  not drop a paying customer into Mock Mode mid-flight. It is only ever a bridge: the
  first successful query replaces it, and a revoked or expired entitlement clears it as
  soon as Play can be reached. It is excluded from cloud backup and device transfer
  (`res/xml/backup_rules.xml`) so it can never travel between accounts.

## Play-specific UI wording

Android must not use Apple's language or links.

| Where | Android |
| --- | --- |
| Manage / cancel | Google Play subscription management — `https://play.google.com/store/account/subscriptions?sku=<product>&package=<app>` |
| Terms | Google Play Terms of Service — `https://play.google.com/about/play-terms/` (Apple's standard EULA link is **not** used) |
| Privacy | H3 Consulting Partners privacy policy — unchanged from iOS, platform-independent |
| Renewal disclosure | Named as a Google Play subscription that renews until cancelled in Google Play |

## Security: what a client-only implementation can and cannot do

The app has **no backend**, by design, and the brief forbids inventing one. Google's
recommended architecture for a hardened implementation is server-side verification: the
purchase token is sent to your server, which calls the Play Developer API
(`purchases.subscriptionsv2.get` / `purchases.products.get`) and grants entitlement from
that authoritative answer, with Real-time Developer Notifications keeping it current.

**What the client-only implementation does provide**

- Purchases are made through Play's own billing flow; the app never sees payment details.
- Entitlement is read from Play's own purchase records on the device, not from anything
  the app writes.
- Every purchase is acknowledged, so Play's own three-day revocation protects against
  purchases that are never confirmed.
- Cancellations, expiries, refunds and revocations are reflected on the next query,
  because Play stops returning the purchase.
- The cached entitlement is a short bridge for offline launches, is never authoritative,
  and never leaves the device.

**What cannot be securely accomplished client-only**

| Capability | Why the client cannot do it |
| --- | --- |
| Proving a purchase is genuine against tampering | Local signature verification requires embedding the app's public key, and any check that runs on the device can be patched out on a rooted device or in a repackaged APK. |
| Detecting a revocation immediately | Without Real-time Developer Notifications the app learns about a chargeback or refund only on its next query. |
| Preventing entitlement sharing across accounts | Play ties a purchase to a Google account; the client cannot add anything on top. |
| Surviving a hostile client | Anything client-side can be bypassed by someone determined to bypass it. |

**Practical risk.** The exposure is a modified APK on a rooted device unlocking Live
Connected Mode without paying. That is piracy of a feature, not a route to anyone's data:
the app holds no accounts, no personal data and no server-side resources, so a bypass
costs a subscription and nothing else. For an app of this shape that is a proportionate
trade against the cost of running, securing and paying for infrastructure that the
product otherwise does not need.

**What a backend would add, if the owner ever wants it**: server-side verification of
each purchase token, Real-time Developer Notifications for immediate revocation, and a
server-held entitlement the client cannot forge. It would also introduce a service to
host, secure, monitor and pay for, plus a privacy surface the app currently does not
have. **This has not been built, and should not be without an explicit decision.**

## Testing

`core` covers the rules that can be tested without Play: Mock Mode stays free, each of
the three products grants access on its own, a pending purchase does not grant access, an
absent purchase revokes it, and unacknowledged purchases are surfaced for acknowledgement.

The Play-facing half — a real purchase flow, restore on a fresh install, a genuine
pending purchase, grace period and expiry — needs Play's own test tracks and licence
testers, and is listed as manual work in `Docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md`.
