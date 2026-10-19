package tw.personal.privateinbox

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Hand-built 2014–2016 Material One visual language, without tracking/UI libraries. */
class MainActivity : ComponentActivity() {
    private companion object {
        const val AUTH_REQUEST = 101
        const val BLUE = 0xff5682A3.toInt()
        const val BLUE_DARK = 0xff497799.toInt()
        const val BADGE = 0xff4D8BC4.toInt()
        const val INK = 0xff212121.toInt()
        const val SECONDARY = 0xff757575.toInt()
        const val BG = 0xffE7EBF0.toInt()
        const val LINE = 0xffE0E0E0.toInt()
    }

    private var unlocked = false
    private var page = "locked"
    private lateinit var main: FrameLayout
    private var entries: List<InboxDatabase.Entry> = emptyList()
    private var currentThread: String? = null
    private var currentTitle: String? = null
    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private val colors = intArrayOf(0xff5aa8a1.toInt(), 0xffc28d64.toInt(), 0xff68a4c9.toInt(),
        0xffbc799e.toInt(), 0xff9183bc.toInt(), 0xff78a676.toInt())

    private fun Int.dp() = (this * resources.displayMetrics.density + .5f).toInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024L -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> String.format(java.util.Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
    }
    private fun rounded(color: Int, radius: Int = 8, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.dp().toFloat()
            if (border != null) setStroke(1.dp(), border)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FLAG_SECURE blocks screenshots and screen recording. This protection is
        // unconditional: it is never relaxed, in any build type.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = BLUE_DARK
        window.navigationBarColor = 0xff172635.toInt()
        window.decorView.systemUiVisibility = 0
        main = FrameLayout(this)
        setContentView(main)
        main.setOnApplyWindowInsetsListener { view, insets ->
            // targetSdk 36 draws edge-to-edge on modern Android; keep controls visible.
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        refreshBackState()
        showLock()
    }

    override fun onResume() {
        super.onResume()
        if (!unlocked) showLock()
    }

    override fun onStop() {
        super.onStop()
        unlocked = false
        entries = emptyList()
        currentThread = null
        currentTitle = null
        showLock()
    }

    @Deprecated("Credential confirmation activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AUTH_REQUEST && resultCode == RESULT_OK) {
            unlocked = true
            showInbox(true)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (page) {
                "photo" -> showChat()
                "chat", "settings", "search" -> showInbox(false)
                else -> finish()
            }
            isEnabled = false
        }
    }

    private fun refreshBackState() {
        backCallback.isEnabled = unlocked && page in listOf("photo", "chat", "settings", "search")
    }

