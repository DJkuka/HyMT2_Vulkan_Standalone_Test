package com.example.hymt2vulkantest

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Standalone test host for the Vulkan-enabled llama-android AAR.
 * It deliberately does not depend on Tap Translate.
 */
class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var status: TextView
    private lateinit var modelLabel: TextView
    private lateinit var systemInfo: TextView
    private lateinit var sourceInput: EditText
    private lateinit var resultView: TextView
    private lateinit var gpuSpinner: Spinner
    private lateinit var contextSpinner: Spinner
    private lateinit var threadsSpinner: Spinner
    private lateinit var chooseButton: Button
    private lateinit var loadButton: Button
    private lateinit var translateButton: Button
    private lateinit var unloadButton: Button

    private var modelDescriptor: ParcelFileDescriptor? = null
    private var modelPath: String? = null
    private var loadedModel: LlamaModel? = null
    private var busy = false

    private data class GpuChoice(val label: String, val layers: Int)
    private val gpuChoices = listOf(
        GpuChoice("CPU only · 0 GPU layers", 0),
        GpuChoice("Vulkan · 2 layers", 2),
        GpuChoice("Vulkan · 4 layers", 4),
        GpuChoice("Vulkan · 8 layers", 8),
        GpuChoice("Vulkan · 12 layers", 12),
        GpuChoice("Vulkan · 16 layers", 16),
        GpuChoice("Vulkan · 24 layers", 24),
    )
    private val contextChoices = listOf(2048, 3072, 4096, 6144)
    private val threadChoices = listOf(2, 3, 4, 6)

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val info = runCatching { Llama.getSystemInfo() }
            .getOrElse { "Native runtime load failed: ${it.javaClass.simpleName}: ${it.message}" }
        systemInfo.text = buildString {
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Android API: ${Build.VERSION.SDK_INT}\n")
            append("ABI: ${Build.SUPPORTED_ABIS.joinToString()}\n\n")
            append("llama.cpp system info:\n")
            append(info)
        }
        Log.i(TAG, "Native runtime initialized. ABI=${Build.SUPPORTED_ABIS.joinToString()} info=$info")
        status.text = "Vulkan runtime подключён. Для первого сравнения начни с CPU, затем выгрузи модель и выбери Vulkan · 2 layers."
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Hy-MT2 · Vulkan / CPU test"
            textSize = 22f
        }, match())

        root.addView(TextView(this).apply {
            text = "Самостоятельный тест native runtime. Tap Translate не требуется. GGUF читается напрямую через SAF; копия модели в память приложения не создаётся."
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }, match())

        addLabel(root, "Режим вычислений")
        gpuSpinner = spinner(gpuChoices.map { it.label })
        root.addView(gpuSpinner, match())

        addLabel(root, "Контекст")
        contextSpinner = spinner(contextChoices.map { "$it токенов" })
        contextSpinner.setSelection(0)
        root.addView(contextSpinner, match())

        addLabel(root, "Потоки CPU")
        val defaultThreads = min(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(2)
        threadsSpinner = spinner(threadChoices.map { "$it" })
        threadsSpinner.setSelection(threadChoices.indexOf(defaultThreads).coerceAtLeast(0))
        root.addView(threadsSpinner, match())

        modelLabel = TextView(this).apply {
            text = "Модель не выбрана. Нужен Hy-MT2-1.8B-Q4_K_M.gguf."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(modelLabel, match())

        chooseButton = button("1. Выбрать GGUF на SD-карте") {
            if (!busy && loadedModel == null) {
                openModel.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
            }
        }
        root.addView(chooseButton, match())

        loadButton = button("2. Загрузить модель с выбранным backend") { loadModel() }
        loadButton.isEnabled = false
        root.addView(loadButton, match())

        addLabel(root, "Английский текст")
        sourceInput = EditText(this).apply {
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            textSize = 16f
            setText("You're right. The kill shot probably isn't necessary. And we've a great deal to discuss, in a very short time.")
        }
        root.addView(sourceInput, match())

        translateButton = button("3. Перевести на русский") { translate() }
        translateButton.isEnabled = false
        root.addView(translateButton, match())

        unloadButton = button("Выгрузить модель / сменить режим") { unloadModel() }
        unloadButton.isEnabled = false
        root.addView(unloadButton, match())

        addLabel(root, "Результат")
        resultView = TextView(this).apply {
            text = "Здесь появится перевод."
            textSize = 17f
            setTextIsSelectable(true)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        root.addView(resultView, match())

        addLabel(root, "Сведения runtime / устройства")
        systemInfo = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        root.addView(systemInfo, match())

        status = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad)
        }
        root.addView(status, match())
    }

    private fun addLabel(parent: LinearLayout, value: String) {
        parent.addView(TextView(this).apply {
            text = value
            textSize = 16f
            setPadding(0, 12, 0, 4)
        }, match())
    }

    private fun spinner(items: List<String>): Spinner {
        val control = Spinner(this)
        control.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        return control
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = (7 * resources.displayMetrics.density).toInt() }

    private fun prepareModel(uri: Uri) {
        if (busy || loadedModel != null) return
        busy = true
        setIdleControls(false)
        status.text = "Открываю GGUF через SAF и проверяю файловый дескриптор…"

        uiScope.launch {
            var newDescriptor: ParcelFileDescriptor? = null
            try {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                newDescriptor = withContext(Dispatchers.IO) { openAndValidateSeekableGguf(uri) }
                val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "GGUF"

                modelDescriptor?.close()
                modelDescriptor = newDescriptor
                newDescriptor = null
                modelPath = "/proc/self/fd/${modelDescriptor!!.fd}"
                modelLabel.text = "Модель: $displayName\nРазмер: ${formatBytes(Os.fstat(modelDescriptor!!.fileDescriptor).st_size)}\nПуть native: /proc/self/fd/${modelDescriptor!!.fd}"
                status.text = "Файл проверен. Он остаётся открытым на время загрузки модели; второй файл не создаётся."
                loadButton.isEnabled = true
                Log.i(TAG, "GGUF selected: name=$displayName fd=${modelDescriptor!!.fd} path=$modelPath")
            } catch (t: Throwable) {
                status.text = "Ошибка выбора GGUF: ${t.message ?: t.javaClass.simpleName}\n${t.javaClass.name}"
                Log.e(TAG, "SAF GGUF validation failed", t)
                loadButton.isEnabled = false
            } finally {
                runCatching { newDescriptor?.close() }
                busy = false
                setIdleControls(true)
                loadButton.isEnabled = modelPath != null && loadedModel == null
            }
        }
    }

    /** Opens a regular, seekable descriptor and validates its GGUF signature without copying it. */
    private fun openAndValidateSeekableGguf(uri: Uri): ParcelFileDescriptor {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: error("DocumentsProvider не предоставил файловый дескриптор")
        try {
            val fd = pfd.fileDescriptor
            val stat = Os.fstat(fd)
            val fileType = stat.st_mode and OsConstants.S_IFMT
            if (fileType != OsConstants.S_IFREG) {
                error("Провайдер вернул не обычный файл (pipe/stream). Выбери GGUF через системный DocumentsUI на SD-карте.")
            }
            if (stat.st_size < MIN_MODEL_BYTES) {
                error("Файл слишком мал для Hy-MT2 GGUF: ${formatBytes(stat.st_size)}")
            }
            Os.lseek(fd, 0L, OsConstants.SEEK_SET)
            val magic = ByteArray(4)
            val count = Os.pread(fd, magic, 0, magic.size, 0L)
            if (count != 4 || magic[0] != 0x47.toByte() || magic[1] != 0x47.toByte() ||
                magic[2] != 0x55.toByte() || magic[3] != 0x46.toByte()
            ) {
                error("Нет сигнатуры GGUF. Выбранный файл не похож на GGUF-модель.")
            }
            val procFd = File("/proc/self/fd/${pfd.fd}")
            if (!procFd.exists()) error("Не удалось проверить /proc/self/fd для native loader")
            return pfd
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        } finally {
            cursor?.close()
        }
    }

    private fun loadModel() {
        val path = modelPath ?: return
        if (busy || loadedModel != null) return
        val fd = modelDescriptor ?: return
        val layers = gpuChoices.getOrNull(gpuSpinner.selectedItemPosition)?.layers ?: 0
        val context = contextChoices.getOrNull(contextSpinner.selectedItemPosition) ?: 2048
        val threads = threadChoices.getOrNull(threadsSpinner.selectedItemPosition) ?: 4
        val backend = if (layers == 0) "CPU" else "Vulkan requested ($layers GPU layers)"

        busy = true
        setIdleControls(false)
        status.text = "Загрузка модели: $backend · context=$context · threads=$threads.\nДескриптор ${fd.fd} остаётся открытым."
        Log.i(TAG, "loadModel start backend=$backend context=$context threads=$threads fd=${fd.fd} path=$path")

        uiScope.launch {
            try {
                val loaded = Llama.loadModel(
                    modelPath = path,
                    config = LlamaConfig(
                        contextSize = context,
                        threads = threads,
                        gpuLayers = layers,
                        temperature = 0.7f,
                        topP = 0.6f,
                        topK = 20,
                    ),
                )
                loadedModel = loaded
                status.text = "Модель загружена. Запрошен backend: $backend.\nТеперь выполни перевод. Для смены backend сначала выгрузи модель."
                Log.i(TAG, "loadModel success backend=$backend context=$context threads=$threads")
                translateButton.isEnabled = true
                unloadButton.isEnabled = true
                chooseButton.isEnabled = false
                gpuSpinner.isEnabled = false
                contextSpinner.isEnabled = false
                threadsSpinner.isEnabled = false
            } catch (t: Throwable) {
                status.text = "Ошибка загрузки ($backend): ${t.message ?: t.javaClass.simpleName}\n${t.javaClass.name}\n\nПопробуй CPU или меньше GPU-слоёв."
                Log.e(TAG, "loadModel failed backend=$backend", t)
                loadButton.isEnabled = true
            } finally {
                busy = false
                if (loadedModel == null) {
                    chooseButton.isEnabled = true
                    gpuSpinner.isEnabled = true
                    contextSpinner.isEnabled = true
                    threadsSpinner.isEnabled = true
                    loadButton.isEnabled = modelPath != null
                }
            }
        }
    }

    private fun translate() {
        val model = loadedModel ?: return
        val source = sourceInput.text.toString().trim()
        if (source.isEmpty() || busy) return

        busy = true
        translateButton.isEnabled = false
        chooseButton.isEnabled = false
        unloadButton.isEnabled = false
        resultView.text = "Перевод выполняется на устройстве…"
        status.text = "Генерация запущена…"

        uiScope.launch {
            try {
                val prompt = """Translate the following text into Russian. Output only the translated result without explanations:

$source""".trimIndent()
                val result = Llama.complete(
                    model = model,
                    prompt = prompt,
                    systemPrompt = "You are a professional English-to-Russian comic translator. Use natural idiomatic Russian. Preserve speaker tone, humor, sarcasm, names and meaning. Do not add explanations.",
                    maxTokens = 512,
                )
                resultView.text = result.text.trim()
                status.text = String.format(
                    Locale.US,
                    "Готово · %d токенов · %.2f ток/с · prompt %d мс · генерация %d мс",
                    result.tokensGenerated,
                    result.tokensPerSecond,
                    result.promptEvalTimeMs,
                    result.generateTimeMs,
                )
                Log.i(TAG, "translate success tokens=${result.tokensGenerated} tps=${result.tokensPerSecond} generateMs=${result.generateTimeMs}")
            } catch (t: Throwable) {
                resultView.text = "Ошибка перевода: ${t.message ?: t.javaClass.simpleName}"
                status.text = t.javaClass.name
                Log.e(TAG, "translate failed", t)
            } finally {
                busy = false
                translateButton.isEnabled = loadedModel != null
                unloadButton.isEnabled = loadedModel != null
                chooseButton.isEnabled = loadedModel == null
            }
        }
    }

    private fun unloadModel() {
        if (busy) return
        busy = true
        setIdleControls(false)
        status.text = "Выгружаю модель и закрываю файловый дескриптор…"
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loadedModel?.let { Llama.releaseModel(it) }
                    loadedModel = null
                    modelDescriptor?.close()
                    modelDescriptor = null
                    modelPath = null
                }
                modelLabel.text = "Модель выгружена. Выбери GGUF заново, если нужно повторить тест."
                status.text = "Модель и дескриптор освобождены. Теперь можно выбрать CPU или другой Vulkan-профиль."
                resultView.text = "Здесь появится перевод."
                loadButton.isEnabled = false
                unloadButton.isEnabled = false
            } catch (t: Throwable) {
                status.text = "Ошибка выгрузки: ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, "unloadModel failed", t)
            } finally {
                busy = false
                setIdleControls(true)
                loadButton.isEnabled = false
                unloadButton.isEnabled = false
            }
        }
    }

    private fun setIdleControls(enabled: Boolean) {
        chooseButton.isEnabled = enabled && loadedModel == null
        gpuSpinner.isEnabled = enabled && loadedModel == null
        contextSpinner.isEnabled = enabled && loadedModel == null
        threadsSpinner.isEnabled = enabled && loadedModel == null
        if (!enabled) {
            loadButton.isEnabled = false
            translateButton.isEnabled = false
            unloadButton.isEnabled = false
        }
    }

    override fun onDestroy() {
        // Native inference/load calls are blocking JNI. Do not free their model
        // handle or descriptor while a worker may still be inside native code.
        if (!busy) {
            runCatching { loadedModel?.let { Llama.releaseModel(it) } }
            loadedModel = null
            runCatching { modelDescriptor?.close() }
            modelDescriptor = null
        } else {
            Log.w(TAG, "Activity destroyed while native operation is busy; keep model/fd alive until process teardown")
        }
        uiScope.cancel()
        super.onDestroy()
    }

    private fun formatBytes(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f ГБ", n / (1024.0 * 1024 * 1024))
        n >= 1024L * 1024 -> String.format(Locale.US, "%.0f МБ", n / (1024.0 * 1024))
        else -> "$n Б"
    }

    companion object {
        private const val TAG = "HyMT2VKTest"
        private const val MIN_MODEL_BYTES = 100L * 1024 * 1024
    }
}
