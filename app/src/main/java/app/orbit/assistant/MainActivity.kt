package app.orbit.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import java.io.File
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun btn(title: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = title
                setOnClickListener { action() }
            })
        }

        root.addView(TextView(this).apply { text = "Orbit"; textSize = 32f })
        status = TextView(this)
        root.addView(status)
        btn("1. Выдать разрешения") { requestPermissions(permissions(), 1) }
        btn("2. Включить службу доступности") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("3. Запустить Orbit") { startOrbit() }
        btn("Остановить") { stopService(Intent(this, OrbitVoiceService::class.java)) }
        val keyField = EditText(this).apply {
            hint = "Ключ Gemini API"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.key(this@MainActivity))
        }
        root.addView(keyField)
        btn("Сохранить ключ Gemini") {
            Prefs.setKey(this, keyField.text.toString())
            Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_SHORT).show()
        }
        btn("Получить бесплатный ключ") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        }
        root.addView(Switch(this).apply {
            text = "Всегда понимать через Gemini (умнее, но медленнее)"
            isChecked = Prefs.alwaysAi(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setAlways(this@MainActivity, on) }
        })
        btn("Выбрать локальную модель (.task)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, 2)
        }
        root.addView(Switch(this).apply {
            text = "Предпочитать локальную модель (офлайн, приватно)"
            isChecked = Prefs.preferLocal(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setPreferLocal(this@MainActivity, on) }
        })
        root.addView(TextView(this).apply { text = HELP; setPadding(0, pad, 0, 0) })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val a11y = if (OrbitAccessibilityService.instance != null)
            "Служба доступности: включена ✅" else "Служба доступности: выключена ❌"
        val model = if (LocalLlm.available(this))
            "Локальная модель: загружена (${LocalLlm.file(this).length() / 1048576} МБ) ✅"
        else "Локальная модель: нет"
        status.text = "$a11y\n$model"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        status.text = "Копирую модель… это может занять несколько минут"
        Thread {
            val ok = try {
                val tmp = File(filesDir, "model.tmp")
                contentResolver.openInputStream(uri)!!.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                LocalLlm.reset()
                val dst = LocalLlm.file(this)
                dst.delete()
                tmp.renameTo(dst)
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Модель загружена" else "Не удалось скопировать модель", Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }.start()
    }

    private fun permissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun startOrbit() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions(), 1)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, OrbitVoiceService::class.java))
        Toast.makeText(this, "Orbit запущен. Скажи: «Орбит, ...»", Toast.LENGTH_LONG).show()
    }

    companion object {
        const val HELP = """Команды (начинай со слова «Орбит»):
• открой <приложение> / запусти <приложение>
• позвони <имя или номер>
• нажми <текст на экране>
• напиши <текст> (в активное поле)
• прокрути вниз / вверх, свайп влево / вправо
• назад, домой, недавние, уведомления, быстрые настройки
• скриншот, заблокируй
• громче, тише, без звука, включи / выключи фонарик
• поставь будильник на 7:30, таймер на 5 минут
• найди <запрос>
• вайфай, блютуз (откроет настройки)
• выключись

Если команда не распознана, Orbit спрашивает Gemini: он понимает свободную речь, исправляет ошибки распознавания, выполняет цепочки действий и отвечает на вопросы. Без ключа можно использовать локальную модель (файл .task выбирается кнопкой), она работает офлайн."""
    }
}
