package app.orbit.assistant

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    fun key(c: Context): String = sp(c).getString("key", "").orEmpty()
    fun setKey(c: Context, v: String) = sp(c).edit().putString("key", v.trim()).apply()

    fun model(c: Context): String = sp(c).getString("model", "gemini-2.5-flash").orEmpty()

    fun preferLocal(c: Context): Boolean = sp(c).getBoolean("local", false)
    fun setPreferLocal(c: Context, v: Boolean) = sp(c).edit().putBoolean("local", v).apply()

    fun alwaysAi(c: Context): Boolean = sp(c).getBoolean("always", false)
    fun setAlways(c: Context, v: Boolean) = sp(c).edit().putBoolean("always", v).apply()
}
