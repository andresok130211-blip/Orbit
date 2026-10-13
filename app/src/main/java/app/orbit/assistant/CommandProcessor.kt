package app.orbit.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings

class CommandProcessor(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val stop: () -> Unit
) {
    private companion object {
        val ALIASES = mapOf(
            "ютуб" to "youtube", "телеграм" to "telegram", "ватсап" to "whatsapp",
            "вацап" to "whatsapp", "камера" to "camera", "настройки" to "settings",
            "хром" to "chrome", "браузер" to "chrome", "карты" to "maps",
            "почта" to "gmail", "гмейл" to "gmail", "плей маркет" to "play store",
            "калькулятор" to "calculator", "часы" to "clock", "галерея" to "gallery",
            "фото" to "photos", "музыка" to "music", "контакты" to "contacts",
            "сообщения" to "messages", "телефон" to "phone", "инстаграм" to "instagram",
            "тикток" to "tiktok", "спотифай" to "spotify", "вк" to "vk", "вконтакте" to "vk"
        )
    }

    private val a11y get() = OrbitAccessibilityService.instance

    private fun withA11y(block: (OrbitAccessibilityService) -> Unit) {
        val s = a11y
        if (s == null) say("Включи службу доступности Orbit") else block(s)
    }

    private fun global(action: Int) = withA11y { it.performGlobalAction(action) }

    fun handle(raw: String): Boolean {
        val c = raw.trim().lowercase().trimEnd('.', '!', '?')
        fun has(vararg w: String) = w.any { c.contains(it) }
        fun arg(vararg p: String) = p.firstOrNull { c.startsWith(it) }?.let { c.removePrefix(it).trim() }

        if (has("выключись", "отключись", "до свидания")) {
            say("Пока")
            Handler(Looper.getMainLooper()).postDelayed({ stop() }, 1500)
            return true
        }
        if (has("вайфай", "wi-fi", "wifi", "вай фай", "вай-фай")) {
            launch(Intent(Settings.ACTION_WIFI_SETTINGS)); return true
        }
        if (has("блютуз", "bluetooth", "блютус")) {
            launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return true
        }
        arg("открой", "запусти", "open")?.let { openApp(it); return true }
        arg("позвони", "call")?.let { call(it); return true }
        arg("нажми", "тапни", "кликни", "click")?.let { t ->
            withA11y { if (!it.clickText(t)) say("Не нашёл $t") }
            return true
        }
        arg("напиши", "введи", "type")?.let { t ->
            withA11y { if (!it.typeText(t)) say("Нет активного поля ввода") }
            return true
        }
        arg("найди", "поиск", "search")?.let { q ->
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)); return true
        }
        if (has("таймер")) { timer(c); return true }
        if (has("будильник")) { alarm(c); return true }

        if (has("прокрут", "листай", "скролл", "пролистай")) {
            withA11y { if (has("вверх")) it.scrollUp() else it.scrollDown() }; return true
        }
        if (has("свайп")) {
            withA11y { if (has("влево")) it.swipeLeft() else it.swipeRight() }; return true
        }
        if (has("скриншот", "снимок экрана")) {
            if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT); return true
        }
        if (has("заблокируй", "блокировка")) {
            if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN); return true
        }
        if (has("недавние", "последние приложения")) { global(AccessibilityService.GLOBAL_ACTION_RECENTS); return true }
        if (has("быстрые настройки")) { global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); return true }
        if (has("уведомления", "шторк")) { global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return true }
        if (has("домой", "домашний экран")) { global(AccessibilityService.GLOBAL_ACTION_HOME); return true }
        if (has("назад")) { global(AccessibilityService.GLOBAL_ACTION_BACK); return true }

        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (has("громче")) {
            repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, if (it == 2) AudioManager.FLAG_SHOW_UI else 0) }
            return true
        }
        if (has("тише")) {
            repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, if (it == 2) AudioManager.FLAG_SHOW_UI else 0) }
            return true
        }
        if (has("без звука")) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            return true
        }
        if (has("фонарик")) {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) { say("Фонарика нет"); return true }
            cm.setTorchMode(id, !has("выключи", "погаси"))
            return true
        }
        return false
    }

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            // Запуск через службу доступности обходит ограничения на старт активити из фона
            (a11y ?: ctx).startActivity(intent)
        } catch (e: Exception) {
            say("Не удалось выполнить")
        }
    }

    private fun openApp(name: String) {
        val pm = ctx.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val keys = listOfNotNull(name, ALIASES[name])
        val app = pm.queryIntentActivities(query, 0).firstOrNull { r ->
            val label = r.loadLabel(pm).toString().lowercase()
            keys.any { label.contains(it) }
        }
        if (app == null) { say("Не нашёл приложение $name"); return }
        val intent = pm.getLaunchIntentForPackage(app.activityInfo.packageName) ?: return
        launch(intent)
        say("Открываю ${app.loadLabel(pm)}")
    }

    private fun call(target: String) {
        val number = if (target.any { it.isDigit() }) target.filter { it.isDigit() || it == '+' }
        else findContact(target)
        if (number == null) { say("Не нашёл контакт $target"); return }
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            say("Нет разрешения на звонки"); return
        }
        launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
        say("Звоню")
    }

    private fun findContact(name: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val n = name.lowercase()
        // Учитываем падежи: «маме» -> «мам»
        val stems = listOf(n, if (n.length > 3) n.dropLast(1) else n)
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        ctx.contentResolver.query(
            phone,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val display = cur.getString(0)?.lowercase() ?: continue
                if (stems.any { display.contains(it) }) return cur.getString(1)
            }
        }
        return null
    }

    private fun timer(c: String) {
        val m = Regex("(\\d+)\\s*(сек|мин|час)?").find(c)
        if (m == null) { say("Не понял, на сколько"); return }
        val n = m.groupValues[1].toInt()
        val seconds = n * when (m.groupValues[2]) { "сек" -> 1; "час" -> 3600; else -> 60 }
        launch(Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        say("Таймер поставлен")
    }

    private fun alarm(c: String) {
        val m = Regex("(\\d{1,2})[: ]?(\\d{2})?").find(c)
        val hour = m?.groupValues?.get(1)?.toIntOrNull()
        val minute = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (hour == null || hour > 23 || minute > 59) { say("Не понял время"); return }
        launch(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        say("Будильник на %d:%02d".format(hour, minute))
    }
}
