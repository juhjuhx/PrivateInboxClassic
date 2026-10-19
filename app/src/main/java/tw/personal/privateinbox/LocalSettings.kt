package tw.personal.privateinbox

import android.content.Context

object LocalSettings {
    private const val NAME = "local_config"
    private const val SOURCES = "selected_source_packages"
    private const val RECORDING = "recording_enabled"
    private const val IMAGES = "capture_media_enabled"

    fun selected(context: Context): Set<String> = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getStringSet(SOURCES, emptySet())
        ?.toSet() ?: emptySet()

    fun updateSources(context: Context, sources: Set<String>) {
        val accepted = sources.intersect(CapturePolicy.knownApps.map { it.second }.toSet())
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(SOURCES, accepted).apply()
    }

    fun isRecording(context: Context): Boolean = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getBoolean(RECORDING, false)

    fun captureImages(context: Context): Boolean = context
        .getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(IMAGES, false)

    fun setCaptureImages(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(IMAGES, enabled).apply()
    }

    fun setRecording(context: Context, enabled: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(RECORDING, enabled).apply()
    }
}
