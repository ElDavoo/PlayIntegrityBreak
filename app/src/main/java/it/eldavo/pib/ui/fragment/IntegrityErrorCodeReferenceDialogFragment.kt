package it.eldavo.pib.ui.fragment

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import it.eldavo.pib.R

class IntegrityErrorCodeReferenceDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val webView = WebView(requireContext()).apply {
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
            settings.javaScriptEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (uri.scheme != "pibcopy") {
                        return false
                    }
                    val value = uri.getQueryParameter("value")?.trim().orEmpty()
                    if (value.isNotEmpty()) {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("integrity_error_code", value))
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.app_rewrite_integrity_error_code_copied, value),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return true
                }
            }

            val html = resources.openRawResource(R.raw.integrity_error_codes_reference)
                .bufferedReader()
                .use { it.readText() }
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.app_rewrite_integrity_error_code_reference_title)
            .setView(webView)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }
}
