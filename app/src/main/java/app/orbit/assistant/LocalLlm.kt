package app.orbit.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/** Локальная модель (Gemma в формате MediaPipe .task). Работает офлайн, ключ не нужен. */
object LocalLlm {

    private var llm: LlmInference? = null

    private const val EXAMPLES = """

Примеры:
Фраза: открой ютубчик
{"commands":["открой youtube"],"reply":""}
Фраза: пролистай пониже
{"commands":["прокрути вниз"],"reply":""}
Фраза: сколько будет два плюс два
{"commands":[],"reply":"Четыре."}
Отвечай только JSON."""

    fun file(c: Context) = File(c.filesDir, "model.task")

    fun available(c: Context) = file(c).exists()

    @Synchronized
    fun reset() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
    }

    @Synchronized
    private fun engine(c: Context): LlmInference {
        llm?.let { return it }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file(c).absolutePath)
            .setMaxTokens(1024)
            .build()
        return LlmInference.createFromOptions(c.applicationContext, options).also { llm = it }
    }

    @Synchronized
    fun ask(c: Context, userText: String, screen: String): GeminiClient.Result {
        return try {
            val prompt = "<start_of_turn>user\n" + GeminiClient.SYSTEM + EXAMPLES +
                "\n\nЭкран: $screen\nФраза: $userText<end_of_turn>\n<start_of_turn>model\n"
            GeminiClient.parse(engine(c).generateResponse(prompt))
        } catch (e: Throwable) {
            GeminiClient.Result(emptyList(), "", "локальная модель: " + e.javaClass.simpleName)
        }
    }
}
