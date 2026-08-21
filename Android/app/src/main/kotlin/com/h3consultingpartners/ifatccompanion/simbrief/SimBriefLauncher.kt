package com.h3consultingpartners.ifatccompanion.simbrief

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.h3consultingpartners.ifatccompanion.core.config.AppConfig

/**
 * Opens SimBrief dispatch in a Custom Tab.
 *
 * The iOS build deliberately uses `SFSafariViewController` rather than an embedded
 * `WKWebView`, and the reason ports directly: SimBrief's own page fields — most visibly
 * the Depart/Arrive airport-search boxes — only behave correctly under the browser's own
 * keyboard and focus handling. Embedding the page produced keyboard/focus races that
 * could not be fixed from the app side, and the only in-page cure would be injecting
 * JavaScript into SimBrief's page, which this app does not do out of respect for the
 * site.
 *
 * **Custom Tabs is the Android counterpart of `SFSafariViewController`**: the same engine
 * the pilot's own browser uses, their existing SimBrief session and cookies, no script of
 * ours running in the page, and a close button that returns straight to the app. A plain
 * `WebView` would reintroduce exactly the problem iOS moved away from.
 *
 * If no browser supports Custom Tabs, this falls back to an ordinary view intent, and
 * only reports failure when the device has no browser at all.
 *
 * IFATC Companion is not affiliated with SimBrief or Navigraph, does not scrape the site,
 * and does not alter what it shows.
 */
object SimBriefLauncher {

    /** Returns false only when the device has no browser at all. */
    fun open(context: Context, colorArgb: Int? = null): Boolean {
        val uri = Uri.parse(AppConfig.Links.SIMBRIEF)
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(false)
            .apply {
                if (colorArgb != null) {
                    setDefaultColorSchemeParams(
                        androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                            .setToolbarColor(colorArgb)
                            .build(),
                    )
                }
            }
            .build()
        return try {
            intent.launchUrl(context, uri)
            true
        } catch (notFound: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (stillNotFound: ActivityNotFoundException) {
                false
            }
        }
    }
}
