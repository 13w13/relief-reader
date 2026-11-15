// SPDX-FileCopyrightText: 2026 Re.L contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.relais.app.data

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.NullRequestData

/**
 * The one check every image goes through before Coil loads it (security audit of 15/11): the
 * photos of headlines, the images of reports and the previews of files. https only, http moved
 * to https ([TrustedUrls.image]); file:, content:, android.resource:, data:, a private address,
 * or anything that is not an address, is refused.
 */
object SafeImages : Interceptor {
    /** The address Coil may load for [data], or null. */
    fun allowed(data: Any?): String? = when (data) {
        is String -> TrustedUrls.image(data)
        is coil3.Uri, is android.net.Uri, is okhttp3.HttpUrl -> TrustedUrls.image(data.toString())
        else -> null
    }

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        // No image asked for: Coil gives its own empty result.
        if (data == NullRequestData) return chain.proceed()
        val url = allowed(data)
            ?: return ErrorResult(image = null, request = chain.request, throwable = IllegalArgumentException("Image address refused"))
        return chain.withRequest(chain.request.newBuilder().data(url).build()).proceed()
    }
}
