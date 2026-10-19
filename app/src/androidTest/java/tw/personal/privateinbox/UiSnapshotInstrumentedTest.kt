package tw.personal.privateinbox

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the actual production screens off-screen and writes them as PNG files.
 *
 * FLAG_SECURE blocks `adb screencap`, but that protection is a screen-capture
 * guard — it does not stop the app from drawing itself into a Bitmap. This test
 * therefore measures the real UI (same views, same colours, same layout) without
 * weakening FLAG_SECURE in the shipping code path.
 */
@RunWith(AndroidJUnit4::class)
class UiSnapshotInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun save(name: String, view: View, width: Int = 1080, height: Int = 2400): File {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)
        val dir = File(context.cacheDir, "ui-snapshots").apply { mkdirs() }
        val out = File(dir, "$name.png")
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Emit base64 in chunks so the PNG can be recovered from logcat even after
        // the instrumentation APK (and its directories) are removed from the device.
        val b64 = android.util.Base64.encodeToString(out.readBytes(), android.util.Base64.NO_WRAP)
        val chunk = 3000
        for (i in b64.indices step chunk) {
            val end = minOf(i + chunk, b64.length)
            println("SNAPSHOT_B64 $name ${b64.substring(i, end)}")
        }
        println("SNAPSHOT_DONE $name ${out.length()}")
        return out
    }

    @Test
    fun renderChatListScreen() {
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { act ->
            // Reach the real rendering path: unlock programmatically for the
            // snapshot only. The production unlock gate is untouched.
            val field = MainActivity::class.java.getDeclaredField("unlocked")
            field.isAccessible = true
            field.setBoolean(act, true)
            val entriesField = MainActivity::class.java.getDeclaredField("entries")
            entriesField.isAccessible = true
            entriesField.set(act, sampleEntries())
            act.javaClass.getDeclaredMethod("showInbox", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(act, false)
            val root = act.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
            save("01-chat-list", root)
        }
    }

    @Test
    fun renderConversationScreen() {
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { act ->
            val unlocked = MainActivity::class.java.getDeclaredField("unlocked")
            unlocked.isAccessible = true
            unlocked.setBoolean(act, true)
            val entriesField = MainActivity::class.java.getDeclaredField("entries")
            entriesField.isAccessible = true
            val entries = sampleEntries()
            entriesField.set(act, entries)
            val currentThread = MainActivity::class.java.getDeclaredField("currentThread")
            currentThread.isAccessible = true
            currentThread.set(act, "org.telegram.messenger\u0000Alice Chen")
            val currentTitle = MainActivity::class.java.getDeclaredField("currentTitle")
            currentTitle.isAccessible = true
            currentTitle.set(act, "Alice Chen")
            act.javaClass.getDeclaredMethod("showChat").apply { isAccessible = true }.invoke(act)
            val root = act.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
            save("02-conversation", root)
        }
    }

    @Test
    fun renderSettingsScreen() {
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { act ->
            val unlocked = MainActivity::class.java.getDeclaredField("unlocked")
            unlocked.isAccessible = true
            unlocked.setBoolean(act, true)
            act.javaClass.getDeclaredMethod("showSettings").apply { isAccessible = true }.invoke(act)
            val root = act.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
            save("03-settings", root)
        }
    }

    @Test
    fun renderLockScreen() {
        val activity = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { act ->
            val root = act.findViewById<FrameLayout>(android.R.id.content).getChildAt(0)
            save("00-lock-screen", root)
        }
    }

    /** Deterministic sample rows so the snapshots are stable and readable. */
    private fun sampleEntries(): List<InboxDatabase.Entry> {
        val base = 1_700_000_000_000L
        fun entry(id: Long, pkg: String, title: String, sender: String, body: String, img: Boolean, offset: Long) =
            InboxDatabase.Entry(id, pkg, base + offset, base + offset, title, sender, body, img)
        return listOf(
            entry(1, "org.telegram.messenger", "Alice Chen", "Alice", "晚上一起吃飯嗎？", false, 0),
            entry(2, "jp.naver.line.android", "LINE 家族群", "媽媽", "記得帶保溫瓶", false, 60_000),
            entry(3, "com.whatsapp", "David Wu", "David", "📷 照片", true, 120_000),
            entry(4, "com.instagram.android", "Instagram", "sara.photo", "你 new 了一則貼文", false, 180_000),
            entry(5, "org.telegram.messenger", "Alice Chen", "Alice", "好啊，六點半", false, 240_000)
        )
    }
}
