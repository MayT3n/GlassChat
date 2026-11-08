package com.mayt3n.glasschat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var loading: TextView

    private val allowedHosts = setOf("chatgpt.com", "www.chatgpt.com", "auth.openai.com", "openai.com", "www.openai.com")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(16, 19, 27)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(15, 18, 27))
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.rgb(15, 18, 27))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString = settings.userAgentString
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val host = uri.host?.lowercase() ?: return true
                    if (uri.scheme != "https") return true
                    if (allowedHosts.any { host == it || host.endsWith(".$it") }) return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        true
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    loading.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading.visibility = View.GONE
                }
            }
            webChromeClient = WebChromeClient()
        }

        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        val top = glassPanel(18f).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
        }
        val brand = TextView(this).apply {
            text = "◉  GlassChat"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val spacer = View(this)
        val refresh = actionText("↻") { web.reload() }
        top.addView(brand)
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(refresh, LinearLayout.LayoutParams(dp(42), dp(38)))

        val topParams = FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP).apply {
            setMargins(dp(12), dp(10) + statusBarInset(), dp(12), 0)
        }
        root.addView(top, topParams)

        loading = TextView(this).apply {
            text = "در حال بارگذاری ChatGPT…"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(Color.argb(190, 35, 42, 58), 30f)
        }
        val loadingParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(82) + statusBarInset()
        }
        root.addView(loading, loadingParams)

        val dock = glassPanel(24f).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        dock.addView(dockButton("⌂", "خانه") { web.loadUrl(HOME) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        dock.addView(dockButton("＋", "گفت‌وگوی تازه") {
            web.evaluateJavascript("document.querySelector('a[href*="/"]')?.click();", null)
            web.loadUrl(HOME)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        dock.addView(dockButton("↻", "بارگذاری") { web.reload() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        val dockParams = FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM).apply {
            setMargins(dp(18), 0, dp(18), dp(12) + navigationBarInset())
        }
        root.addView(dock, dockParams)

        setContentView(root)
        if (savedInstanceState == null) web.loadUrl(HOME) else web.restoreState(savedInstanceState)
    }

    private fun statusBarInset(): Int = if (Build.VERSION.SDK_INT >= 30) window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0 else dp(24)
    private fun navigationBarInset(): Int = if (Build.VERSION.SDK_INT >= 30) window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0 else dp(0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), Color.argb(45, 255, 255, 255))
    }

    private fun glassPanel(radius: Float) = LinearLayout(this).apply {
        background = rounded(Color.argb(185, 35, 42, 58), radius)
        elevation = dp(8).toFloat()
    }

    private fun actionText(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setOnClickListener { action() }
    }

    private fun dockButton(symbol: String, label: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        addView(TextView(this@MainActivity).apply {
            text = symbol
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(220, 225, 240))
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val HOME = "https://chatgpt.com/"
    }
}
