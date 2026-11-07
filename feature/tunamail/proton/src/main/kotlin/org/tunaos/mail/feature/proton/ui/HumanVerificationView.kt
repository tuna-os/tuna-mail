package org.tunaos.mail.feature.proton.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.tunaos.mail.feature.proton.HumanVerificationMessage

/**
 * Shows Proton's CAPTCHA page (verify.proton.me, embedded mode) and reports what it posts. The
 * page's messages reach the app through a web message listener that only frames from
 * verify.proton.me can see, injected before the page's own scripts run; navigation away from
 * verify.proton.me is refused.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HumanVerificationView(
    url: String,
    onMessage: (HumanVerificationMessage) -> Unit,
    onCannotShow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnMessage = rememberUpdatedState(onMessage)
    if (!isSupported()) {
        LaunchedEffect(Unit) { onCannotShow() }
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = VerifyOnlyWebViewClient()
                val origins = setOf(HumanVerificationMessage.ORIGIN)
                WebViewCompat.addWebMessageListener(this, HumanVerificationMessage.BRIDGE, origins) {
                        _,
                        message,
                        sourceOrigin,
                        isMainFrame,
                        _,
                    ->
                    val data = message.data
                    if (isMainFrame && sourceOrigin.toString() == HumanVerificationMessage.ORIGIN && data != null) {
                        currentOnMessage.value(HumanVerificationMessage.parse(data))
                    }
                }
                WebViewCompat.addDocumentStartJavaScript(this, HumanVerificationMessage.FORWARD_SCRIPT, origins)
                loadUrl(url)
            }
        },
        onRelease = { webView -> webView.destroy() },
    )
}

private fun isSupported(): Boolean =
    WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

private class VerifyOnlyWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !request.url.isVerifyPage()

    private fun Uri.isVerifyPage() = scheme == "https" && host == VERIFY_HOST

    private companion object {
        const val VERIFY_HOST = "verify.proton.me"
    }
}
