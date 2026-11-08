package com.mayt3n.glasschat

import android.app.Activity
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
import android.os.BatteryManager
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
import android.widget.TextClock
import android.widget.Toast
import com.mayt3n.glasschat.auth.ChatGPTAuth
import com.mayt3n.glasschat.auth.ChatGPTSession
import com.mayt3n.glasschat.chat.ConversationStore
import com.mayt3n.glasschat.chat.StoredChatMessage
import com.mayt3n.glasschat.chat.StoredConversation
import com.mayt3n.glasschat.network.ModelOption
import com.mayt3n.glasschat.network.PromptMessage
import com.mayt3n.glasschat.network.ResponsesClient
import java.util.UUID
import java.util.concurrent.Executors

private data class UiMessage(val role: String, var text: String)
private data class UiChat(val id: String, var title: String, var updated: Long, val messages: MutableList<UiMessage>)

class MainActivity : Activity() {
    private lateinit var auth: ChatGPTAuth
    private lateinit var chatStore: ConversationStore
    private lateinit var root: FrameLayout
    private lateinit var islandText: TextView
    private lateinit var modelButton: TextView
    private lateinit var composer: EditText
    private lateinit var sendButton: TextView
    private lateinit var messagesColumn: LinearLayout
    private lateinit var messagesScroll: ScrollView

