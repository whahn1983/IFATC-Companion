import SwiftUI
import StoreKit

/// The subscription screen that unlocks Live Connected Mode. Shows the monthly
/// and annual options with StoreKit-localized pricing, the required renewal
/// disclosure, restore / manage actions, and the Terms / Privacy links.
struct SubscriptionView: View {
    @EnvironmentObject var entitlements: EntitlementManager
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    headerSection
                    statusBanner
                    if let error = entitlements.productLoadError {
                        errorCard(error)
                    }
                    productOptions
                    restoreAndManage
                    disclosureSection
                    legalLinks
                }
                .padding(16)
            }
            .navigationTitle("Subscription")
            .navigationBarTitleDisplayMode(.inline)
            .screenBackground()
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                // Refresh products/entitlements when the screen appears.
                if entitlements.products.isEmpty { await entitlements.refresh() }
            }
            .onDisappear { entitlements.resetPurchasePhase() }
        }
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Unlock Live Connected Mode")
                .font(.title2.weight(.bold))
            Text("Use IFATC Companion on iPhone while flying Infinite Flight on iPad.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("Live Connected Mode requires Infinite Flight, sold separately, running on another device on the same local Wi-Fi network.")
                .font(.footnote)
                .foregroundStyle(.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Status

    private var statusBanner: some View {
        Card {
            HStack(spacing: 10) {
                Image(systemName: entitlements.hasLiveAccess ? "checkmark.seal.fill" : "lock.fill")
                    .font(.title3)
                    .foregroundStyle(entitlements.hasLiveAccess ? Color.green : Color.orange)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Current Status").font(.caption).foregroundStyle(.secondary)
                    Text(entitlements.statusText).font(.headline)
                }
                Spacer(minLength: 0)
                if entitlements.isLoading { ProgressView() }
            }
        }
    }

    private func errorCard(_ message: String) -> some View {
        Card {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                Text(message)
                    .font(.callout)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - Product options

    private var productOptions: some View {
        VStack(spacing: 12) {
            productCard(for: .monthly)
            productCard(for: .annual)
        }
    }

    @ViewBuilder
    private func productCard(for plan: SubscriptionProduct) -> some View {
        let product = product(for: plan)
        Card {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 8) {
                            Text(product?.displayName ?? plan.fallbackDisplayName)
                                .font(.headline)
                            if plan == .annual { bestValueBadge }
                        }
                        Text(plan.durationText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(product?.displayPrice ?? plan.fallbackPrice)
                        .font(.title3.weight(.semibold).monospacedDigit())
                }
                purchaseButton(for: plan, product: product)
            }
        }
    }

    private var bestValueBadge: some View {
        Text("Best value")
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Capsule().fill(Color.green.opacity(0.22)))
            .overlay(Capsule().stroke(Color.green.opacity(0.6), lineWidth: 1))
            .foregroundStyle(Color.green)
    }

    @ViewBuilder
    private func purchaseButton(for plan: SubscriptionProduct, product: Product?) -> some View {
        let isPurchasing = entitlements.purchasePhase == .purchasing
        Button {
            guard let product else { return }
            Task { await entitlements.purchase(product) }
        } label: {
            HStack {
                Spacer()
                if isPurchasing {
                    ProgressView().tint(.white)
                } else {
                    Text(entitlements.hasLiveAccess ? "Subscribed" : "Subscribe — \(product?.displayPrice ?? plan.fallbackPrice)")
                        .font(.headline)
                }
                Spacer()
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.borderedProminent)
        .tint(plan == .annual ? .green : .accentColor)
        .disabled(product == nil || isPurchasing || entitlements.hasLiveAccess)
    }

    private func product(for plan: SubscriptionProduct) -> Product? {
        switch plan {
        case .monthly: return entitlements.monthlyProduct
        case .annual:  return entitlements.annualProduct
        }
    }

    // MARK: - Restore / manage / states

    private var restoreAndManage: some View {
        VStack(spacing: 10) {
            purchaseStatusText

            Button {
                Task { await entitlements.restorePurchases() }
            } label: {
                Label("Restore Purchases", systemImage: "arrow.clockwise")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(entitlements.purchasePhase == .purchasing)

            Button {
                manageSubscriptions()
            } label: {
                Label("Manage Subscription", systemImage: "gearshape")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    @ViewBuilder
    private var purchaseStatusText: some View {
        switch entitlements.purchasePhase {
        case .purchased:
            statusLine("Live Connected Mode unlocked. Thank you!", color: .green, icon: "checkmark.circle.fill")
        case .cancelled:
            statusLine("Purchase cancelled — Mock Mode is still active.", color: .secondary, icon: "info.circle")
        case .failed(let message):
            statusLine(message, color: .orange, icon: "exclamationmark.triangle.fill")
        case .purchasing, .idle:
            EmptyView()
        }
    }

    private func statusLine(_ text: String, color: some ShapeStyle, icon: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: icon)
            Text(text).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .font(.footnote)
        .foregroundStyle(color)
    }

    /// Present Apple's subscription management UI when possible, otherwise open
    /// the App Store subscriptions page.
    private func manageSubscriptions() {
        Task {
            #if os(iOS)
            if let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive }) {
                do {
                    try await AppStore.showManageSubscriptions(in: scene)
                    await entitlements.refreshEntitlement()
                    return
                } catch {
                    // Fall through to the App Store URL below.
                }
            }
            #endif
            if let url = URL(string: "https://apps.apple.com/account/subscriptions") {
                openURL(url)
            }
        }
    }

    // MARK: - Disclosure & legal

    private var disclosureSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Payment will be charged to your Apple Account at confirmation of purchase. Subscription automatically renews unless auto-renew is turned off at least 24 hours before the end of the current period. Your account will be charged for renewal within 24 hours prior to the end of the current period. You can manage or cancel your subscription in your Apple Account settings after purchase.")
            Text("Mock Mode is free and does not require a subscription.")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var legalLinks: some View {
        HStack(spacing: 16) {
            Link("Terms of Use", destination: SubscriptionLinks.termsOfUse)
            Link("Privacy Policy", destination: SubscriptionLinks.privacyPolicy)
            Spacer()
        }
        .font(.footnote.weight(.medium))
        .padding(.top, 4)
    }
}

#Preview {
    SubscriptionView()
        .environmentObject(EntitlementManager())
        .preferredColorScheme(.dark)
}