    private fun txt(value: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            includeFontPadding = true
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun toolbar(title: String, onBack: () -> Unit, search: Boolean = false): LinearLayout {
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BLUE)
            elevation = 3.dp().toFloat()
        }
        val back = txt(if (page == "inbox") "☰" else "‹", 31f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onBack() }
        }
        bar.addView(back, LinearLayout.LayoutParams(52.dp(), 56.dp()))
        val label = txt(title, 20f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(label, LinearLayout.LayoutParams(0, 56.dp(), 1f))
        if (search) {
            val action = txt("⌕", 32f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                contentDescription = "搜尋通知"
                setOnClickListener { showSearch() }
            }
            bar.addView(action, LinearLayout.LayoutParams(54.dp(), 56.dp()))
        }
        return bar
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
    }
    private fun show(body: View) {
        main.removeAllViews()
        main.addView(body, FrameLayout.LayoutParams(-1, -1))
        refreshBackState()
    }
    private fun scroll(view: LinearLayout, background: Int = Color.WHITE): ScrollView =
        ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(background)
            addView(view)
        }
    private fun divider(): View = View(this).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(-1, 1.dp())
    }

    private fun showLock() {
        page = "locked"
        val layout = column().apply { gravity = Gravity.CENTER; setPadding(30.dp(), 15.dp(), 30.dp(), 15.dp()) }
        val logo = txt("✉", 42f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = rounded(BLUE, 48)
        }
        layout.addView(logo, LinearLayout.LayoutParams(94.dp(), 94.dp()))
        layout.addView(txt("PRIVATE INBOX", 24f, INK, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 28.dp(), 0, 9.dp())
        })
        layout.addView(txt("Classic · 僅儲存在這部手機", 14f, SECONDARY).apply { gravity = Gravity.CENTER })
        val button = Button(this).apply {
            text = "使用手機螢幕鎖解鎖"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(BLUE)
            setOnClickListener { authenticate() }
        }
        layout.addView(button, LinearLayout.LayoutParams(-1, 56.dp()).apply { topMargin = 40.dp() })
        layout.addView(txt("無網路權限 · 沒有廣告 · 不會開啟原始聊天室", 12f, SECONDARY).apply {
            gravity = Gravity.CENTER
            setPadding(0, 26.dp(), 0, 0)
        })
        show(layout)
    }

    @Suppress("DEPRECATION")
    private fun authenticate() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isDeviceSecure) {
            AlertDialog.Builder(this).setTitle("需要螢幕鎖")
                .setMessage("請在 Android 設定建立 PIN、圖形鎖或密碼，才能開啟私人通知紀錄。")
                .setPositiveButton("了解", null).show()
            return
        }
        val intent = keyguard.createConfirmDeviceCredentialIntent("開啟私人通知", "請驗證裝置身分")
        if (intent != null) startActivityForResult(intent, AUTH_REQUEST)
    }

    private fun loadEntries() {
        // DB read + AES-GCM decryption off the main thread; re-render when ready.
        io.execute {
            val db = InboxDatabase(this)
            var loaded: List<InboxDatabase.Entry>
            try {
                loaded = db.recent(1500)
                val days = LocalSettings.retentionDays(this)
                if (days > 0) runCatching { db.pruneOlderThanDays(days) }
            } catch (_: Exception) {
                loaded = emptyList()
            } finally { db.close() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                entries = loaded
                when (page) {
                    "inbox" -> showInbox(false)
                    "chat" -> showChat()
                    else -> Unit
                }
            }
        }
    }

    /** Stable conversation identity: source package + the conversation title the
     *  notification reported. Two different contacts that share a display name in
     *  the same app are indistinguishable at the notification API level, so they
     *  intentionally share a thread; the source package always separates apps. */
    private fun threadKey(e: InboxDatabase.Entry): String = "${e.packageName}\u0000${e.title}"

    private fun threads(list: List<InboxDatabase.Entry>) = list.groupBy { threadKey(it) }
        .entries.sortedByDescending { it.value.maxOf { e -> e.receivedAt } }

    private fun avatar(name: String, size: Int = 54): TextView {
        val initials = name.trim().take(2).ifEmpty { "?" }
        val color = colors[(name.hashCode().toLong() and 0x7fffffffL).rem(colors.size).toInt()]
        return txt(initials, 19f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(color, size / 2)
        }
    }

    private fun time(epoch: Long, withDate: Boolean = false): String =
        DateFormat.format(if (withDate) "MM/dd HH:mm" else "HH:mm", Date(epoch)).toString()

    /** Show the source's original time when the notification provided one; otherwise the capture time. */
    private fun entryTime(entry: InboxDatabase.Entry, withDate: Boolean = false): String =
        time(if (entry.sourceTimestamp > 0) entry.sourceTimestamp else entry.receivedAt, withDate)

    /** Calendar-day label used for the in-conversation date separators. */
    private fun dayLabel(entry: InboxDatabase.Entry): String {
        val stamp = if (entry.sourceTimestamp > 0) entry.sourceTimestamp else entry.receivedAt
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = stamp }
        val today = java.util.Calendar.getInstance()
        val sameYear = cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
        val dayDiff = today.get(java.util.Calendar.DAY_OF_YEAR) - cal.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "今天"
            sameYear && dayDiff == 1 -> "昨天"
            else -> DateFormat.format("yyyy/MM/dd", Date(stamp)).toString()
        }
    }

    private fun threadRow(group: List<InboxDatabase.Entry>, click: () -> Unit): View {
        val last = group.maxByOrNull { it.receivedAt } ?: group.first()
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 9.dp(), 14.dp(), 9.dp())
            setBackgroundColor(Color.WHITE)
            setOnClickListener { click() }
        }
        row.addView(avatar(last.title), LinearLayout.LayoutParams(54.dp(), 54.dp()))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 0, 4.dp(), 0)
        }
        details.addView(txt(last.title, 16f, INK, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val preview = (if (last.sender.isNotEmpty()) "${last.sender}: " else "") +
            (if (last.hasImage) "📷 " else "") + last.body
        details.addView(txt(preview, 14f, SECONDARY).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 3.dp(), 0, 0)
        })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        val meta = LinearLayout(this).apply { gravity = Gravity.END; orientation = LinearLayout.VERTICAL }
        meta.addView(txt(entryTime(last), 11f, SECONDARY).apply { gravity = Gravity.RIGHT })
        if (group.size > 1) {
            // Badge counts messages captured since the user last opened this thread.
            // It is NOT the source app's real unread counter, which we cannot observe.
            val seenAt = LocalSettings.lastSeen(this, threadKey(last))
            val unread = group.count { it.receivedAt > seenAt }
            if (unread > 0) {
                val badge = txt(unread.coerceAtMost(999).toString(), 11f, Color.WHITE, true).apply {
                    gravity = Gravity.CENTER; background = rounded(BADGE, 18)
                    contentDescription = "自上次查看後擷取 $unread 則"
                }
                meta.addView(badge, LinearLayout.LayoutParams(32.dp(), 22.dp()).apply {
                    topMargin = 8.dp(); gravity = Gravity.RIGHT
                })
            }
        }
        row.addView(meta, LinearLayout.LayoutParams(-2, -2))
        return row
    }

    private fun showInbox(reload: Boolean) {
        if (!unlocked) return
        page = "inbox"
        if (reload) loadEntries()
        val root = column()
        root.addView(toolbar("Private Inbox", { showMenu() }, true))
        val data = column()
        if (reload && entries.isEmpty()) {
            data.addView(txt("正在載入已保存的通知…", 16f, SECONDARY).apply {
                gravity = Gravity.CENTER
                setPadding(12.dp(), 82.dp(), 12.dp(), 16.dp())
            })
        } else if (entries.isEmpty()) {
            data.addView(txt("目前沒有已保存的通知", 18f, INK, true).apply {
                gravity = Gravity.CENTER
                setPadding(12.dp(), 82.dp(), 12.dp(), 16.dp())
            })
            data.addView(txt("點右下角設定，開啟來源及照片擷取，再到 Android 授予通知存取權限。", 14f, SECONDARY).apply {
                gravity = Gravity.CENTER; setPadding(22.dp(), 0, 22.dp(), 0)
            })
        } else {
            for (thread in threads(entries)) {
                val first = thread.value.first()
                data.addView(threadRow(thread.value) {
                    currentThread = thread.key
                    currentTitle = first.title
                    showChat()
                }, LinearLayout.LayoutParams(-1, 76.dp()))
                data.addView(divider())
            }
        }
        val content = FrameLayout(this)
        content.addView(scroll(data), FrameLayout.LayoutParams(-1, -1))
        val fab = txt("⚙", 25f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = rounded(BLUE, 48)
            elevation = 5.dp().toFloat()
            contentDescription = "來源與隱私設定"
            setOnClickListener { showSettings() }
        }
        content.addView(fab, FrameLayout.LayoutParams(56.dp(), 56.dp(), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = 18.dp(); bottomMargin = 20.dp()
        })
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        show(root)
    }

    private fun showMenu() {
        AlertDialog.Builder(this).setItems(arrayOf("來源與隱私設定", "重新整理", "鎖定")) { _, index ->
            when (index) {
                0 -> showSettings()
                1 -> showInbox(true)
                2 -> { unlocked = false; entries = emptyList(); showLock() }
            }
        }.show()
    }

    private fun showChat() {
        if (!unlocked) return
        val key = currentThread ?: return showInbox(false)
        page = "chat"
        val group = entries.filter { threadKey(it) == key }
            .sortedWith(compareBy<InboxDatabase.Entry> { it.receivedAt }.thenBy { it.id })
        // Opening the thread marks everything currently stored in it as seen, so the
        // chat list badge reflects newly captured messages only.
        group.maxByOrNull { it.receivedAt }?.let {
            LocalSettings.markSeen(this, key, it.receivedAt)
        }
        val root = column()
        root.addView(toolbar(currentTitle.orEmpty(), { showInbox(false) }))
        val container = column().apply {
            setBackgroundColor(BG)
            setPadding(9.dp(), 10.dp(), 9.dp(), 22.dp())
        }
        var lastDay = ""
        for (entry in group) {
            // Telegram-style date separator between different calendar days.
            val day = dayLabel(entry)
            if (day != lastDay) {
                lastDay = day
                container.addView(txt(day, 12f, SECONDARY).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 12.dp(), 0, 8.dp())
                })
            }
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 8, 0xffe2e8ec.toInt())
                setPadding(11.dp(), 8.dp(), 11.dp(), 7.dp())
            }
            if (entry.sender.isNotBlank()) holder.addView(txt(entry.sender, 13f, BLUE, true))
            if (entry.hasImage) {
                val photo = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(LINE, 4)
                    contentDescription = "開啟收到的照片預覽"
                    setOnClickListener { showPhoto(entry) }
                }
                holder.addView(photo, LinearLayout.LayoutParams(230.dp(), 170.dp()).apply {
                    bottomMargin = 6.dp()
                })
                // Decode the encrypted thumbnail off the UI thread.
                io.execute {
                    val db = InboxDatabase(this)
                    val bits = try { db.loadImage(entry.id) } catch (_: Exception) { null }
                    finally { db.close() }
                    val decoded = if (bits != null) ImageCapture.decodedForDisplay(bits, 480) else null
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        decoded?.let { photo.setImageBitmap(it) }
                    }
                }
            }
            if (entry.body.isNotBlank()) holder.addView(txt(entry.body, 15.5f, INK))
            val metaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            metaRow.addView(txt(entryTime(entry, true) + if (entry.sourceTimestamp > 0) "" else "（擷取）", 11f, SECONDARY).apply {
                setPadding(22.dp(), 4.dp(), 4.dp(), 0)
            })
            val del = txt("🗑", 13f, SECONDARY).apply {
                gravity = Gravity.CENTER
                contentDescription = "刪除這則已保存的通知副本及其照片"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("刪除這則訊息？")
                        .setMessage("這則通知副本與已保存的照片將永久刪除。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("刪除") { _, _ ->
                            io.execute {
                                val db = InboxDatabase(this@MainActivity)
                                runCatching { db.deleteMessage(entry.id) }
                                db.close()
                                runOnUiThread {
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    entries = entries.filter { it.id != entry.id }
                                    showChat()
                                }
                            }
                        }.show()
                }
            }
            metaRow.addView(del, LinearLayout.LayoutParams(34.dp(), -2))
            holder.addView(metaRow)
            val row = LinearLayout(this).apply {
                gravity = Gravity.START
                setPadding(0, 2.dp(), 0, 3.dp())
            }
            row.addView(holder, LinearLayout.LayoutParams(-2, -2).apply {
                width = minOf(resources.displayMetrics.widthPixels - 42.dp(), 295.dp())
            })
            container.addView(row)
        }
        val chatScroller = scroll(container, BG).apply { isFillViewport = false }
        root.addView(chatScroller, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(txt("僅供查看已擷取的通知副本 · 無法從此發送或標記已讀", 12f, SECONDARY).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(6.dp(), 13.dp(), 6.dp(), 13.dp())
        })
        show(root)
        chatScroller.post { chatScroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showPhoto(entry: InboxDatabase.Entry) {
        if (!unlocked) return
        page = "photo"
        val root = column().apply { setBackgroundColor(Color.BLACK) }
        root.addView(toolbar("照片預覽", { showChat() }))
        val holder = FrameLayout(this)
        val zoom = PhotoZoomView(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.INVISIBLE
        }
        val status = txt("正在載入照片…", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(14.dp(), 10.dp(), 14.dp(), 20.dp())
            setBackgroundColor(Color.BLACK)
        }
        holder.addView(zoom, FrameLayout.LayoutParams(-1, -1))
        holder.addView(status)
        root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(txt("${entry.title}  ·  ${entryTime(entry, true)}  ·  通知內的照片預覽（雙指縮放、雙擊放大）", 12f, Color.WHITE).apply {
            setPadding(14.dp(), 10.dp(), 14.dp(), 20.dp())
            setBackgroundColor(Color.BLACK)
        })
        show(root)
        // Decrypt + decode off the UI thread.
        io.execute {
            val db = InboxDatabase(this)
            val bytes = try { db.loadImage(entry.id) } catch (_: Exception) { null }
            finally { db.close() }
            val bitmap = if (bytes != null) ImageCapture.decodedForDisplay(bytes, 1600) else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bitmap != null) {
                    zoom.setBitmap(bitmap)
                    zoom.visibility = View.VISIBLE
                    holder.removeView(status)
                } else {
                    status.text = "無法載入這張照片（可能已失效或損毀）"
                }
            }
        }
    }

    private fun showSearch() {
        if (!unlocked) return
        page = "search"
        val root = column()
        root.addView(toolbar("搜尋歷史通知", { showInbox(false) }))
        val input = EditText(this).apply {
            hint = "搜尋聯絡人、訊息、來源…"
            textSize = 16f
            setSingleLine(true)
            setPadding(18.dp(), 13.dp(), 18.dp(), 13.dp())
        }
        root.addView(input)

        // Source-app filter row: tap to cycle an optional source restriction.
        var sourceFilter: String? = null
        val sourceRow = txt("來源：全部   ·   點一下依來源過濾", 13f, BLUE).apply {
            setPadding(18.dp(), 4.dp(), 18.dp(), 8.dp())
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                val sources = (CapturePolicy.knownApps + ("全部" to "")).toMutableList()
                AlertDialog.Builder(this@MainActivity).setTitle("依來源 App 過濾")
                    .setItems(sources.map { it.first }.toTypedArray()) { _, i ->
                        sourceFilter = if (i == sources.size - 1) null
                            else CapturePolicy.nameFor(sources[i].second)
                        showSearch()
                    }.show()
            }
        }
        root.addView(sourceRow)

        // Date-range filter: from an optional lower bound on the capture time.
        var fromEpoch: Long = 0L
        val dateRow = txt("日期範圍：${if (fromEpoch > 0L) "自 " + time(fromEpoch, true) + " 起" else "全部"}   ·   點一下設定", 13f, BLUE).apply {
            setPadding(18.dp(), 4.dp(), 18.dp(), 8.dp())
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                val cal = java.util.Calendar.getInstance()
                AlertDialog.Builder(this@MainActivity).setTitle("篩選日期範圍")
                    .setItems(arrayOf("全部時間", "最近 7 天", "最近 30 天", "自今天起")) { _, i ->
                        when (i) {
                            0 -> fromEpoch = 0L
                            1 -> fromEpoch = System.currentTimeMillis() - 7L * 86_400_000
                            2 -> fromEpoch = System.currentTimeMillis() - 30L * 86_400_000
                            else -> {
                                cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
                                fromEpoch = cal.timeInMillis
                            }
                        }
                        showSearch()
                    }.show()
            }
        }
        root.addView(dateRow)

        val results = column()
        root.addView(scroll(results), LinearLayout.LayoutParams(-1, 0, 1f))
        fun search(q: String) {
            results.removeAllViews()
            if (q.isBlank() && sourceFilter == null && fromEpoch == 0L) {
                results.addView(txt("輸入關鍵字，或點上面的列依來源、日期過濾本機紀錄", 14f, SECONDARY).apply {
                    gravity = Gravity.CENTER; setPadding(0, 40.dp(), 0, 0)
                })
                return
            }
            val hits = entries.filter { e ->
                val matchesText = q.isBlank() ||
                    e.title.contains(q, true) || e.sender.contains(q, true) ||
                    e.body.contains(q, true) || CapturePolicy.nameFor(e.packageName).contains(q, true)
                val matchesSource = sourceFilter == null ||
                    CapturePolicy.nameFor(e.packageName) == sourceFilter
                val matchesDate = e.receivedAt >= fromEpoch
                matchesText && matchesSource && matchesDate
            }.take(200)
            for (e in hits) {
                results.addView(threadRow(listOf(e)) {
                    currentThread = threadKey(e)
                    currentTitle = e.title
                    showChat()
                }, LinearLayout.LayoutParams(-1, 76.dp()))
                results.addView(divider())
            }
            if (hits.isEmpty()) results.addView(txt("找不到符合的通知", 14f, SECONDARY).apply {
                setPadding(18.dp(), 30.dp(), 18.dp(), 0)
            })
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { search(s.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        search("")
        show(root)
    }

    private fun showSettings() {
        if (!unlocked) return
        page = "settings"
        val root = column()
        root.addView(toolbar("來源與隱私", { showInbox(true) }))
        val panel = column().apply { setPadding(18.dp(), 18.dp(), 18.dp(), 25.dp()) }
        panel.addView(txt("擷取設定", 20f, INK, true))
        panel.addView(txt("Android 通知監聽權限可以接收多種通知。本程式只保存你勾選的通訊 App，預設完全關閉。", 13f, SECONDARY).apply {
            setPadding(0, 8.dp(), 0, 14.dp())
        })
        val toggle = CheckBox(this).apply {
            text = "啟用訊息記錄"
            buttonTintList = ColorStateList.valueOf(BLUE)
            isChecked = LocalSettings.isRecording(this@MainActivity)
            setOnCheckedChangeListener { _, state -> LocalSettings.setRecording(this@MainActivity, state) }
        }
        panel.addView(toggle)
        val photoToggle = CheckBox(this).apply {
            text = "保存可存取的通知照片（加密縮圖）"
            buttonTintList = ColorStateList.valueOf(BLUE)
            isChecked = LocalSettings.captureImages(this@MainActivity)
            setOnCheckedChangeListener { _, state -> LocalSettings.setCaptureImages(this@MainActivity, state) }
        }
        panel.addView(photoToggle)
        panel.addView(txt("照片僅限通知內實際附帶的圖片，最多 1280px。無法取得原圖、被隱藏的媒體或歷史聊天室照片。", 12f, SECONDARY).apply {
            setPadding(5.dp(), 0, 0, 15.dp())
        })
        val permission = Button(this).apply {
            text = "前往 Android 通知存取授權"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(BLUE)
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        panel.addView(permission, LinearLayout.LayoutParams(-1, 52.dp()))
        panel.addView(txt("選擇訊息來源", 18f, INK, true).apply { setPadding(0, 25.dp(), 0, 8.dp()) })
        for ((name, pkg) in CapturePolicy.knownApps) {
            panel.addView(CheckBox(this).apply {
                text = name
                buttonTintList = ColorStateList.valueOf(BLUE)
                isChecked = pkg in LocalSettings.selected(this@MainActivity)
                setOnCheckedChangeListener { _, checked ->
                    val selected = LocalSettings.selected(this@MainActivity).toMutableSet()
                    if (checked) selected.add(pkg) else selected.remove(pkg)
                    LocalSettings.updateSources(this@MainActivity, selected)
                }
            })
        }
        panel.addView(txt("資料管理", 18f, INK, true).apply { setPadding(0, 24.dp(), 0, 12.dp()) })
        val delete = Button(this).apply {
            text = "刪除全部通知與照片"
            setTextColor(0xffb53636.toInt())
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("永久刪除全部資料？")
                    .setMessage("所有加密的訊息紀錄與照片預覽都會刪除，無法復原。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("確認刪除") { _, _ ->
                        io.execute {
                            val db = InboxDatabase(this@MainActivity)
                            runCatching { db.clear() }
                            db.close()
                            runOnUiThread {
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                entries = emptyList()
                                showInbox(true)
                            }
                        }
                    }.show()
            }
        }
        panel.addView(delete, LinearLayout.LayoutParams(-1, 50.dp()))
        panel.addView(txt("保留期限與儲存空間", 18f, INK, true).apply { setPadding(0, 24.dp(), 0, 12.dp()) })
        val retentionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4.dp(), 0, 0)
        }
        retentionRow.addView(txt("保留期限", 15f, INK).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        val retentionPicker = Button(this).apply {
            text = when (LocalSettings.retentionDays(this@MainActivity)) {
                0 -> "永久"
                else -> "${LocalSettings.retentionDays(this@MainActivity)} 天"
            }
            setTextColor(Color.WHITE)
            minimumWidth = 0
            backgroundTintList = ColorStateList.valueOf(0xff3f5a72.toInt())
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("資料保留期限")
                    .setItems(arrayOf("永久保留", "30 天", "90 天", "180 天")) { _, i ->
                        LocalSettings.setRetentionDays(this@MainActivity, intArrayOf(0, 30, 90, 180)[i])
                        showSettings()
                    }.show()
            }
        }
        retentionRow.addView(retentionPicker)
        panel.addView(retentionRow)
        panel.addView(txt("本機加密儲存空間使用量：${formatBytes(LocalSettings.storageBytes(this))}", 13f, SECONDARY).apply {
            setPadding(0, 10.dp(), 0, 0)
        })
        panel.addView(txt("加密狀態", 18f, INK, true).apply { setPadding(0, 22.dp(), 0, 10.dp()) })
        panel.addView(txt("金鑰：Android Keystore AES-256-GCM（每安裝獨立、金鑰不出裝置）\n儲存：SQLite 內每列訊息與每張照片 blob 個別 AES-GCM 加密\n明文欄位：來源套件名、訊息序号、時間戳、是否有照片", 12f, SECONDARY).apply {
            setLineSpacing(0f, 1.35f)
        })
        panel.addView(txt("版本資訊", 18f, INK, true).apply { setPadding(0, 22.dp(), 0, 10.dp()) })
        panel.addView(txt("Private Inbox Classic v0.2（內部測試）\n沒有網路權限 · 沒有雲端 · 沒有廣告 · 沒有帳號\n本機離線保存通知文字與通知實際附帶的照片", 12f, SECONDARY).apply {
            setLineSpacing(0f, 1.35f)
        })
        panel.addView(txt("沒有 INTERNET 權限、沒有雲端備份，也不提供分享功能。退出畫面後需重新用手機螢幕鎖驗證。", 12f, SECONDARY).apply {
            setPadding(0, 20.dp(), 0, 4.dp())
        })
        root.addView(scroll(panel), LinearLayout.LayoutParams(-1, 0, 1f))
        show(root)
    }
}