    private var account: ChatGPTSession? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val chats = mutableListOf<UiChat>()
    private var activeChatId: String? = null
    private var models: List<ModelOption> = emptyList()
    private var selectedModel = ""
    private var modelName = "مدل"
    private var signingIn = false
    private var sending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(8, 11, 20)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        auth = ChatGPTAuth(this)
        chatStore = ConversationStore(this)
        chats.addAll(chatStore.read().map { item ->
            UiChat(item.id, item.title, item.updatedAt, item.messages.map {
                UiMessage(it.role, it.text)
            }.toMutableList())
        })
        account = auth.savedSession()
        when {
            account == null -> showLogin()
            account?.canUsePlan() != true -> showPlanGate(account!!)
            else -> showChatList()
        }
    }

    private fun makeRoot(): FrameLayout {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(8, 11, 20))
            clipChildren = false
            clipToPadding = false
        }
        frame.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))

        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }
        frame.addView(clock, FrameLayout.LayoutParams(dp(54), dp(24), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(9); leftMargin = dp(23)
        })
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val rightStatus = label("▂ ▄ ▆   " + (if (batteryLevel in 1..100) batteryLevel.toString() + "%" else "--%"), 10.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        frame.addView(rightStatus, FrameLayout.LayoutParams(dp(94), dp(24), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(9); rightMargin = dp(20)
        })

        val island = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(13), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(2, 3, 8))
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), Color.argb(75, 255, 255, 255))
            }
            elevation = dp(14).toFloat()
        }
        val led = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(99, 226, 170))
            }
        }
        island.addView(led, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
        islandText = label("GLASSCHAT", 9f, Color.rgb(231, 237, 250), true).apply { letterSpacing = .15f }
        island.addView(islandText)
        island.setOnClickListener {
            islandText.text = if (islandText.text == "GLASSCHAT") "اتصال امن • فعال" else "GLASSCHAT"
            island.animate().scaleX(1.05f).scaleY(1.05f).setDuration(130).withEndAction {
                island.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
            }.start()
        }
        frame.addView(island, FrameLayout.LayoutParams(dp(142), dp(32), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(5)
        })
        root = frame
        return frame
    }

    private fun islandState(value: String) {
        if (::islandText.isInitialized) islandText.text = value.uppercase()
    }

    private fun showLogin(error: String = "", busy: Boolean = false) {
        val frame = makeRoot()
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(25), dp(43), dp(25), dp(28))
        }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(42) })
        body.addView(orb(dp(78)), LinearLayout.LayoutParams(dp(78), dp(78)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(17)
        })
        body.addView(label("GLASSCHAT", 11f, Color.rgb(178, 202, 255), true).apply {
            gravity = Gravity.CENTER; letterSpacing = .22f
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        body.addView(label("گفت‌وگو، با حس آیفون", 27f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        body.addView(label("یک فضای شخصی، روان و شفاف برای فکر کردن با ChatGPT.", 13f, Color.rgb(174, 188, 217)).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(10); rightMargin = dp(10); bottomMargin = dp(25)
        })

        val card = glass(29).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(21), dp(22), dp(21), dp(21))
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2))
        card.addView(label("ورود به حساب", 18f, Color.WHITE, true))
        card.addView(label(
            "با حساب ChatGPT خودت وارد شو. اگر حسابت را با گوگل ساخته‌ای، گزینه Google را در صفحه رسمی ورود انتخاب کن.",
            12.5f, Color.rgb(188, 200, 224)
        ).apply { setLineSpacing(dp(4).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8); bottomMargin = dp(18)
        })
        card.addView(primary(if (busy) "در حال اتصال..." else "Continue with ChatGPT", if (busy) "◌" else "✦") {
            if (!busy) beginSignIn()
        }, LinearLayout.LayoutParams(-1, dp(56)))
        val safe = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.argb(28, 136, 169, 255), 17, Color.argb(48, 191, 209, 255))
        }
        safe.addView(label("◈", 18f, Color.rgb(169, 198, 255), true), LinearLayout.LayoutParams(dp(30), -2))
        safe.addView(label("رمز گوگل و ChatGPT داخل GlassChat وارد نمی‌شود؛ ورود در مرورگر امن انجام می‌شود.", 11f, Color.rgb(190, 202, 225)), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(safe, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        if (error.isNotBlank()) {
            card.addView(label(error, 11.5f, Color.rgb(255, 206, 162)).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        body.addView(label("GlassChat مستقل است؛ تاریخچه آن از تاریخچه اپ رسمی جداست.", 10.5f, Color.rgb(133, 149, 180)).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        setContentView(frame)
        animateIn(frame)
        islandState(if (busy) "SECURE SIGN IN" else "WELCOME")
    }

    private fun beginSignIn() {
        if (signingIn) return
        signingIn = true
        showLogin("در حال بررسی اتصال و آماده‌سازی ورود امن...", true)
        executor.execute {
            try {
                val connected = auth.signIn()
                account = connected
                runOnUiThread {
                    signingIn = false
                    if (connected.canUsePlan()) {
                        showChatList()
                        fetchModels()
                    } else showPlanGate(connected)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    signingIn = false
                    showLogin(e.message ?: "ورود انجام نشد.")
                    islandState("NETWORK ERROR")
                }
            }
        }
    }

    private fun showPlanGate(user: ChatGPTSession) {
        val frame = makeRoot()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        val card = glass(28).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(23), dp(22), dp(22)) }
        page.addView(orb(dp(65)), LinearLayout.LayoutParams(dp(65), dp(65)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16)
        })
        page.addView(card, LinearLayout.LayoutParams(-1, -2))
        card.addView(label("حساب متصل شد", 22f, Color.WHITE, true))
        card.addView(label(user.email, 11.5f, Color.rgb(164, 192, 255)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        card.addView(label("اما مجوز استفاده از سهمیه پلن برای این کلاینت صادر نشده است. ورود هویت و اجازه استفاده از پلن دو تأیید جدا هستند.", 13f, Color.rgb(188, 201, 225)).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(15); bottomMargin = dp(18) })
        card.addView(primary("باز کردن ChatGPT", "↗") { openExternal("https://chatgpt.com/") }, LinearLayout.LayoutParams(-1, dp(53)))
        card.addView(secondary("خروج از حساب", "↪") { signOut() }, LinearLayout.LayoutParams(-1, dp(49)).apply { topMargin = dp(9) })
        frame.addView(page, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame); animateIn(frame); islandState("ACCOUNT LINKED")
    }

    private fun showChatList() {
        if (account == null) { showLogin(); return }
        val frame = makeRoot()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(53), dp(19), dp(8))
        }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(83) })

        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleGroup.addView(label("گفت‌وگوها", 28f, Color.WHITE, true))
        titleGroup.addView(label("فضای شخصی تو با ChatGPT", 10.5f, Color.rgb(153, 171, 206)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        heading.addView(titleGroup, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(iconButton("＋", 23f) { newChat() }, LinearLayout.LayoutParams(dp(43), dp(43)).apply { marginEnd = dp(8) })
        heading.addView(iconButton("●", 13f) { showSettings() }, LinearLayout.LayoutParams(dp(43), dp(43)))
        page.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        val searchBox = glass(18).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
        searchBox.addView(label("⌕", 23f, Color.rgb(149, 172, 214)), LinearLayout.LayoutParams(dp(28), -2))
        val search = EditText(this).apply {
            hint = "جست‌وجوی گفت‌وگوها"; setHintTextColor(Color.rgb(132, 150, 183))
            setTextColor(Color.WHITE); textSize = 13f; background = null; maxLines = 1; minLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH; setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        searchBox.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(searchBox, LinearLayout.LayoutParams(-1, dp(47)).apply { bottomMargin = dp(21) })

        val section = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        section.addView(label("اخیراً", 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        section.addView(label("${chats.size} گفت‌وگو", 10f, Color.rgb(134, 154, 190)))
        page.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })

        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; addView(rows, FrameLayout.LayoutParams(-1, -2)) }
        page.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))

        fun populate(query: String) {
            rows.removeAllViews()
            val filtered = chats.sortedByDescending { it.updated }
                .filter { it.title.contains(query, true) || it.messages.lastOrNull()?.text?.contains(query, true) == true }
            if (filtered.isEmpty()) {
                val empty = glass(25).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(20), dp(27), dp(20), dp(27))
                }
                empty.addView(orb(dp(48)), LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
                empty.addView(label(if (query.isBlank()) "هنوز گفت‌وگویی نداری" else "نتیجه‌ای پیدا نشد", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
                empty.addView(label("اولین گفت‌وگو را بساز و شروع کن.", 11f, Color.rgb(157, 176, 210)).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                rows.addView(empty, LinearLayout.LayoutParams(-1, -2))
            } else filtered.forEach { chat ->
                rows.addView(chatRow(chat) { activeChatId = chat.id; showChat() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
            }
        }
        populate("")
        search.addTextChangedListener(SimpleWatcher { populate(it) })
        page.addView(label(account?.email ?: "حساب ChatGPT متصل است", 9.5f, Color.rgb(116, 138, 176)).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addDock(frame, "chats")
        setContentView(frame); animateIn(frame); islandState("CHATGPT CONNECTED")
    }

    private fun chatRow(chat: UiChat, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = rounded(Color.argb(24, 255, 255, 255), 19, Color.argb(25, 255, 255, 255))
            isClickable = true; setOnClickListener { action() }
        }
        row.addView(orb(dp(47)), LinearLayout.LayoutParams(dp(47), dp(47)).apply { marginEnd = dp(12) })
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        center.addView(label(chat.title, 13.5f, Color.WHITE, true))
        center.addView(label(chat.messages.lastOrNull()?.text?.take(65) ?: "برای شروع لمس کن", 10.5f, Color.rgb(151, 168, 198)).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        row.addView(center, LinearLayout.LayoutParams(0, -2, 1f))
        val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        meta.addView(label(timeLabel(chat.updated), 9f, Color.rgb(130, 149, 184)))
        meta.addView(label("›", 20f, Color.rgb(128, 148, 187)).apply { gravity = Gravity.END })
        row.addView(meta, LinearLayout.LayoutParams(dp(44), -2))
        return row
    }

    private fun showChat() {
        val chat = activeChat() ?: run { newChat(); return }
        val frame = makeRoot()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(48), dp(13), dp(8)) }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(10))
        }
        header.addView(iconButton("‹", 27f) { showChatList() }, LinearLayout.LayoutParams(dp(39), dp(41)).apply { marginEnd = dp(7) })
        header.addView(orb(dp(41)), LinearLayout.LayoutParams(dp(41), dp(41)).apply { marginEnd = dp(9) })
        val name = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        name.addView(label("ChatGPT", 14.5f, Color.WHITE, true))
        name.addView(label("●  آنلاین", 9f, Color.rgb(108, 224, 172)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        header.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        modelButton = label(modelName, 9.5f, Color.rgb(208, 220, 255), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(Color.argb(36, 117, 148, 255), 24, Color.argb(62, 178, 200, 255))
            maxWidth = dp(112); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { showModels() }
        }
        header.addView(modelButton)
        page.addView(header, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(5) })

        messagesColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(3), dp(12), dp(3), dp(17)) }
        messagesScroll = ScrollView(this).apply {
            isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
            addView(messagesColumn, FrameLayout.LayoutParams(-1, -2))
        }
        page.addView(messagesScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val input = glass(25).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(7), dp(7))
        }
        composer = EditText(this).apply {
            hint = "پیام..."; setHintTextColor(Color.rgb(134, 151, 182)); setTextColor(Color.WHITE)
            textSize = 14f; background = null; minLines = 1; maxLines = 5
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(dp(5), dp(8), dp(5), dp(8))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        input.addView(composer, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = label("↑", 23f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(116, 151, 255), Color.rgb(95, 104, 222), Color.rgb(69, 166, 189))).apply {
                cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(65, 255, 255, 255))
            }
            setOnClickListener { send() }
        }
        input.addView(sendButton, LinearLayout.LayoutParams(dp(46), dp(44)).apply { marginStart = dp(7) })
        page.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        page.addView(label("پاسخ با مجوز استفاده از پلن ChatGPT", 9f, Color.rgb(123, 141, 174)).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

        setContentView(frame); drawMessages()
        if (models.isEmpty()) fetchModels()
        animateIn(frame); islandState(if (sending) "THINKING" else "READY")
    }

    private fun drawMessages(): TextView {
        messagesColumn.removeAllViews()
        val chat = activeChat() ?: return label("", 1f, Color.TRANSPARENT)
        if (chat.messages.isEmpty()) {
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(16), dp(38), dp(16), dp(30))
            }
            welcome.addView(orb(dp(55)), LinearLayout.LayoutParams(dp(55), dp(55)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(13) })
            welcome.addView(label("سلام، از کجا شروع کنیم؟", 20f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            })
            welcome.addView(label("یک سؤال، یک ایده یا یک مسئله پیچیده را باز کن.", 11.5f, Color.rgb(160, 178, 210)).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            listOf("یک ایده تازه بده", "این موضوع را توضیح بده", "در برنامه‌نویسی کمکم کن").forEach { prompt ->
                welcome.addView(label(prompt, 10.5f, Color.rgb(207, 219, 246), true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(14), dp(9), dp(14), dp(9))
                    background = rounded(Color.argb(31, 137, 171, 255), 22, Color.argb(49, 191, 211, 255))
                    setOnClickListener { composer.setText(prompt); send() }
                }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
            }
            messagesColumn.addView(welcome, LinearLayout.LayoutParams(-1, -1))
            return label("", 1f, Color.TRANSPARENT)
        }

        var answerView: TextView? = null
        chat.messages.forEach { message ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = if (message.role == "user") Gravity.END else Gravity.START
                setPadding(dp(2), dp(4), dp(2), dp(4))
            }
            if (message.role == "assistant") {
                line.addView(orb(dp(26)), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(7); topMargin = dp(1) })
            }
            val bubble = TextView(this).apply {
                text = message.text.ifBlank { "•••" }; textSize = 13.5f
                setTextColor(if (message.role == "user") Color.WHITE else Color.rgb(234, 239, 250))
                setLineSpacing(dp(4).toFloat(), 1f); textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                setPadding(dp(14), dp(11), dp(14), dp(11))
                maxWidth = (resources.displayMetrics.widthPixels * .79f).toInt()
                background = if (message.role == "user") GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(92, 121, 218), Color.rgb(79, 101, 188))
                ).apply { cornerRadius = dp(19).toFloat(); setStroke(dp(1), Color.argb(65, 190, 206, 255)) } else glassBubble()
            }
            line.addView(bubble)
            messagesColumn.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            if (message.role == "assistant") answerView = bubble
        }
        scrollBottom()
        return answerView ?: label("", 1f, Color.TRANSPARENT)
    }

    private fun send() {
        if (sending || !::composer.isInitialized) return
        val prompt = composer.text.toString().trim()
        if (prompt.isBlank()) return
        if (selectedModel.isBlank()) {
            Toast.makeText(this, "مدل هنوز آماده نیست.", Toast.LENGTH_SHORT).show()
            fetchModels(); return
        }
        val session = account
        val chat = activeChat()
        if (session == null || !session.canUsePlan() || chat == null) {
            showLogin("اتصال ChatGPT نیاز به ورود دوباره دارد."); return
        }
        composer.setText("")
        chat.messages.add(UiMessage("user", prompt))
        if (chat.title == "گفت‌وگوی جدید") chat.title = prompt.take(34)
        chat.messages.add(UiMessage("assistant", ""))
        chat.updated = System.currentTimeMillis()
        persist()
        val answer = chat.messages.last()
        val answerView = drawMessages()
        val history = chat.messages.filter { it.text.isNotBlank() }.map { PromptMessage(it.role, it.text) }
        val chosen = selectedModel
        sending = true; sendButton.alpha = .55f; islandState("THINKING")

        executor.execute {
            try {
                val fresh = auth.validSessionOrRefresh(session)
                account = fresh
                ResponsesClient.streamReply(fresh, chosen, history) { delta ->
                    answer.text += delta
                    runOnUiThread { answerView.text = answer.text; scrollBottom() }
                }
                runOnUiThread {
                    chat.updated = System.currentTimeMillis(); persist()
                    sending = false; if (::sendButton.isInitialized) sendButton.alpha = 1f
                    islandState("READY")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    answer.text = e.message ?: "ارتباط با مدل قطع شد."
                    answerView.text = answer.text
                    chat.updated = System.currentTimeMillis(); persist()
                    sending = false; if (::sendButton.isInitialized) sendButton.alpha = 1f
                    islandState("CONNECTION ISSUE")
                }
            }
        }
    }

    private fun fetchModels() {
        val current = account ?: return
        modelName = "در حال دریافت"
        if (::modelButton.isInitialized) modelButton.text = modelName
        executor.execute {
            try {
                val fresh = auth.validSessionOrRefresh(current)
                account = fresh
                val available = ResponsesClient.listModels(fresh)
                if (available.isEmpty()) throw IllegalStateException("مدلی برای این حساب در دسترس نیست.")
                models = available
                if (selectedModel.isBlank() || available.none { it.slug == selectedModel }) selectedModel = available.first().slug
                modelName = available.firstOrNull { it.slug == selectedModel }?.displayName ?: available.first().displayName
                runOnUiThread { if (::modelButton.isInitialized) modelButton.text = modelName }
            } catch (e: Exception) {
                modelName = "مدل نامشخص"
                runOnUiThread {
                    if (::modelButton.isInitialized) modelButton.text = modelName
                    Toast.makeText(this, e.message ?: "دریافت مدل‌ها ناموفق بود", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showModels() {
        if (models.isEmpty()) { fetchModels(); return }
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.argb(140, 0, 0, 0))
        overlay.setOnClickListener { root.removeView(overlay) }
        val sheet = glass(27).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(18), dp(17), dp(16))
            isClickable = true; setOnClickListener { }
        }
        sheet.addView(label("انتخاب مدل", 17f, Color.WHITE, true))
        sheet.addView(label("مدل‌های مجاز برای حساب تو", 10f, Color.rgb(155, 174, 208)), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4); bottomMargin = dp(11)
        })
        models.take(12).forEach { model ->
            sheet.addView(label((if (model.slug == selectedModel) "✓   " else "     ") + model.displayName, 11.5f, Color.WHITE, model.slug == selectedModel).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(if (model.slug == selectedModel) Color.argb(53, 105, 141, 245) else Color.argb(21, 255, 255, 255), 14, Color.argb(35, 255, 255, 255))
                setOnClickListener {
                    selectedModel = model.slug; modelName = model.displayName
                    modelButton.text = modelName; root.removeView(overlay)
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        overlay.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(13), 0, dp(13), dp(14)) })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        sheet.translationY = dp(30).toFloat(); sheet.animate().translationY(0f).setDuration(220).start()
    }

    private fun showSettings() {
        val user = account ?: run { showLogin(); return }
        val frame = makeRoot()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(19), dp(54), dp(19), dp(10)) }
        frame.addView(page, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(83) })
        page.addView(label("تنظیمات", 28f, Color.WHITE, true))
        page.addView(label("حساب و تجربه GlassChat", 10.5f, Color.rgb(153, 171, 206)), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4); bottomMargin = dp(18)
        })
        val profile = glass(23).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(15), dp(14), dp(15))
        }
        profile.addView(orb(dp(47)), LinearLayout.LayoutParams(dp(47), dp(47)).apply { marginEnd = dp(11) })
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(label(user.name.ifBlank { "حساب ChatGPT" }, 13f, Color.WHITE, true))
        details.addView(label(user.email, 10f, Color.rgb(157, 179, 215)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        profile.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        profile.addView(label("●", 10f, Color.rgb(103, 220, 172)))
        page.addView(profile, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        page.addView(settingRow("◇", "حساب ChatGPT", "مدیریت دسترسی و Usage") { openExternal("https://chatgpt.com/") }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        page.addView(settingRow("◐", "ظاهر", "Liquid Glass تیره") { Toast.makeText(this, "تم روشن در نسخه بعدی اضافه می‌شود.", Toast.LENGTH_SHORT).show() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        page.addView(settingRow("⌑", "حریم خصوصی", "گفت‌وگوها روی دستگاه رمزنگاری می‌شوند") { Toast.makeText(this, "ذخیره محلی با Android Keystore رمزنگاری می‌شود.", Toast.LENGTH_LONG).show() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        page.addView(secondary("خروج از حساب", "↪") { signOut() }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(15) })
        addDock(frame, "settings")
        setContentView(frame); animateIn(frame); islandState("SETTINGS")
    }

    private fun settingRow(symbol: String, title: String, detail: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(Color.argb(25, 255, 255, 255), 17, Color.argb(34, 255, 255, 255))
            setOnClickListener { action() }
        }
        row.addView(label(symbol, 18f, Color.rgb(175, 197, 255), true), LinearLayout.LayoutParams(dp(31), -2))
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(label(title, 12f, Color.WHITE, true))
        labels.addView(label(detail, 9.5f, Color.rgb(148, 166, 199)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 20f, Color.rgb(129, 151, 188)))
        return row
    }

    private fun addDock(frame: FrameLayout, selected: String) {
        val dock = glass(23).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(7), dp(6), dp(7), dp(6)) }
        dock.addView(tab("◫", "گفت‌وگوها", selected == "chats") { showChatList() }, LinearLayout.LayoutParams(0, dp(53), 1f))
        dock.addView(tab("⚙", "تنظیمات", selected == "settings") { showSettings() }, LinearLayout.LayoutParams(0, dp(53), 1f))
        frame.addView(dock, FrameLayout.LayoutParams(-1, dp(66), Gravity.BOTTOM).apply {
            setMargins(dp(15), 0, dp(15), dp(10))
        })
    }

    private fun tab(symbol: String, title: String, active: Boolean, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { action() }
            addView(label(symbol, 19f, if (active) Color.rgb(180, 202, 255) else Color.rgb(130, 150, 185), true).apply { gravity = Gravity.CENTER })
            addView(label(title, 9f, if (active) Color.WHITE else Color.rgb(144, 162, 191), active).apply { gravity = Gravity.CENTER })
        }

    private fun newChat() {
        val chat = UiChat(UUID.randomUUID().toString(), "گفت‌وگوی جدید", System.currentTimeMillis(), mutableListOf())
        chats.add(0, chat); activeChatId = chat.id; persist(); showChat()
    }

    private fun activeChat(): UiChat? = chats.firstOrNull { it.id == activeChatId }

    private fun persist() {
        chatStore.write(chats.map { chat ->
            StoredConversation(chat.id, chat.title, chat.updated, chat.messages.map {
                StoredChatMessage(it.role, it.text)
            }.toMutableList())
        })
    }

    private fun signOut() {
        val old = account
        executor.execute {
            auth.signOut(old); account = null
            runOnUiThread { activeChatId = null; showLogin() }
        }
    }

    private fun orb(size: Int): View =
        TextView(this).apply {
            text = "✦"; textSize = if (size > dp(35)) 20f else 12f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(116, 154, 255), Color.rgb(104, 102, 219), Color.rgb(62, 174, 183))
            ).apply { shape = GradientDrawable.OVAL; setStroke(dp(1), Color.argb(80, 255, 255, 255)) }
            elevation = dp(5).toFloat()
        }

    private fun iconButton(symbol: String, size: Float, action: () -> Unit): TextView =
        label(symbol, size, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(40, 255, 255, 255), 30, Color.argb(43, 255, 255, 255))
            setOnClickListener { action() }
        }

    private fun primary(title: String, symbol: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(108, 141, 247), Color.rgb(105, 104, 222), Color.rgb(73, 163, 192))
            ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.argb(80, 255, 255, 255)) }
            elevation = dp(8).toFloat()
            addView(label(symbol, 18f, Color.WHITE, true), LinearLayout.LayoutParams(dp(28), -2))
            addView(label(title, 13f, Color.WHITE, true))
            setOnClickListener { action() }
        }

    private fun secondary(title: String, symbol: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; isClickable = true
            background = rounded(Color.argb(29, 255, 255, 255), 17, Color.argb(53, 255, 255, 255))
            addView(label(symbol, 17f, Color.rgb(205, 218, 247), true), LinearLayout.LayoutParams(dp(28), -2))
            addView(label(title, 12f, Color.rgb(228, 235, 249), true))
            setOnClickListener { action() }
        }

    private fun glass(radius: Int): LinearLayout =
        LinearLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(69, 255, 255, 255), Color.argb(29, 151, 179, 247), Color.argb(17, 255, 255, 255))
            ).apply { cornerRadius = dp(radius).toFloat(); setStroke(dp(1), Color.argb(61, 227, 237, 255)) }
            elevation = dp(9).toFloat()
        }

    private fun glassBubble(): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(29, 144, 175, 244), Color.argb(19, 255, 255, 255))
        ).apply { cornerRadius = dp(19).toFloat(); setStroke(dp(1), Color.argb(54, 233, 241, 255)) }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke) }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = true; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }

    private fun timeLabel(timestamp: Long): String {
        val elapsed = System.currentTimeMillis() - timestamp
        return when {
            elapsed < 60_000L -> "اکنون"
            elapsed < 3_600_000L -> "${elapsed / 60_000L} دقیقه"
            elapsed < 86_400_000L -> "${elapsed / 3_600_000L} ساعت"
            else -> android.text.format.DateFormat.format("MM/dd", timestamp).toString()
        }
    }

    private fun scrollBottom() {
        if (::messagesScroll.isInitialized) messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "مرورگر پیدا نشد.", Toast.LENGTH_SHORT).show() }
    }

    private fun animateIn(view: View) {
        view.alpha = 0f; view.translationY = dp(10).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(230).start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (account != null && activeChatId != null) { activeChatId = null; showChatList() }
        else if (account != null) showChatList()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

private class SimpleWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}

private class AuroraView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(8, 11, 20))
        val w = width.toFloat(); val h = height.toFloat()
        glow(canvas, w * .12f, h * .13f, w * .8f, 0x554A66D6)
        glow(canvas, w * .94f, h * .45f, w * .68f, 0x45418F9E)
        glow(canvas, w * .34f, h * .95f, w * .83f, 0x403C5BA8)
    }
    private fun glow(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(x, y, radius,
            intArrayOf(color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, paint); paint.shader = null
    }
}