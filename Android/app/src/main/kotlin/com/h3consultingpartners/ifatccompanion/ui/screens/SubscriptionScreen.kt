package com.h3consultingpartners.ifatccompanion.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.h3consultingpartners.ifatccompanion.core.billing.EntitlementState
import com.h3consultingpartners.ifatccompanion.core.billing.PurchasePhase
import com.h3consultingpartners.ifatccompanion.core.billing.SubscriptionProduct
import com.h3consultingpartners.ifatccompanion.core.ui.SubscriptionStrings
import com.h3consultingpartners.ifatccompanion.ui.components.Card
import com.h3consultingpartners.ifatccompanion.ui.theme.IFATCTheme

data class SubscriptionScreenActions(
    val onPurchase: (SubscriptionProduct) -> Unit,
    val onRestore: () -> Unit,
    val onManage: () -> Unit,
    val onOpenTerms: () -> Unit,
    val onOpenPrivacy: () -> Unit,
)

/**
 * The paywall that unlocks Live Connected Mode.
 *
 * Ported from `IFATCCompanion/Views/SubscriptionView.swift`, with one deliberate
 * exception: the renewal disclosure and the Terms link. iOS discloses that payment is
 * charged to the customer's Apple Account and managed in Apple Account settings, which
 * is false on Android and would be a policy violation to ship. Both are rewritten for
 * Google Play in `SubscriptionStrings`. Everything else — the header, the plan cards,
 * the badges, the button wording, the status lines — is carried across unchanged.
 */
@Composable
fun SubscriptionScreen(
    state: EntitlementState,
    actions: SubscriptionScreenActions,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Header() }
        item { StatusBanner(state) }

        state.productLoadError?.let { message ->
            item { ErrorCard(message) }
        }

        // Ordered monthly, annual, lifetime — the order both platforms show them in.
        items(SubscriptionProduct.entries.size) { index ->
            val product = SubscriptionProduct.entries[index]
            ProductCard(product, state, actions)
        }

        item { RestoreAndManage(state, actions) }
        item { Disclosures() }
        item { LegalLinks(actions) }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = SubscriptionStrings.HEADER,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = SubscriptionStrings.HEADER_SUBTITLE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = SubscriptionStrings.HEADER_REQUIREMENT,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusBanner(state: EntitlementState) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (state.hasLiveAccess) Icons.Filled.Verified else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (state.hasLiveAccess) {
                    IFATCTheme.semantic.connected
                } else {
                    IFATCTheme.semantic.connecting
                },
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = SubscriptionStrings.CURRENT_STATUS,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = state.statusText, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.weight(1f))
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = IFATCTheme.semantic.connecting,
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProductCard(
    product: SubscriptionProduct,
    state: EntitlementState,
    actions: SubscriptionScreenActions,
) {
    val offer = state.products.firstOrNull { it.product == product }
    val price = offer?.displayPrice ?: product.fallbackPrice
    val purchasing = state.purchasePhase == PurchasePhase.Purchasing

    Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = offer?.displayName ?: product.fallbackDisplayName,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    SubscriptionStrings.badge(product)?.let { badge ->
                        TagBadge(
                            text = badge,
                            tint = if (product == SubscriptionProduct.ANNUAL) {
                                IFATCTheme.semantic.connected
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
                Text(
                    text = product.durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = price,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Button(
            onClick = { actions.onPurchase(product) },
            modifier = Modifier.fillMaxWidth(),
            // Nothing to buy if Play never loaded the product, and never two purchases
            // at once.
            enabled = offer != null && !purchasing && !state.hasLiveAccess,
        ) {
            if (purchasing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    text = SubscriptionStrings.purchaseButtonTitle(
                        product,
                        price,
                        state.hasLiveAccess,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

@Composable
private fun TagBadge(text: String, tint: Color) {
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.6f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
    }
}

@Composable
private fun RestoreAndManage(state: EntitlementState, actions: SubscriptionScreenActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PurchaseStatusLine(state)

        OutlinedButton(
            onClick = actions.onRestore,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.purchasePhase != PurchasePhase.Purchasing,
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Text("  ${SubscriptionStrings.RESTORE}")
        }
        OutlinedButton(onClick = actions.onManage, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Text("  ${SubscriptionStrings.MANAGE}")
        }
    }
}

@Composable
private fun PurchaseStatusLine(state: EntitlementState) {
    val (text, tint) = when (val phase = state.purchasePhase) {
        PurchasePhase.Purchased ->
            SubscriptionStrings.PURCHASED_MESSAGE to IFATCTheme.semantic.connected

        PurchasePhase.Cancelled ->
            SubscriptionStrings.CANCELLED_MESSAGE to MaterialTheme.colorScheme.onSurfaceVariant

        // Play routinely holds a purchase while a delayed payment clears. iOS has no real
        // equivalent, so rather than leave the customer in Mock Mode wondering, Android
        // says so.
        PurchasePhase.Pending ->
            EntitlementState.PURCHASE_PENDING_MESSAGE to IFATCTheme.semantic.connecting

        is PurchasePhase.Failed -> phase.message to IFATCTheme.semantic.connecting

        PurchasePhase.Purchasing, PurchasePhase.Idle -> return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun Disclosures() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (line in SubscriptionStrings.disclosures) {
            Text(
                text = line,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegalLinks(actions: SubscriptionScreenActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = actions.onOpenTerms) {
            Text(SubscriptionStrings.TERMS_LINK_TITLE)
        }
        TextButton(onClick = actions.onOpenPrivacy) {
            Text(SubscriptionStrings.PRIVACY_LINK_TITLE)
        }
    }
}
