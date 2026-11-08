package com.mayt3n.glasschat

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.mayt3n.glasschat.auth.ChatGPTAuth
import com.mayt3n.glasschat.auth.ChatGPTSession
import com.mayt3n.glasschat.network.ModelOption
import com.mayt3n.glasschat.network.PromptMessage
import com.mayt3n.glasschat.network.ResponsesClient
import java.util.concurrent.Executors

private data class ChatMessage(val role: String, var text: String)

class MainActivity : Activity() {
    private lateinit var auth: ChatGPTAuth
    private var session: ChatGPTSession? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val messages = mutableListOf<ChatMessage>()
    private var models: List<ModelOption> = emptyList()
    private var modelSlug = ""
    private var modelLabel = "در حال آماده‌سازی مدل‌ها"
    private var loginRunning = false
    private var sending = false

    private lateinit var chatColumn: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var composer: EditText
    private lateinit var sendAction: TextView
    private lateinit var modelChip: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(8, 11, 20)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        auth = ChatGPTAuth(this)
        session = auth.savedSession()
        val saved = session
        when {
            saved == null -> showLogin()
            !saved.canUsePlan() -> showPlanUnavailable(saved)
            else -> showChat()
        }
    }

    private fun showLogin(status: String = "", busy: Boolean = false) {
        val root = baseRoot()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(page, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val brandMark = TextView(this).apply {
            text = "✳"
            textSize = 31f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(116, 153, 255), Color.rgb(95, 79, 190), Color.rgb(53, 185, 191))
            ).apply { cornerRadius = dp(23).toFloat() }
            elevation = dp(18).toFloat()
        }
        page.addView(brandMark, LinearLayout.LayoutParams(dp(66), dp(66)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(15)
        })

        page.addView(labelText("GLASSCHAT", 12f, Color.rgb(185, 205, 255), true).apply {
            letterSpacing = 0.20f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        page.addView(labelText("گفت‌وگو، با امضای خودت", 27f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })

        page.addView(labelText(
            "یک تجربه مستقل و مینیمال برای ChatGPT؛ با رابطی که از اول تا آخر برای GlassChat طراحی شده.",
            14f, Color.rgb(190, 199, 220)
        ).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(26)
        })

        val card = glassPanel(30).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
        }
        page.addView(card, LinearLayout.LayoutParams(-1, -2))

        card.addView(labelText("ورود امن", 18f, Color.WHITE, true))
        card.addView(labelText(
            "برای اتصال، با حساب ChatGPT خودت وارد شو. اگر حسابت را با گوگل ساخته‌ای، گزینه Google را در صفحه رسمی ورود انتخاب کن.",
            13f, Color.rgb(190, 201, 224)
        ).apply { setLineSpacing(dp(4).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(20)
        })

        card.addView(actionButton(
            if (busy) "در حال اتصال..." else "ادامه با ChatGPT",
            if (busy) "◌" else "✳"
        ) {
            if (!busy) startSignIn()
        }, LinearLayout.LayoutParams(-1, dp(58)))

        val securityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(Color.argb(24, 151, 183, 255), 17, Color.argb(36, 190, 210, 255))
        }
        securityRow.addView(labelText("◇", 18f, Color.rgb(166, 196, 255), true), LinearLayout.LayoutParams(dp(28), -2))
        securityRow.addView(labelText(
            "رمز گوگل یا ChatGPT در اختیار GlassChat قرار نمی‌گیرد. تأیید هویت در پنجره امن مرورگر انجام می‌شود.",
            11.5f, Color.rgb(191, 202, 226)
        ), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(securityRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        if (status.isNotBlank()) {
            card.addView(labelText(status, 12f, Color.rgb(255, 205, 158)).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }

        page.addView(labelText(
            "GlassChat یک کلاینت مستقل و غیررسمی است. گفت‌وگوهای این برنامه از تاریخچه اپ رسمی جدا هستند.",
            11f, Color.rgb(139, 151, 178)
        ).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(22)
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
        setContentView(root)
    }

    private fun startSignIn() {
        if (loginRunning) return
        loginRunning = true
        showLogin("در حال آماده‌سازی ورود امن. پس از تأیید، به برنامه برگرد.", true)
        worker.execute {
            try {
                val connected = auth.signIn()
                session = connected
                runOnUiThread {
                    loginRunning = false
                    if (connected.canUsePlan()) {
                        messages.clear()
                        showChat()
                        loadModels()
                    } else {
                        showPlanUnavailable(connected)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loginRunning = false
                    showLogin("ورود کامل نشد: " + (e.message ?: "خطای نامشخص"), false)
                }
            }
        }
    }

    private fun showPlanUnavailable(account: ChatGPTSession) {
        val root = baseRoot()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val card = glassPanel(28).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(24), dp(22), dp(24)) }
        content.addView(labelText("GLASSCHAT", 12f, Color.rgb(185, 205, 255), true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        content.addView(card, LinearLayout.LayoutParams(-1, -2))
        card.addView(labelText("حساب متصل شد", 22f, Color.WHITE, true))
        card.addView(labelText(account.email.ifBlank { account.name }, 13f, Color.rgb(169, 195, 255)), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(7)
        })
        card.addView(labelText(
            "اما اجازه استفاده از سهمیه ChatGPT برای درخواست‌های این کلاینت صادر نشده است. ورود هویت به‌تنهایی دسترسی مدل نمی‌دهد. دسترسی استفاده از پلن، مجوز جداگانه‌ای است و طبق مستند فعلی برای حساب‌های واجد شرایط Plus و Pro ارائه می‌شود. حساب Free ممکن است در این مرحله امکان پاسخ‌گویی نداشته باشد.",
            13f, Color.rgb(194, 203, 224)
        ).apply { setLineSpacing(dp(4).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(16)
            bottomMargin = dp(20)
        })
        card.addView(actionButton("رفتن به ChatGPT", "↗") {
            openExternal("https://chatgpt.com/")
        }, LinearLayout.LayoutParams(-1, dp(54)))
        card.addView(actionButton("خروج از حساب", "↪", secondary = true) {
            worker.execute {
                auth.signOut(account)
                session = null
                runOnUiThread { showLogin() }
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun showChat() {
        val root = baseRoot()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        val header = glassPanel(23).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
        }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(labelText("GlassChat", 17f, Color.WHITE, true))
            addView(labelText("A quieter space for ideas", 9.5f, Color.rgb(156, 175, 211)))
        }, LinearLayout.LayoutParams(0, -2, 1f))

        modelChip = labelText(modelLabel, 10.5f, Color.rgb(203, 216, 255), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(Color.argb(35, 126, 156, 255), 30, Color.argb(48, 180, 198, 255))
            maxWidth = dp(132)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { loadModels() }
        }
        header.addView(modelChip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })

        val accountButton = labelText("●", 17f, Color.rgb(187, 207, 255), true).apply {
            gravity = Gravity.CENTER
            background = roundedBackground(Color.argb(34, 255, 255, 255), 30, Color.argb(44, 255, 255, 255))
            setOnClickListener { showAccountDialog() }
        }
        header.addView(accountButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        page.addView(header, LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(9) })

        chatColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(14), dp(3), dp(16))
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(chatColumn, FrameLayout.LayoutParams(-1, -2))
        }
        page.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(7), dp(3), dp(5))
        }
        val composerCard = glassPanel(25).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(8), dp(8), dp(8))
        }
        composer = EditText(this).apply {
            hint = "پیامت رو بنویس..."
            setHintTextColor(Color.rgb(140, 153, 181))
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            background = null
            minLines = 1
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(5), dp(7), dp(5), dp(7))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessage()
                    true
                } else false
            }
        }
        composerCard.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        sendAction = labelText("↑", 23f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(113, 146, 255), Color.rgb(90, 106, 220))
            ).apply { cornerRadius = dp(18).toFloat() }
            setOnClickListener { sendMessage() }
        }
        composerCard.addView(sendAction, LinearLayout.LayoutParams(dp(48), dp(46)).apply { marginStart = dp(8) })
        bottom.addView(composerCard, LinearLayout.LayoutParams(-1, -2))
        bottom.addView(labelText(
            "با سهمیه پلن ChatGPT  •  مدیریت دسترسی در تنظیمات حساب",
            9.5f, Color.rgb(134, 150, 182)
        ).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        page.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        renderMessages()
        if (models.isEmpty()) loadModels()
    }

    private fun loadModels() {
        val current = session ?: return
        modelLabel = "در حال دریافت مدل‌ها"
        if (::modelChip.isInitialized) modelChip.text = modelLabel
        worker.execute {
            try {
                val refreshed = auth.validSessionOrRefresh(current)
                session = refreshed
                val available = ResponsesClient.listModels(refreshed)
                if (available.isEmpty()) throw IllegalStateException("مدل قابل استفاده‌ای برای این حساب برنگشت.")
                models = available
                modelSlug = available.first().slug
                modelLabel = available.first().displayName
                runOnUiThread {
                    if (::modelChip.isInitialized) modelChip.text = modelLabel
                }
            } catch (e: Exception) {
                modelLabel = "مدل در دسترس نیست"
                runOnUiThread {
                    if (::modelChip.isInitialized) modelChip.text = modelLabel
                    Toast.makeText(this, e.message ?: "دریافت مدل‌ها ناموفق بود", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sendMessage() {
        if (sending) return
        val text = composer.text.toString().trim()
        if (text.isBlank()) return
        if (modelSlug.isBlank()) {
            Toast.makeText(this, "مدل هنوز آماده نیست. یک لحظه بعد دوباره امتحان کن.", Toast.LENGTH_SHORT).show()
            return
        }
        val activeSession = session
        if (activeSession == null || !activeSession.canUsePlan()) {
            Toast.makeText(this, "اتصال ChatGPT نیاز به ورود دوباره دارد.", Toast.LENGTH_LONG).show()
            showLogin()
            return
        }

        composer.setText("")
        messages.add(ChatMessage("user", text))
        val answer = ChatMessage("assistant", "")
        messages.add(answer)
        sending = true
        sendAction.alpha = 0.45f
        val answerView = renderMessages(returnLastAssistant = true)
        worker.execute {
            try {
                val refreshed = auth.validSessionOrRefresh(activeSession)
                session = refreshed
                val history = messages.filter { it.text.isNotBlank() }
                    .map { PromptMessage(it.role, it.text) }
                ResponsesClient.streamReply(refreshed, modelSlug, history) { delta ->
                    answer.text += delta
                    runOnUiThread {
                        answerView.text = answer.text
                        scrollToBottom()
                    }
                }
                runOnUiThread {
                    sending = false
                    sendAction.alpha = 1f
                }
            } catch (e: Exception) {
                runOnUiThread {
                    answer.text = e.message ?: "ارتباط با مدل قطع شد."
                    answerView.text = answer.text
                    sending = false
                    sendAction.alpha = 1f
                    scrollToBottom()
                }
            }
        }
    }

    private fun renderMessages(returnLastAssistant: Boolean = false): TextView {
        chatColumn.removeAllViews()
        if (messages.isEmpty()) {
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(45), dp(20), dp(35))
            }
            welcome.addView(labelText("✦", 34f, Color.rgb(160, 185, 255), true).apply {
                gravity = Gravity.CENTER
            })
            welcome.addView(labelText("امروز چی توی ذهنته؟", 22f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })
            welcome.addView(labelText("یک سؤال، یک ایده یا یک مسئله پیچیده؛ از همین‌جا شروع کن.", 13f, Color.rgb(169, 183, 211)).apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            chatColumn.addView(welcome, LinearLayout.LayoutParams(-1, -1))
        } else {
            var lastAssistant: TextView? = null
            messages.forEach { message ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = if (message.role == "user") Gravity.END else Gravity.START
                    setPadding(dp(3), dp(4), dp(3), dp(4))
                }
                val bubble = TextView(this).apply {
                    text = message.text.ifBlank { "…" }
                    textSize = 14f
                    setTextColor(if (message.role == "user") Color.WHITE else Color.rgb(231, 237, 250))
                    setLineSpacing(dp(4).toFloat(), 1f)
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setPadding(dp(15), dp(12), dp(15), dp(12))
                    maxWidth = (resources.displayMetrics.widthPixels * 0.80f).toInt()
                    background = if (message.role == "user") {
                        roundedBackground(Color.argb(210, 83, 111, 195), 20, Color.argb(65, 183, 203, 255))
                    } else {
                        roundedBackground(Color.argb(105, 255, 255, 255), 20, Color.argb(48, 255, 255, 255))
                    }
                }
                row.addView(bubble, LinearLayout.LayoutParams(-2, -2))
                chatColumn.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
                if (message.role == "assistant") lastAssistant = bubble
            }
            scrollToBottom()
            return lastAssistant ?: labelText("", 1f, Color.TRANSPARENT)
        }
        return labelText("", 1f, Color.TRANSPARENT)
    }

    private fun showAccountDialog() {
        val account = session ?: return
        AlertDialog.Builder(this)
            .setTitle("حساب متصل")
            .setMessage(account.email + "\n\nدرخواست‌ها با اجازه استفاده از پلن ChatGPT ارسال می‌شوند. برای مدیریت دسترسی و محدودیت، تنظیمات Usage را در ChatGPT باز کن.")
            .setPositiveButton("مدیریت Usage") { _, _ -> openExternal("https://chatgpt.com/") }
            .setNegativeButton("خروج از حساب") { _, _ ->
                worker.execute {
                    auth.signOut(account)
                    session = null
                    runOnUiThread {
                        messages.clear()
                        models = emptyList()
                        modelSlug = ""
                        showLogin()
                    }
                }
            }
            .setNeutralButton("بستن", null)
            .show()
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "مرورگر پیدا نشد.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scrollToBottom() {
        if (::chatScroll.isInitialized) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun baseRoot(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 11, 20))
            setPadding(0, dp(24), 0, dp(22))
            clipChildren = false
            clipToPadding = false
        }
        root.addView(AuroraBackgroundView(this), FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun glassPanel(radius: Int): LinearLayout {
        return LinearLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(62, 255, 255, 255), Color.argb(27, 174, 195, 255), Color.argb(18, 255, 255, 255))
            ).apply {
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), Color.argb(55, 230, 238, 255))
            }
            elevation = dp(10).toFloat()
        }
    }

    private fun actionButton(text: String, symbol: String, secondary: Boolean = false, action: () -> Unit): LinearLayout {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = if (secondary) {
                roundedBackground(Color.argb(30, 255, 255, 255), 17, Color.argb(55, 255, 255, 255))
            } else {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(109, 137, 242), Color.rgb(105, 102, 218), Color.rgb(83, 151, 197))
                ).apply {
                    cornerRadius = dp(17).toFloat()
                    setStroke(dp(1), Color.argb(70, 255, 255, 255))
                }
            }
            setOnClickListener { action() }
        }
        button.addView(labelText(symbol, 18f, Color.WHITE, true), LinearLayout.LayoutParams(dp(28), -2))
        button.addView(labelText(text, 14f, Color.WHITE, true))
        return button
    }

    private fun labelText(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = true
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
    }

    private fun roundedBackground(color: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}

private class AuroraBackgroundView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(8, 11, 20))
        val w = width.toFloat()
        val h = height.toFloat()
        drawGlow(canvas, w * 0.12f, h * 0.16f, w * 0.78f, 0x554E69D8)
        drawGlow(canvas, w * 0.91f, h * 0.45f, w * 0.68f, 0x454B9EA8)
        drawGlow(canvas, w * 0.42f, h * 0.93f, w * 0.82f, 0x403D5FAE)
        paint.shader = null
        paint.color = Color.argb(18, 214, 226, 255)
        paint.strokeWidth = dp(1).toFloat()
        val spacing = dp(34).toFloat()
        var x = 0f
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, paint)
            x += spacing
        }
        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, paint)
            y += spacing
        }
    }

    private fun drawGlow(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(
            x, y, radius,
            intArrayOf(color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}