package app.orbit.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Вызов Gemini API (бесплатный ключ из Google AI Studio). Без внешних библиотек. */
object GeminiClient {

    class Result(val commands: List<String>, val reply: String, val error: String? = null)

    const val SYSTEM = """Ты — Orbit, голосовой ассистент на Android-телефоне. Пользователь произносит фразу; она могла быть распознана неточно — исправляй ошибки по смыслу.
Верни ТОЛЬКО JSON вида {"commands":[...],"reply":"..."}.
commands — список команд для телефона, по порядку, строго в таких форматах:
открой <приложение> | позвони <имя или номер> | нажми <текст на экране> | напиши <текст> | прокрути вниз | прокрути вверх | свайп влево | свайп вправо | назад | домой | недавние | уведомления | быстрые настройки | скриншот | заблокируй | громче | тише | без звука | включи фонарик | выключи фонарик | поставь будильник на ЧЧ:ММ | таймер на N минут | найди <запрос> | вайфай | блютуз
Если нужно несколько шагов (например: открыть приложение, нажать поиск, написать текст), перечисли их по порядку. Команду «нажми» используй только с текстом, который есть в списке элементов экрана.
Если команды не нужны (вопрос, разговор), commands оставь пустым, а в reply дай короткий живой ответ (1–2 предложения) на языке пользователя. Если команды есть, reply можно оставить пустым."""

    fun ask(key: String, model: String, userText: String, screen: String): Result {
        return try {
            val prompt = "Экран: $screen\nФраза пользователя: $userText"
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                put("contents", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                put("generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.3))
            }
            val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) {
                return Result(emptyList(), "", "HTTP ${conn.responseCode}")
            }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
            parse(text)
        } catch (e: Exception) {
            Result(emptyList(), "", e.javaClass.simpleName)
        }
    }

    /** Достаёт JSON {"commands":[...],"reply":"..."} из ответа модели (терпимо к лишнему тексту). */
    fun parse(text: String): Result {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return Result(emptyList(), text.trim())
        return try {
            val j = JSONObject(text.substring(s, e + 1))
            val arr = j.optJSONArray("commands") ?: JSONArray()
            Result((0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }, j.optString("reply", ""))
        } catch (ex: Exception) {
            Result(emptyList(), text.trim())
        }
    }
}
