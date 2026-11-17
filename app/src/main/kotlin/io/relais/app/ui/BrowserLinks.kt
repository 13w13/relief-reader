// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.platform.UriHandler
import io.relais.app.LinkActivity
import io.relais.app.R
import io.relais.app.data.TrustedUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Opening a page of reliefweb.int "in the browser" from Re.L (review of PR 47). Once the reader lets
 * Re.L open reliefweb.int links, a plain ACTION_VIEW comes back to Re.L (LinkActivity), and "Open
 * in browser" or "Open on ReliefWeb" would never reach the browser. These intents aim at a browser.
 */
object BrowserLinks {
    /** A page of reliefweb.int or a subdomain, which Re.L may be the handler of. */
    fun isReliefWeb(url: String): Boolean =
        url.trim().toHttpUrlOrNull()?.let { TrustedUrls.isReliefWebHost(it.host) } == true

    /**
     * ACTION_VIEW for [url], found by its selector: a web address with no host ("https:"). Android
     * looks for the app with the selector only, then hands it this intent with the full address.
     * Browsers take any https address, so they match; LinkActivity takes reliefweb.int only, so it
     * never does. Android counts it as a web intent: the reader's default browser opens it.
     *
     * Not Intent.makeMainSelectorActivity: what it returns (MAIN, LAUNCHER) already has its own
     * selector, and Android follows one level only, which would match every app with an icon.
     */
    fun browserIntent(url: String): Intent {
        val page = Uri.parse(url.trim())
        return Intent(Intent.ACTION_VIEW, page)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .apply { selector = anyWebPage(page.scheme) }
    }

    /** A web address with no host, in [scheme] (https by default). */
    internal fun anyWebPage(scheme: String?): Intent =
        Intent(Intent.ACTION_VIEW, Uri.fromParts(if (scheme.equals("http", ignoreCase = true)) "http" else "https", "", null))
            .addCategory(Intent.CATEGORY_BROWSABLE)

    /** The fallback when no app says it is a browser: the usual chooser, without Re.L in it. */
    fun chooserWithoutRel(context: Context, url: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())), null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, LinkActivity::class.java)))

    /** Opens [url] in a browser; false when the phone has no app to open it. */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(browserIntent(url))
        true
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(chooserWithoutRel(context, url)) }.isSuccess
    } catch (e: SecurityException) {
        false
    }
}

/**
 * The app's UriHandler (MainActivity): a page of reliefweb.int goes to a browser ([BrowserLinks]),
 * anything else as before. safeOpen goes through it, so every "Open in browser" and "Open on
 * ReliefWeb" of the app does. When nothing on the phone can open the link, a short message says
 * so, then IllegalArgumentException, as Compose's own handler throws.
 */
class RelaisUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) {
        val opened = if (BrowserLinks.isReliefWeb(uri)) {
            BrowserLinks.open(context, uri)
        } else {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
        if (!opened) {
            Toast.makeText(context, R.string.link_no_app, Toast.LENGTH_SHORT).show()
            throw IllegalArgumentException("No app can open this link.")
        }
    }
}
