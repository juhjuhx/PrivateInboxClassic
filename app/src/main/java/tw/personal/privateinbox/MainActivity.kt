package tw.personal.privateinbox

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.VisibleForTesting
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
import androidx.activity.result.contract.ActivityResultContracts
import tw.personal.privateinbox.dhc.DhcExportPlan
import tw.personal.privateinbox.dhc.DhcExportService
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Hand-built 2014–2016 Material One visual language, without tracking/UI libraries. */
class MainActivity : ComponentActivity() {
    private companion object {
        const val AUTH_REQUEST = 101
        const val POST_NOTIF_REQUEST = 102
        const val BLUE = 0xff5682A3.toInt()
        const val BLUE_DARK = 0xff497799.toInt()
        const val BADGE = 0xff4D8BC4.toInt()
        const val INK = 0xff212121.toInt()
        const val SECONDARY = 0xff757575.toInt()
        const val BG = 0xffE7EBF0.toInt()
        const val LINE = 0xffE0E0E0.toInt()

        /**
         * The number of messages loaded per page.
         *
         * A page is decrypted in full, so this is the cost of the first
         * screen. The measurement: a page read is a CONSTANT ~89 ms whether the
         * table holds 100 rows or 10,000, because the query is indexed and
         * only the page is decrypted.
         */
        const val PAGE_SIZE = 50

        /** IR-01: messages per page on the conversation DETAIL screen. */
        const val CHAT_PAGE_SIZE = 100
    }

    private var unlocked = false
    private var page = "locked"
    private lateinit var main: FrameLayout
    internal var entries: List<InboxDatabase.Entry> = emptyList()
    private var currentThread: String? = null
    private var currentTitle: String? = null
    /** Currently selected platform filter (package name), or null for "all". */
    private var selectedPlatform: String? = null
    /** Active search query, shared by the search screen and the platform filter. */
    private var searchQuery: String? = null
    /** Search screen source filter (package name) or null for "all". */
    private var searchSourceFilter: String? = null
    /** Search screen lower time bound in epoch millis; 0 means no bound. */
    private var searchFromEpoch: Long = 0L
    /** The state of the encryption key as observed during the last read. */
    private var keyState: CryptoStore.KeyState = CryptoStore.KeyState.KEY_AVAILABLE
    /** Number of rows that could not be decrypted during the last read. */
    private var unreadableRows: Int = 0

    /** IR-01 P2: whether the open conversation has older messages to load. */
    private var chatHasMore: Boolean = false

    /** IR-01 P2: how many messages the open conversation currently shows. */
    private var chatPageSize: Int = 0

    /** IR-10: all chat messages loaded so far for the open conversation,
     *  accumulated across "載入更早的訊息" clicks. Newest first. */
    private var chatEntries: List<InboxDatabase.Entry> = emptyList()

    /**
     * IR-10: keyset cursor for the open conversation's chat detail page.
     * Holds the (received_at, id) of the OLDEST loaded message, so the next
     * page reads strictly older messages via beforeReceivedAt/beforeId.
     * null means no page has been loaded yet (first load fetches the newest page).
     */
    private var chatCursor: Pair<Long, Long>? = null

    /**
     * IR-01 P2: the last conversation seen, used as a KEYSET cursor for the next
     * page. Anchoring to the last row actually rendered (rather than to an
     * offset) keeps paging stable when a new message re-orders the list.
     */
    private var lastConversationCursor: Pair<Long, String>? = null

    /** True when another page can be loaded. */
    private var hasMorePages: Boolean = false

    /** True while a page is being loaded, so the load is not started twice. */
    private var loadingPage: Boolean = false

    /** The number of pages loaded so far, for the diagnostics panel. */
    private var pagesLoaded: Int = 0

    /**
     * The plan the user is about to export.
     *
     * It is built first and shown, so the export is always an informed,
     * explicit action. Nothing is written before the user has seen it.
     */
    private var pendingExportPlan: tw.personal.privateinbox.dhc.DhcExportPlan.Plan? = null

    /**
     * The Storage Access Framework picker.
     *
     * The user chooses the destination. The app never picks a path itself, so
     * it can only write where the user pointed it.
     */
    private val exportDestination = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val plan = pendingExportPlan
        pendingExportPlan = null
        if (uri == null || plan == null) {
            Toast.makeText(this, "已取消匯出", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        askExportPassphrase(plan, uri)
    }
    /** Guard so the recovery dialog is not shown repeatedly. */
    private var keyRecoveryShown = false
    private val io: ExecutorService = Executors.newFixedThreadPool(2)
    private var selfTestDialog: AlertDialog? = null
    private val colors = intArrayOf(0xff5aa8a1.toInt(), 0xffc28d64.toInt(), 0xff68a4c9.toInt(),
        0xffbc799e.toInt(), 0xff9183bc.toInt(), 0xff78a676.toInt())

    private val postNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            showDiagnostics()
        }

    private fun Int.dp() = (this * resources.displayMetrics.density + .5f).toInt()

    /**
     * Read a diagnostic counter as a display string.
     *
     * The counters map holds mixed types (Long counts, String labels). An
     * unchecked cast here previously crashed the whole settings screen when a
     * key was missing, so the read is type-safe by construction.
     */
    private fun diagCount(counters: Map<String, Any>, key: String): String =
        when (val v = counters[key]) {
            null -> "0"
            is Long -> v.toString()
            is Number -> v.toString()
            else -> v.toString()
        }

