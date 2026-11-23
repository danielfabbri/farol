package app.farol.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Preferências simples (SharedPreferences) expostas como StateFlow. */
class UserPreferences(context: Context) {
    private val sp = context.getSharedPreferences("farol_prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(sp.getString(KEY_THEME, ThemeMode.DARK.name)!!) }.getOrDefault(ThemeMode.DARK)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _highlightLimit = MutableStateFlow(sp.getInt(KEY_HIGHLIGHTS, 7))
    val highlightLimit: StateFlow<Int> = _highlightLimit.asStateFlow()

    private val _otherLimit = MutableStateFlow(sp.getInt(KEY_OTHERS, 15))
    val otherLimit: StateFlow<Int> = _otherLimit.asStateFlow()

    private val _demoMode = MutableStateFlow(sp.getBoolean(KEY_DEMO, false))
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _lastSync = MutableStateFlow(sp.getLong(KEY_LAST_SYNC, 0L))
    val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

    fun setTheme(mode: ThemeMode) { sp.edit().putString(KEY_THEME, mode.name).apply(); _theme.value = mode }
    fun setHighlightLimit(n: Int) { sp.edit().putInt(KEY_HIGHLIGHTS, n).apply(); _highlightLimit.value = n }
    fun setOtherLimit(n: Int) { sp.edit().putInt(KEY_OTHERS, n).apply(); _otherLimit.value = n }
    fun setDemoMode(on: Boolean) { sp.edit().putBoolean(KEY_DEMO, on).apply(); _demoMode.value = on }
    fun setLastSync(ts: Long) { sp.edit().putLong(KEY_LAST_SYNC, ts).apply(); _lastSync.value = ts }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_HIGHLIGHTS = "highlight_limit"
        const val KEY_OTHERS = "other_limit"
        const val KEY_DEMO = "demo_mode"
        const val KEY_LAST_SYNC = "last_sync"
    }
}