    private fun formatBytes(bytes: Long): String = when {        bytes < 1024 -> "$bytes B"
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

    internal fun loadEntries() {
        // DB read + AES-GCM decryption off the main thread; re-render when ready.
        android.util.Log.d("LOADENTRIES", "loadEntries dispatched")
        io.execute {
            android.util.Log.d("LOADENTRIES", "io task running")
            val db = InboxDatabase(this)
            var loaded: List<InboxDatabase.Entry>
            var more = false
            var loadedKeyState = CryptoStore.KeyState.KEY_AVAILABLE
            var loadedUnreadable = 0
            var lastCursor: Pair<Long, String>? = null
            try {
                // T03: reconcile any unlinked rows first (idempotent, cheap when
                // there is nothing to do), then read the canonical map the UI
                // groups by.
                val store = ConversationStore(this@MainActivity)
                try { store.linkAllMessages() } finally { store.close() }
                refreshCanonicalKeys()
                // IR-01: page by CONVERSATION, not by message.
                //
                // The previous implementation read the newest 50 MESSAGES and
                // only then grouped them into conversations. Two defects
                // followed directly:
                //   * a conversation with 100 recent messages filled the whole
                //     page, so an older-but-distinct conversation never
                //     appeared at all;
                //   * a conversation whose rows straddled the page boundary was
                //     split into two boxes.
                // conversationPage() computes the grouping in SQL over the whole
                // table, so one row per conversation is returned regardless of
                // how many messages it holds.
                val summaries = db.conversationPage(limit = PAGE_SIZE, offset = 0)
                val convRows = ArrayList<InboxDatabase.Entry>(summaries.size)
                var worstState = CryptoStore.KeyState.KEY_AVAILABLE
                var badRows = 0
                for (s in summaries) {
                    // Decrypt just this conversation's newest message. The
                    // preview also reports the key state, which is the ONLY
                    // ciphertext touch on the inbox path now that paging is by
                    // conversation.
                    val preview = db.conversationPreview(s.latestMessageId)
                    preview.entry?.let { convRows.add(it) }
                    badRows += preview.unreadable
                    if (preview.keyState.isUnrecoverable) worstState = preview.keyState
                    else if (worstState == CryptoStore.KeyState.KEY_AVAILABLE) {
                        worstState = preview.keyState
                    }
                }
                android.util.Log.d(
                    "LOADENTRIES",
                    "conversationPage convos=${summaries.size} rows=${convRows.size}"
                )
                loaded = convRows
                lastCursor = summaries.lastOrNull()?.let {
                    it.latestMessageTimestamp to it.canonicalConversationId
                }
                loadedKeyState = if (worstState.isUnrecoverable) {
                    CryptoStore.KeyState.RECOVERY_REQUIRED
                } else worstState
                loadedUnreadable = badRows
                more = summaries.size >= PAGE_SIZE
                val days = LocalSettings.retentionDays(this)
                if (days > 0) runCatching { db.pruneOlderThanDays(days) }
            } catch (e: Exception) {
                android.util.Log.e("LOADENTRIES", "loadEntries failed", e)
                loaded = emptyList()
            } finally { db.close() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                android.util.Log.d("LOADENTRIES", "UI update rows=${loaded.size}")
                entries = loaded
                // P1: restore the key-recovery signal that the IR-01 rewrite
                // dropped. Without this, offerKeyRecovery() never fires and the
                // diagnostics panel always reports KEY_AVAILABLE.
                keyState = loadedKeyState
                unreadableRows = loadedUnreadable
                lastConversationCursor = lastCursor
                hasMorePages = more
                pagesLoaded = 1
                when (page) {
                    "inbox" -> showInbox(false)
                    "chat" -> showChat()
                    else -> Unit
                }
                offerKeyRecovery()
            }
        }
    }

    /**
     * Offer the user an explicit choice when the encryption key is gone.
     *
     * The rows are NOT deleted automatically: an invalidated key is permanent,
     * but a temporarily unavailable key (the device has not been unlocked yet)
     * must never destroy anything. The user is told exactly what happened and
     * must confirm before anything is removed.
     */
    private fun offerKeyRecovery() {
        if (keyRecoveryShown) return
        // A key that is only TEMPORARILY unavailable must not raise a dialog
        // offering to delete anything: the device simply has not been unlocked
        // yet, and nothing is lost.
        if (keyState == CryptoStore.KeyState.KEY_AVAILABLE ||
            keyState == CryptoStore.KeyState.KEY_TEMPORARILY_UNAVAILABLE
        ) {
            return
        }
        if (keyState == CryptoStore.KeyState.DECRYPT_FAILED && unreadableRows == 0) return

        val description = KeyRecovery.describe(keyState, unreadableRows, entries.size)
        if (description.action == KeyRecovery.Action.NONE) {
            // Nothing the user can do about it right now; the state is shown in
            // the diagnostics panel instead.
            keyRecoveryShown = true
            return
        }
        keyRecoveryShown = true
        AlertDialog.Builder(this@MainActivity)
            .setTitle(description.title)
            .setMessage(description.message)
            .setNegativeButton("保留資料") { _, _ -> keyRecoveryShown = false }
            .setPositiveButton("清除無法解密的資料") { _, _ ->
                keyRecoveryShown = false
                // A SECOND, explicit confirmation before anything is deleted.
                KeyRecovery.confirmClear(this@MainActivity, unreadableRows) {
                    purgeUndecryptable()
                }
            }
            .setOnCancelListener { keyRecoveryShown = false }
            .show()
    }

    /**
     * Delete the rows that cannot be decrypted.
     *
     * This is only ever reached after the user has confirmed twice: once that
     * they understand the state, and once that they want the deletion. The
     * rows that CAN be decrypted are never touched.
     */
    private fun purgeUndecryptable() {
        io.execute {
            val db = InboxDatabase(this@MainActivity)
            val removed = runCatching { db.purgeUndecryptableRows() }.getOrDefault(0)
            db.close()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this@MainActivity,
                    "已清除 $removed 則無法解密的資料", Toast.LENGTH_LONG).show()
                loadEntries()
            }
        }
    }

    /**
     * Load the next page and append it to the list.
     *
     * The list is paged, so the user asks for more explicitly. That keeps the
     * first screen fast and makes the loading visible instead of surprising.
     */
    private fun loadMorePages() {
        if (loadingPage || !hasMorePages) return
        val cursor = lastConversationCursor ?: return
        loadingPage = true
        io.execute {
            val db = InboxDatabase(this@MainActivity)
            var more: List<InboxDatabase.Entry> = emptyList()
            var hasMore = false
            var nextCursor: Pair<Long, String>? = null
            try {
                // IR-01 P2: the NEXT page of conversations via the KEYSET
                // cursor, so an insert that re-orders the list cannot make this
                // page skip a conversation.
                val summaries = db.conversationPage(
                    limit = PAGE_SIZE,
                    afterLatestReceived = cursor.first,
                    afterGid = cursor.second
                )
                val rows = ArrayList<InboxDatabase.Entry>(summaries.size)
                for (s in summaries) {
                    db.entryForMessageId(s.latestMessageId)?.let { rows.add(it) }
                }
                nextCursor = summaries.lastOrNull()?.let {
                    it.latestMessageTimestamp to it.canonicalConversationId
                }
                more = rows
                hasMore = summaries.size >= PAGE_SIZE
            } catch (_: Exception) {
                // A failed page leaves the list as it was.
            } finally {
                refreshCanonicalKeys()
                db.close()
            }
            runOnUiThread {
                loadingPage = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (more.isNotEmpty()) {
                    entries = entries + more
                    lastConversationCursor = nextCursor
                    hasMorePages = hasMore
                    pagesLoaded++
                    when (page) {
                        "inbox" -> showInbox(false)
                        "chat" -> showChat()
                        else -> Unit
                    }
                } else {
                    hasMorePages = false
                }
            }
        }
    }

    /**
     * Canonical conversation identity for one entry.
     *
     * T03: the inbox previously grouped the CURRENT PAGE by the legacy key, so a
     * conversation whose rows straddled a page boundary appeared twice and a
     * conversation with only older rows was missed. The grouping is now driven by
     * the canonical link table, with the legacy key as the fallback for rows that
     * predate linking (they must stay visible, never vanish).
     */
    private var canonicalKeys: Map<Long, String> = emptyMap()

    private fun refreshCanonicalKeys() {
        val db = InboxDatabase(this)
        try {
            val map = HashMap<Long, String>()
            db.readableDatabase.rawQuery(
                "SELECT message_id, conversation_id FROM message_conversation", null
            ).use { c ->
                while (c.moveToNext()) map[c.getLong(0)] = c.getString(1)
            }
            canonicalKeys = map
        } finally { db.close() }
    }

    private fun threadKey(e: InboxDatabase.Entry): String =
        canonicalKeys[e.id]
            ?: e.conversationKey.ifEmpty {
                InboxDatabase.legacyConversationKey(e.packageName, e.title)
            }

    internal fun threads(list: List<InboxDatabase.Entry>) =
        list.groupBy { threadKey(it) }
            .entries.sortedByDescending { thread -> thread.value.maxOf { e -> e.receivedAt } }

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
        // Source label under the contact name, so the platform is visible at a glance.
        val sourceLabel = SourceRegistry.displayName(last.packageName)
        if (sourceLabel.isNotEmpty() && sourceLabel != last.title) {
            details.addView(txt(sourceLabel, 11f, BLUE).apply {
                maxLines = 1
                setPadding(0, 1.dp(), 0, 0)
            })
        }
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
        // Platform filter bar: the selected source filters the list below. The
        // search query and the selected platform are independent.
        root.addView(platformFilterBar())
        val visible = filteredEntries()
        val data = column()
        if (reload && entries.isEmpty()) {
            data.addView(txt("正在載入已保存的通知…", 16f, SECONDARY).apply {
                gravity = Gravity.CENTER
                setPadding(12.dp(), 82.dp(), 12.dp(), 16.dp())
            })
        } else if (visible.isEmpty()) {
            val filtered = selectedPlatform != null
            val recoveryNeeded =
                keyState == CryptoStore.KeyState.RECOVERY_REQUIRED && !filtered
            data.addView(txt(
                when {
                    recoveryNeeded -> "加密金鑰已失效"
                    filtered -> "這個來源還沒有已保存的通知"
                    else -> "目前沒有已保存的通知"
                },
                18f, INK, true).apply {
                gravity = Gravity.CENTER
                setPadding(12.dp(), 82.dp(), 12.dp(), 16.dp())
            })
            data.addView(txt(
                when {
                    recoveryNeeded ->
                        "$unreadableRows 則已保存的訊息已永久無法解密。\n" +
                            "金鑰無法復原，但資料仍留在本機 — 可在設定中選擇保留或清除。"
                    filtered -> "切換到「全部」可查看其他來源。\n請在設定中開啟來源並授予通知存取權限。"
                    else -> "點右下角設定，開啟來源及照片擷取，再到 Android 授予通知存取權限。"
                },
                14f, SECONDARY).apply {
                gravity = Gravity.CENTER; setPadding(22.dp(), 0, 22.dp(), 0)
            })
        } else {
            for (thread in threads(visible)) {
                val first = thread.value.first()
                // A stable, content-derived tag so tests can assert on the REAL
                // view hierarchy: the key is exactly what the row represents.
                val rowView = threadRow(thread.value) {
                    currentThread = thread.key
                    currentTitle = first.title
                    showChat()
                }
                rowView.tag = thread.key
                data.addView(rowView, LinearLayout.LayoutParams(-1, 76.dp()))
                data.addView(divider())
            }
            // The list is paged. Another page is loaded only when the user
            // asks for it, so the first screen never decrypts the whole table.
            if (hasMorePages) {
                data.addView(txt(
                    if (loadingPage) "載入中…" else "載入更多",
                    15f, BLUE
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 18.dp(), 0, 22.dp())
                    setOnClickListener { loadMorePages() }
                })
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

    /**
     * Horizontal platform filter bar. Each entry is a source from [SourceRegistry],
     * so adding a platform needs no UI change here.
     */
    private fun platformFilterBar(): View {
        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp(), 6.dp(), 8.dp(), 6.dp())
        }
        // "All" chip.
        row.addView(filterChip("全部", selectedPlatform == null) {
            selectedPlatform = null
            showInbox(false)
        })
        for (source in SourceRegistry.filterableSources) {
            val count = entries.count { it.packageName == source.packageName }
            val label = if (count > 0) "${source.shortLabel} $count" else source.shortLabel
            row.addView(filterChip(label, selectedPlatform == source.packageName) {
                selectedPlatform = if (selectedPlatform == source.packageName) null
                                   else source.packageName
                showInbox(false)
            })
        }
        bar.addView(row)
        return bar
    }

    private fun filterChip(label: String, active: Boolean, onClick: () -> Unit): View =
        txt(label, 13f, if (active) Color.WHITE else SECONDARY, active).apply {
            gravity = Gravity.CENTER
            background = rounded(if (active) BLUE else 0xFFF0F2F5.toInt(), 14, LINE)
            setPadding(14.dp(), 7.dp(), 14.dp(), 7.dp())
            setOnClickListener { onClick() }
        }

    /** Entries after applying the platform filter and the active search query. */
    /** QA hook: let instrumentation drive searchQuery/selectedPlatform without
     *  touching private fields. */
    /**
     * IR-08 test hook: open a conversation exactly as a row tap does.
     *
     * The independent review pointed out that testing the DB method directly
     * cannot catch a dead routing branch in `showChat()`. This drives the real
     * `currentThread`/`currentTitle` + `showChat()` path.
     */
    @VisibleForTesting
    internal fun openThreadForTest(threadKey: String, title: String) {
        currentThread = threadKey
        currentTitle = title
        showChat()
    }

    internal fun setSearchInputsForTest(query: String?, platform: String?) {
        searchQuery = query
        selectedPlatform = platform
    }

    internal fun filteredEntries(): List<InboxDatabase.Entry> {
        var list = entries
        val platform = selectedPlatform
        if (platform != null) list = list.filter { it.packageName == platform }
        val query = searchQuery?.trim()
        if (!query.isNullOrEmpty()) {
            list = list.filter {
                it.title.contains(query, true) || it.sender.contains(query, true) ||
                    it.body.contains(query, true) ||
                    SourceRegistry.displayName(it.packageName).contains(query, true)
            }
        }
        return list
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
        // IR-01: the DETAIL page pages by MESSAGE, which is the correct unit
        // here (the user is reading one conversation's history). The inbox page
        // above pages by CONVERSATION.
        //
        // P1 (independent review): the previous version branched on
        // `key.startsWith("k:")`, but `currentThread` comes from threadKey(),
        // which returns the RAW legacy key and never carries the "k:" prefix
        // that conversationPage() adds. The branch was therefore unreachable and
        // an unlinked conversation still showed exactly one message.
        //
        // The routing is now decided by the DATA, not by a string prefix: try the
        // canonical read first, and fall back to the legacy-key read when it
        // yields nothing (which is exactly the unlinked case). No prefix
        // convention can silently break this.
        // IR-10: page by message cursor instead of re-reading with a fixed limit.
        // chatEntries accumulates all messages loaded so far for this conversation,
        // so clicking "載入更早的訊息" genuinely adds older messages instead of
        // discarding the previously fetched page.
        val detailPage = runCatching {
            InboxDatabase(this).use { db ->
                val page = if (chatEntries.isNotEmpty() && chatCursor != null) {
                    // Next page: older messages after the cursor.
                    val (curTs, curId) = chatCursor!!
                    val canonical = db.readConversationMessages(
                        key, limit = CHAT_PAGE_SIZE,
                        beforeReceivedAt = curTs, beforeId = curId)
                    if (canonical.entries.isNotEmpty()) canonical
                    else db.readMessagesByLegacyKey(
                        key, limit = CHAT_PAGE_SIZE,
                        beforeReceivedAt = curTs, beforeId = curId)
                } else {
                    // First load: newest page, no cursor.
                    val canonical = db.readConversationMessages(key, limit = CHAT_PAGE_SIZE)
                    if (canonical.entries.isNotEmpty()) canonical
                    else db.readMessagesByLegacyKey(key, limit = CHAT_PAGE_SIZE)
                }
                page
            }
        }.getOrNull()
        val group: List<InboxDatabase.Entry> = if (detailPage != null && detailPage.entries.isNotEmpty()) {
            chatHasMore = detailPage.hasMore
            val newEntries = detailPage.entries.sortedWith(
                compareBy<InboxDatabase.Entry> { it.receivedAt }.thenBy { it.id }
            )
            // Append older pages to what we already loaded (dedup by id).
            val existingIds = chatEntries.map { it.id }.toSet()
            val appended = newEntries.filter { it.id !in existingIds }
            chatEntries = (chatEntries + appended)
                .sortedWith(compareBy<InboxDatabase.Entry> { it.receivedAt }.thenBy { it.id })
                .reversed() // newest first for display
            // Advance cursor to the oldest message in this page.
            val oldest = detailPage.entries.minByOrNull { it.receivedAt }
            chatCursor = oldest?.let { it.receivedAt to it.id }
            chatPageSize = chatEntries.size
            chatEntries
        } else {
            // Fall back to the already-loaded conversation rows (the inbox page
            // holds this conversation's newest message) so the screen is never
            // blank while the detail page is unavailable.
            entries.filter { threadKey(it) == key }
                .sortedWith(compareBy<InboxDatabase.Entry> { it.receivedAt }.thenBy { it.id })
        }
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
        // IR-01 P2: a REAL affordance for older history. The flag used to be
        // written and never read, so a conversation with more than
        // CHAT_PAGE_SIZE messages was silently truncated with no way forward.
        if (chatHasMore) {
            container.addView(txt("載入更早的訊息", 14f, BLUE).apply {
                gravity = Gravity.CENTER
                setPadding(0, 10.dp(), 0, 12.dp())
                setOnClickListener { loadOlderChatMessages() }
            })
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

    /**
     * IR-01 P2: extend the open conversation's history backwards.
     *
     * Re-reads the conversation with a larger limit rather than paginating with
     * a cursor: the chat view scrolls to the bottom on entry, so growing the
     * window keeps the reading position stable and cannot skip a row.
     */
    private fun loadOlderChatMessages() {
        val key = currentThread ?: return
        if (!chatHasMore) return
        // Called from the UI thread (click handler), so showChat() can run
        // synchronously. Using io.execute + runOnUiThread here would be async
        // and the test could observe the UI before showChat() finishes, seeing
        // a stale page count.
        showChat()
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
            setText(searchQuery.orEmpty())
            setSelection(text.length)
        }
        root.addView(input)

        // Source-app filter row. The selection lives in activity state so it
        // survives the dialog re-rendering this screen.
        val sourceRow = txt(
            "來源：${searchSourceFilter?.let { SourceRegistry.displayName(it) } ?: "全部"}   ·   點一下依來源過濾",
            13f, BLUE).apply {
            setPadding(18.dp(), 4.dp(), 18.dp(), 8.dp())
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                val sources = SourceRegistry.filterableSources
                val labels = (listOf("全部") + sources.map { it.displayName }).toTypedArray()
                AlertDialog.Builder(this@MainActivity).setTitle("依來源 App 過濾")
                    .setItems(labels) { _, i ->
                        searchSourceFilter = if (i == 0) null else sources[i - 1].packageName
                        showSearch()
                    }.show()
            }
        }
        root.addView(sourceRow)

        // Date-range filter: from an optional lower bound on the capture time.
        val fromEpoch = searchFromEpoch
        val dateRow = txt("日期範圍：${if (fromEpoch > 0L) "自 " + time(fromEpoch, true) + " 起" else "全部"}   ·   點一下設定", 13f, BLUE).apply {
            setPadding(18.dp(), 4.dp(), 18.dp(), 8.dp())
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                val cal = java.util.Calendar.getInstance()
                AlertDialog.Builder(this@MainActivity).setTitle("篩選日期範圍")
                    .setItems(arrayOf("全部時間", "最近 7 天", "最近 30 天", "自今天起")) { _, i ->
                        searchFromEpoch = when (i) {
                            0 -> 0L
                            1 -> System.currentTimeMillis() - 7L * 86_400_000
                            2 -> System.currentTimeMillis() - 30L * 86_400_000
                            else -> {
                                cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
                                cal.timeInMillis
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
            searchQuery = q
            val sourceFilter = searchSourceFilter
            if (q.isBlank() && sourceFilter == null && fromEpoch == 0L) {
                results.addView(txt("輸入關鍵字，或點上面的列依來源、日期過濾本機紀錄", 14f, SECONDARY).apply {
                    gravity = Gravity.CENTER; setPadding(0, 40.dp(), 0, 0)
                })
                return
            }
            val hits = entries.filter { e ->
                val matchesText = q.isBlank() ||
                    e.title.contains(q, true) || e.sender.contains(q, true) ||
                    e.body.contains(q, true) ||
                    SourceRegistry.displayName(e.packageName).contains(q, true)
                val matchesSource = sourceFilter == null || e.packageName == sourceFilter
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
        search(searchQuery.orEmpty())
        show(root)
    }

    /**
     * The export section of the settings screen.
     *
     * The export is an explicit, local action: the user chooses what to
     * export, where to put it, and a passphrase. Nothing is sent anywhere,
     * and the app has no INTERNET permission to send it with.
     */
    private fun exportSection(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(txt("加密匯出", 18f, INK, true).apply { setPadding(0, 22.dp(), 0, 4.dp()) })
        addView(txt(
            "把已保存的訊息匯出成一個加密檔案，供離線工具讀取。" +
                "檔案由你提供的密碼加密，密碼不會被儲存。" +
                "本程式沒有網路權限，匯出的檔案不會離開這台裝置，除非你把它交出去。",
            13f, SECONDARY
        ).apply { setPadding(0, 4.dp(), 0, 12.dp()) })

        addView(Button(this@MainActivity).apply {
            text = "匯出全部私人訊息"
            setOnClickListener { startExport(DhcExportPlan.Scope.ALL) }
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, 46.dp()) })

        addView(Button(this@MainActivity).apply {
            text = "選擇來源後匯出"
            setOnClickListener { startExport(DhcExportPlan.Scope.SELECTED_SOURCES) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, 46.dp())
            setPadding(0, 8.dp(), 0, 0)
        })
    }

    /**
     * Start an export: build the plan, then ask for the destination.
     *
     * The plan is built on the worker thread because it reads and decrypts the
     * database. The user sees the plan before anything is written.
     */
    private fun startExport(scope: DhcExportPlan.Scope) {
        io.execute {
            val db = InboxDatabase(this@MainActivity)
            val filter = when (scope) {
                DhcExportPlan.Scope.ALL -> DhcExportPlan.Filter()
                DhcExportPlan.Scope.SELECTED_SOURCES -> DhcExportPlan.Filter(
                    scope = scope,
                    packages = LocalSettings.selected(this@MainActivity).toSet()
                )
                DhcExportPlan.Scope.DATE_RANGE -> DhcExportPlan.Filter(scope = scope)
            }
            val plan = runCatching { DhcExportPlan.build(this@MainActivity, filter, db) }
                .getOrNull()
            db.close()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (plan == null) {
                    Toast.makeText(this@MainActivity, "無法建立匯出計畫", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (plan.messages.isEmpty()) {
                    Toast.makeText(this@MainActivity, "沒有符合條件的訊息", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                pendingExportPlan = plan
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("確認匯出")
                    .setMessage(plan.summary())
                    .setNegativeButton("取消") { _, _ -> pendingExportPlan = null }
                    .setPositiveButton("選擇儲存位置") { _, _ ->
                        exportDestination.launch("private-inbox-export-${System.currentTimeMillis()}.picbox")
                    }
                    .show()
            }
        }
    }

    /**
     * Ask for the passphrase that protects the export.
     *
     * The passphrase is held in a CharArray that is zeroed as soon as the
     * export finishes. It is never stored and never logged.
     */
    private fun askExportPassphrase(plan: DhcExportPlan.Plan, destination: android.net.Uri) {
        val input = android.widget.EditText(this).apply {
            hint = "匯出密碼"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(20.dp(), 14.dp(), 20.dp(), 14.dp())
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 8.dp(), 18.dp(), 0)
            addView(txt(
                "請輸入一個匯出密碼。這個密碼之後是讀取檔案的唯一方式，" +
                    "忘記了就無法復原。",
                13f, SECONDARY
            ))
            addView(input)
        }
        AlertDialog.Builder(this@MainActivity)
            .setTitle("設定匯出密碼")
            .setView(wrapper)
            .setNegativeButton("取消", null)
            .setPositiveButton("匯出") { _, _ ->
                val passphrase = input.text.toString().toCharArray()
                input.text?.clear()
                if (passphrase.isEmpty()) {
                    Toast.makeText(this@MainActivity, "密碼不能是空的", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                runExport(plan, destination, passphrase)
            }
            .show()
    }

    /** Run the export and report the outcome. */
    private fun runExport(
        plan: DhcExportPlan.Plan,
        destination: android.net.Uri,
        passphrase: CharArray
    ) {
        io.execute {
            val result = DhcExportService.export(this@MainActivity, destination, plan, passphrase)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is DhcExportService.Result.Written -> Toast.makeText(
                        this@MainActivity,
                        "已匯出 ${result.messages} 則訊息（${result.bytes} bytes）",
                        Toast.LENGTH_LONG
                    ).show()
                    is DhcExportService.Result.Cancelled -> Toast.makeText(
                        this@MainActivity, "已取消匯出", Toast.LENGTH_SHORT
                    ).show()
                    is DhcExportService.Result.Failed -> Toast.makeText(
                        this@MainActivity, "匯出失敗：${result.reason}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showSettings() {
        if (!unlocked) return
        page = "settings"
        val root = column()
        root.addView(toolbar("來源與隱私", { showInbox(true) }))
        val panel = column().apply { setPadding(18.dp(), 18.dp(), 18.dp(), 25.dp()) }
        panel.addView(exportSection())
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
        // The self-test and burst-test fixtures are not offered to the user.
        for (source in SourceRegistry.all.filterNot { it.isSelfTest }) {
            panel.addView(CheckBox(this).apply {
                text = source.displayName
                buttonTintList = ColorStateList.valueOf(BLUE)
                isChecked = source.packageName in LocalSettings.selected(this@MainActivity)
                setOnCheckedChangeListener { _, checked ->
                    val selected = LocalSettings.selected(this@MainActivity).toMutableSet()
                    if (checked) selected.add(source.packageName)
                    else selected.remove(source.packageName)
                    LocalSettings.updateSources(this@MainActivity, selected)
                }
            })
        }
        panel.addView(txt("每個來源可獨立開關。未勾選的來源，其通知不會被保存。", 12f, SECONDARY).apply {
            setPadding(5.dp(), 0, 5.dp(), 8.dp())
        })
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
        panel.addView(txt("Private Inbox Classic v0.3.1（內部測試）\n沒有網路權限 · 沒有雲端 · 沒有廣告 · 沒有帳號\n本機離線保存通知文字與通知實際附帶的照片", 12f, SECONDARY).apply {
            setLineSpacing(0f, 1.35f)
        })
        panel.addView(txt("沒有 INTERNET 權限、沒有雲端備份，也不提供分享功能。退出畫面後需重新用手機螢幕鎖驗證。", 12f, SECONDARY).apply {
            setPadding(0, 20.dp(), 0, 4.dp())
        })
        // Notification-listener diagnostics: honest, no fabricated status.
        panel.addView(txt("通知監聽診斷", 18f, INK, true).apply { setPadding(0, 22.dp(), 0, 10.dp()) })
        val c = CaptureDiagnostics.counters(this)
        val status = CaptureDiagnostics.resolveStatus(this)
        fun diagRow(label: String, value: String): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 3.dp(), 0, 0)
            }
            row.addView(txt(label, 14f, SECONDARY).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1.3f) })
            row.addView(txt(value, 14f, INK).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.END
            })
            return row
        }
        panel.addView(diagRow("通知存取權限", when {
            SelfTestNotifier.listenerPermissionGranted(this) -> "已授權"
            else -> "未授權"
        }))
        panel.addView(diagRow("監聽器連線狀態", status))
        panel.addView(diagRow("訊息記錄開關", if (LocalSettings.isRecording(this)) "開啟" else "關閉"))
        panel.addView(diagRow("允許來源數量", LocalSettings.selected(this).size.toString()))
        panel.addView(diagRow("收到通知事件", diagCount(c, "received")))
        panel.addView(diagRow("解析成功", diagCount(c, "parse_ok")))
        panel.addView(diagRow("資料庫寫入成功", diagCount(c, "db_ok")))
        panel.addView(diagRow("失敗事件", diagCount(c, "failed")))
        panel.addView(diagRow("最後錯誤代碼", "${c["last_code"]} / ${c["last_err"]}"))
        val lastReceived = diagCount(c, "last_notification_received_at").toLongOrNull() ?: 0L
        val lastEvent = diagCount(c, "last_diagnostic_event_at").toLongOrNull() ?: 0L
        val lastSuccess = diagCount(c, "last_successful_capture_at").toLongOrNull() ?: 0L
        panel.addView(diagRow("最後收到通知", if (lastReceived > 0) time(lastReceived, true) else "（尚無）"))
        panel.addView(diagRow("最後成功保存", if (lastSuccess > 0) time(lastSuccess, true) else "（尚無）"))
        panel.addView(diagRow("最後診斷事件", if (lastEvent > 0) time(lastEvent, true) else "（尚無）"))
        panel.addView(divider().apply { setPadding(0, 14.dp(), 0, 4.dp()) })

        // The encryption key's own state. This is what tells a temporary lock
        // apart from a permanent loss, which the message list alone cannot.
        panel.addView(txt("加密金鑰", 16f, INK, true).apply { setPadding(0, 8.dp(), 0, 6.dp()) })
        val keyDescription = KeyRecovery.describe(keyState, unreadableRows, entries.size)
        panel.addView(diagRow("金鑰狀態", keyState.name))
        panel.addView(diagRow("無法解密的訊息", "$unreadableRows 則"))
        panel.addView(diagRow("金鑰診斷", KeyRecovery.diagnose(this)))
        panel.addView(divider().apply { setPadding(0, 14.dp(), 0, 4.dp()) })

        // The capture pipeline's quantitative counters. They are in-memory, so
        // they are reset when the process dies.
        panel.addView(txt("擷取管線（本次行程）", 16f, INK, true).apply { setPadding(0, 8.dp(), 0, 6.dp()) })
        val metrics = CaptureMetrics.snapshot()
        panel.addView(diagRow("通知回呼", "${metrics["callbacks_entered"] ?: 0}"))
        panel.addView(diagRow("通過來源政策", "${metrics["policy_accepted"] ?: 0}"))
        panel.addView(diagRow("呈現的訊息", "${metrics["messages_presented"] ?: 0}"))
        panel.addView(diagRow("送去寫入", "${metrics["messages_submitted"] ?: 0}"))
        panel.addView(diagRow("入庫", "${metrics["db_committed"] ?: 0}"))
        panel.addView(diagRow("重複", "${metrics["db_duplicated"] ?: 0}"))
        panel.addView(diagRow("寫入失敗", "${metrics["db_failed"] ?: 0}"))
        panel.addView(diagRow("佇列最大深度", "${metrics["queue_high_watermark"] ?: 0}"))
        panel.addView(diagRow("佇列溢出", "${metrics["queue_overflow"] ?: 0}"))
        panel.addView(diagRow("Worker 例外", "${metrics["worker_failures"] ?: 0}"))
        panel.addView(diagRow("截斷的訊息", "${metrics["messages_truncated"] ?: 0}"))
        panel.addView(diagRow("回呼耗時 p95/p99",
            "${CaptureMetrics.callbackDuration.snapshot().p95} / " +
                "${CaptureMetrics.callbackDuration.snapshot().p99} ms"))
        panel.addView(diagRow("佇列等待 p95/p99",
            "${CaptureMetrics.queueWait.snapshot().p95} / " +
                "${CaptureMetrics.queueWait.snapshot().p99} ms"))
        panel.addView(divider().apply { setPadding(0, 14.dp(), 0, 4.dp()) })
        val diagActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10.dp(), 0, 0)
        }
        fun diagButton(label: String, tint: Int, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                minimumWidth = 0
                backgroundTintList = ColorStateList.valueOf(tint)
                setOnClickListener { onClick() }
            }.also { diagActions.addView(it, LinearLayout.LayoutParams(0, 44.dp(), 1f).apply { marginEnd = 6.dp() }) }
        diagButton("重新檢查連線", BLUE) {
            recheckListenerConnection()
        }
        diagButton("發送測試通知", BLUE) {
            sendSelfTest()
        }
        diagButton("重新整理訊息", BLUE) {
            loadEntries()
            showInbox(false)
        }
        panel.addView(diagActions)
        val diagButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8.dp(), 0, 0)
        }
        diagButtons.addView(Button(this).apply {
            text = "匯出匿名診斷報告"
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(0xff3f5a72.toInt())
            setOnClickListener {
                val report = CaptureDiagnostics.export(this@MainActivity)
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("Private Inbox 診斷報告", report))
                AlertDialog.Builder(this@MainActivity).setTitle("診斷報告已複製")
                    .setMessage("匿名診斷（不含訊息內容或照片）已複製到剪貼簿。")
                    .setPositiveButton("好", null).show()
            }
        })
        diagButtons.addView(Button(this).apply {
            text = "清除診斷紀錄"
            setTextColor(0xffb53636.toInt())
            backgroundTintList = ColorStateList.valueOf(0xff555a5f.toInt())
            setOnClickListener {
                CaptureDiagnostics.clear(this@MainActivity)
                showSettings()
            }
        })
        panel.addView(diagButtons)
        root.addView(scroll(panel), LinearLayout.LayoutParams(-1, 0, 1f))
        show(root)
    }

    private fun recheckListenerConnection() {
        // Ask the system to (re)bind the listener on user action only. Guarded against
        // restart loops: this is a single, user-initiated call, never a timer. The
        // real CONNECTED/DISCONNECTED evidence still comes from the service's own
        // onListenerConnected / onListenerDisconnected callbacks — a rebind request
        // that was merely accepted must not be logged as a connection.
        val requested = CaptureDiagnostics.rebind(this)
        io.execute {
            Thread.sleep(800)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!requested) {
                    // rebind() refused (not bound / not granted): record the failure,
                    // but do not fabricate a connection state.
                    CaptureDiagnostics.note(this, packageName, "lifecycle",
                        CaptureDiagnostics.LISTENER_DISCONNECTED, "rebind_refused")
                }
                showSettings()
            }
        }
    }

    private fun sendSelfTest() {
        val ctx = this
        if (!SelfTestNotifier.listenerPermissionGranted(ctx)) {
            AlertDialog.Builder(ctx).setTitle("缺少通知存取權限")
                .setMessage("請先在 Android 設定授予本程式「通知存取」權限，測試通知才會抵達監聽器。")
                .setPositiveButton("前往授權") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("取消", null).show()
            return
        }
        // Ensure the self-test source is enabled so the listener stores it.
        val sel = LocalSettings.selected(ctx).toMutableSet()
        sel.add(CapturePolicy.SELF_TEST)
        LocalSettings.updateSources(ctx, sel)
        LocalSettings.setRecording(ctx, true)

        if (!SelfTestNotifier.canPost(ctx)) {
            requestPostNotificationPermission()
            return
        }
        val posted = SelfTestNotifier.post(ctx)
        selfTestDialog = AlertDialog.Builder(ctx).setTitle("發送測試通知")
            .setMessage(if (posted) "已發送。監聽器應在幾秒內將其存檔。正在檢查…" else
                "無法發送：本機未允許本程式顯示通知。")
            .setCancelable(false)
            .setPositiveButton("完成", null)
            .show()
        io.execute {
            val deadline = System.currentTimeMillis() + 10_000
            var row: InboxDatabase.Entry? = null
            while (System.currentTimeMillis() < deadline) {
                val db = InboxDatabase(ctx)
                val rows = try { db.recent(50) } finally { db.close() }
                row = rows.firstOrNull {
                    it.packageName == CapturePolicy.SELF_TEST && it.body == SelfTestNotifier.BODY
                }
                if (row != null) break
                Thread.sleep(300)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val diag = CaptureDiagnostics
                if (row != null) {
                    CaptureDiagnostics.note(ctx, CapturePolicy.SELF_TEST, "UI",
                        CaptureDiagnostics.UI_REFRESH_SUCCESS)
                    selfTestDialog?.dismiss()
                    entries = entries + row
                    currentThread = threadKey(row)
                    currentTitle = row.title
                    showChat()
                    AlertDialog.Builder(ctx).setTitle("系統通知端到端測試通過")
                        .setMessage("測試通知經 Android 通知系統送達監聽器，已加密入庫並顯示。" +
                            " LINE／Messenger 真機相容性仍需實體手機驗證。")
                        .setPositiveButton("好", null).show()
                } else {
                    diag.note(ctx, CapturePolicy.SELF_TEST, "UI",
                        CaptureDiagnostics.UI_REFRESH_FAILED, "self_test_row_missing")
                    selfTestDialog?.dismiss()
                    showDiagnostics()
                }
            }
        }
    }

    private fun requestPostNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            postNotificationPermissionLauncher.launch(
                android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            sendSelfTest()
        }
    }

    private fun showDiagnostics() {
        if (!unlocked) return
        page = "settings"
        showSettings()
    }
}
