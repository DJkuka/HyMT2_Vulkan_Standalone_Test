package com.example.hymt2vulkantest

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.PowerManager
import android.os.SystemClock
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
import dev.ffmpegkit.llama.LlamaResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

/** Standalone CPU-only benchmark for Tencent Hy-MT2 1.8B STQ1_0 GGUF. */
class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var modelLabel: TextView
    private lateinit var systemInfo: TextView
    private lateinit var sourceInput: EditText
    private lateinit var resultView: TextView
    private lateinit var reportView: TextView
    private lateinit var threadsSpinner: Spinner
    private lateinit var contextSpinner: Spinner
    private lateinit var maxTokensSpinner: Spinner
    private lateinit var chooseButton: Button
    private lateinit var loadButton: Button
    private lateinit var translateButton: Button
    private lateinit var benchmarkButton: Button
    private lateinit var unloadButton: Button
    private lateinit var copyButton: Button

    private var modelDescriptor: ParcelFileDescriptor? = null
    private var modelPath: String? = null
    private var modelBytes: Long = 0
    private var modelName: String = ""
    private var loadedModel: LlamaModel? = null
    private var busy = false
    private val reports = mutableListOf<String>()

    private data class MemorySnapshot(
        val pssKb: Int,
        val rssKb: Long?,
        val hwmKb: Long?,
        val nativeHeapKb: Long,
        val javaHeapUsedMb: Double,
        val javaHeapMaxMb: Double,
    )

    private data class RunMeasurement(
        val number: Int,
        val result: LlamaResult,
        val wallMs: Long,
        val processCpuMs: Long,
        val memoryBefore: MemorySnapshot,
        val memoryAfter: MemorySnapshot,
        val peakPssKb: Int,
        val peakRssKb: Long?,
        val cpuFreqBefore: String,
        val cpuFreqAfter: String,
        val thermalBefore: String,
        val thermalAfter: String,
    )

    private val threadChoices = listOf(2, 3, 4, 6, 8)
    private val contextChoices = listOf(1024, 2048, 3072, 4096)
    private val outputChoices = listOf(256, 512, 768, 1024)

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val info = runCatching { Llama.getSystemInfo() }
            .getOrElse { "Native runtime init error: ${it.javaClass.simpleName}: ${it.message}" }
        systemInfo.text = buildSystemReport(info)
        status.text = "CPU-only STQ1_0 runtime готов. Выбери Hy-MT2-1.8B-1.25Bit.gguf."
        Log.i(TAG, "STQ1_0 CPU benchmark runtime initialized. ABI=${Build.SUPPORTED_ABIS.joinToString()} info=$info")
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply {
            text = "Hy-MT2 1.25-bit · STQ1_0 CPU test"
            textSize = 22f
        }, match())
        root.addView(TextView(this).apply {
            text = "Отдельный тестовый стенд. Только CPU / ARM NEON, без Vulkan. GGUF открывается напрямую с SD-карты: копия не создаётся."
            textSize = 14f
            setPadding(0, 0, 0, pad / 2)
        }, match())

        addLabel(root, "CPU-потоки")
        threadsSpinner = spinner(threadChoices.map { "$it" })
        threadsSpinner.setSelection(threadChoices.indexOf(6))
        root.addView(threadsSpinner, match())

        addLabel(root, "Контекст")
        contextSpinner = spinner(contextChoices.map { "$it токенов" })
        contextSpinner.setSelection(contextChoices.indexOf(2048))
        root.addView(contextSpinner, match())

        addLabel(root, "Максимум выходных токенов")
        maxTokensSpinner = spinner(outputChoices.map { "$it" })
        maxTokensSpinner.setSelection(outputChoices.indexOf(768))
        root.addView(maxTokensSpinner, match())

        modelLabel = TextView(this).apply {
            text = "Модель не выбрана. Нужен Hy-MT2-1.8B-1.25Bit.gguf · около 462 МБ."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(modelLabel, match())

        chooseButton = button("1. Выбрать STQ1_0 GGUF на SD-карте") {
            if (!busy && loadedModel == null) openModel.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
        }
        root.addView(chooseButton, match())
        loadButton = button("2. Загрузить модель в RAM (CPU)") { loadModel() }
        loadButton.isEnabled = false
        root.addView(loadButton, match())

        addLabel(root, "Входной текст (английский)")
        sourceInput = EditText(this).apply {
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP
            textSize = 16f
            setText("You're right. The kill shot probably isn't necessary. And we've a great deal to discuss, in a very short time. But before we begin, I need you to understand one thing: nobody gets out of this room until we know the truth.")
        }
        root.addView(sourceInput, match())

        translateButton = button("3. Перевести один раз") { runInferenceSeries(false, 1) }
        translateButton.isEnabled = false
        root.addView(translateButton, match())
        benchmarkButton = button("Прогрев + 3 замера скорости") { runInferenceSeries(true, 3) }
        benchmarkButton.isEnabled = false
        root.addView(benchmarkButton, match())
        unloadButton = button("Выгрузить модель / сменить настройки") { unloadModel() }
        unloadButton.isEnabled = false
        root.addView(unloadButton, match())

        addLabel(root, "Результат перевода")
        resultView = TextView(this).apply {
            text = "Здесь появится русский перевод."
            textSize = 17f
            setTextIsSelectable(true)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        root.addView(resultView, match())

        addLabel(root, "Диагностика и замеры")
        reportView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            text = "Здесь появится подробный отчёт: загрузка модели, память, CPU, токены и скорость."
        }
        root.addView(reportView, match())
        copyButton = button("Скопировать полный отчёт") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Hy-MT2 STQ1_0 benchmark", buildFullReport()))
            status.text = "Полный отчёт скопирован в буфер обмена."
        }
        copyButton.isEnabled = false
        root.addView(copyButton, match())

        addLabel(root, "Устройство и native runtime")
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
        status.text = "Открываю GGUF через SAF, проверяю обычный файл и сигнатуру…"
        uiScope.launch {
            var newDescriptor: ParcelFileDescriptor? = null
            try {
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                newDescriptor = withContext(Dispatchers.IO) { openAndValidateSeekableGguf(uri) }
                val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "GGUF"
                modelDescriptor?.close()
                modelDescriptor = newDescriptor
                newDescriptor = null
                modelPath = "/proc/self/fd/${modelDescriptor!!.fd}"
                modelBytes = Os.fstat(modelDescriptor!!.fileDescriptor).st_size
                modelName = displayName
                modelLabel.text = "Модель: $displayName\nРазмер: ${formatBytes(modelBytes)}\nNative path: /proc/self/fd/${modelDescriptor!!.fd}\nКопирование в память приложения: нет"
                status.text = "Файл проверен. Нажми «Загрузить модель». Для точной идентификации сравни имя, размер и SHA-256 файла."
                loadButton.isEnabled = true
                Log.i(TAG, "STQ1_0 GGUF selected: name=$displayName bytes=$modelBytes fd=${modelDescriptor!!.fd} path=$modelPath")
            } catch (t: Throwable) {
                status.text = "Ошибка выбора GGUF: ${t.message ?: t.javaClass.simpleName}\n${t.javaClass.name}"
                Log.e(TAG, "SAF GGUF validation failed", t)
            } finally {
                runCatching { newDescriptor?.close() }
                busy = false
                setIdleControls(true)
                loadButton.isEnabled = modelPath != null && loadedModel == null
            }
        }
    }

    private fun openAndValidateSeekableGguf(uri: Uri): ParcelFileDescriptor {
        val pfd = contentResolver.openFileDescriptor(uri, "r")
            ?: error("DocumentsProvider не предоставил файловый дескриптор")
        try {
            val fd = pfd.fileDescriptor
            val stat = Os.fstat(fd)
            if ((stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG) {
                error("Провайдер вернул pipe/stream вместо обычного файла. Выбери GGUF через системный DocumentsUI на SD-карте.")
            }
            if (stat.st_size < 300L * 1024L * 1024L) {
                error("Файл слишком мал для этой модели: ${formatBytes(stat.st_size)}. Ожидается около 462 МБ.")
            }
            val magic = ByteArray(4)
            val read = Os.pread(fd, magic, 0, magic.size, 0L)
            if (read != 4 || magic.toString(Charsets.US_ASCII) != "GGUF") error("Нет сигнатуры GGUF.")
            val probe = Os.open("/proc/self/fd/${pfd.fd}", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
            try {
                val probeStat = Os.fstat(probe)
                if (probeStat.st_size != stat.st_size) error("/proc/self/fd показывает другой размер файла")
                val probeMagic = ByteArray(4)
                val count = Os.pread(probe, probeMagic, 0, probeMagic.size, 0L)
                if (count != 4 || probeMagic.toString(Charsets.US_ASCII) != "GGUF") error("Не удалось повторно прочитать GGUF через /proc/self/fd")
            } finally {
                Os.close(probe)
            }
            return pfd
        } catch (t: Throwable) {
            runCatching { pfd.close() }
            throw t
        }
    }

    private fun loadModel() {
        val path = modelPath ?: return
        val fd = modelDescriptor ?: return
        if (busy || loadedModel != null) return
        val threads = threadChoices.getOrNull(threadsSpinner.selectedItemPosition) ?: 6
        val context = contextChoices.getOrNull(contextSpinner.selectedItemPosition) ?: 2048
        val maxTokens = outputChoices.getOrNull(maxTokensSpinner.selectedItemPosition) ?: 768
        val memBefore = memorySnapshot()
        val cpuBefore = Process.getElapsedCpuTime()
        val started = SystemClock.elapsedRealtimeNanos()

        busy = true
        setIdleControls(false)
        status.text = "Загрузка STQ1_0 в RAM · CPU only · $threads потоков · context=$context."
        Log.i(TAG, "STQ1_0 model load start bytes=$modelBytes threads=$threads context=$context maxTokens=$maxTokens fd=${fd.fd}")

        uiScope.launch {
            try {
                val loaded = Llama.loadModel(
                    modelPath = path,
                    config = LlamaConfig(
                        contextSize = context,
                        threads = threads,
                        gpuLayers = 0,
                        temperature = 0.7f,
                        topP = 0.6f,
                        topK = 20,
                        seed = 42,
                    ),
                )
                loadedModel = loaded
                val wallMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
                val cpuMs = Process.getElapsedCpuTime() - cpuBefore
                val memAfter = memorySnapshot()
                val report = buildString {
                    appendLine("=== ЗАГРУЗКА МОДЕЛИ ===")
                    appendLine("Файл: $modelName")
                    appendLine("Размер файла: ${formatBytes(modelBytes)}")
                    appendLine("Runtime: llama.cpp STQ1_0 CPU/ARM NEON")
                    appendLine("Backend: CPU only; gpuLayers=0; Vulkan отсутствует в AAR")
                    appendLine("Потоки: $threads · контекст: $context · выходной лимит: $maxTokens")
                    appendLine("Время загрузки: ${wallMs} мс")
                    appendLine("CPU time процесса: ${cpuMs} мс · CPU/wall=${percent(cpuMs.toDouble(), wallMs.toDouble())}")
                    appendLine("Память до: ${formatMemory(memBefore)}")
                    appendLine("Память после: ${formatMemory(memAfter)}")
                    appendLine("Изменение PSS: ${signedKb(memAfter.pssKb - memBefore.pssKb)}")
                    appendLine("Частоты CPU: ${cpuFrequencyInfo()}")
                    appendLine("Thermal: ${thermalStatus()} · battery: ${batteryInfo()}")
                }
                reports.clear()
                reports.add(report)
                reportView.text = report
                status.text = "Модель загружена. Введи текст и нажми «Перевести» или «Прогрев + 3 замера»."
                Log.i(TAG, "STQ1_0 load success wallMs=$wallMs cpuMs=$cpuMs memBefore=${formatMemory(memBefore)} memAfter=${formatMemory(memAfter)}")
            } catch (t: Throwable) {
                val wallMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
                val memAfter = memorySnapshot()
                reportView.text = "Ошибка загрузки STQ1_0: ${t.message ?: t.javaClass.simpleName}\n${t.javaClass.name}\nВремя до ошибки: ${wallMs} мс\nПамять: ${formatMemory(memBefore)} → ${formatMemory(memAfter)}"
                status.text = "Не удалось загрузить STQ1_0. Посмотри Logcat TapSTQ1CPU."
                Log.e(TAG, "STQ1_0 load failed wallMs=$wallMs", t)
            } finally {
                busy = false
                setIdleControls(true)
                val success = loadedModel != null
                translateButton.isEnabled = success
                benchmarkButton.isEnabled = success
                unloadButton.isEnabled = success
                loadButton.isEnabled = !success && modelPath != null
                copyButton.isEnabled = reports.isNotEmpty()
            }
        }
    }

    private fun runInferenceSeries(warmup: Boolean, measuredRuns: Int) {
        val model = loadedModel ?: return
        val source = sourceInput.text.toString().trim()
        if (source.isEmpty() || busy) return
        val maxTokens = outputChoices.getOrNull(maxTokensSpinner.selectedItemPosition) ?: 768
        val prompt = "Translate the following segment into Russian, without additional explanation.\n\n$source"
        val threads = threadChoices.getOrNull(threadsSpinner.selectedItemPosition) ?: 6
        val context = contextChoices.getOrNull(contextSpinner.selectedItemPosition) ?: 2048

        busy = true
        setIdleControls(false)
        resultView.text = "Выполняется ${if (warmup) "прогрев и серия из $measuredRuns замеров" else "перевод"}…"
        status.text = "CPU only · $threads потоков · context=$context · maxTokens=$maxTokens"

        uiScope.launch {
            val measurements = mutableListOf<RunMeasurement>()
            var peakPss = memorySnapshot().pssKb
            var peakRss = memorySnapshot().rssKb
            val monitor = launch(Dispatchers.IO) {
                while (isActive) {
                    val sample = memorySnapshot()
                    peakPss = max(peakPss, sample.pssKb)
                    sample.rssKb?.let { value -> peakRss = max(peakRss ?: value, value) }
                    delay(150)
                }
            }
            try {
                if (warmup) {
                    status.text = "Прогрев: первый запуск не войдёт в итоговую статистику…"
                    val warmStart = SystemClock.elapsedRealtimeNanos()
                    val warm = Llama.complete(model, prompt, "", maxTokens)
                    val warmWall = (SystemClock.elapsedRealtimeNanos() - warmStart) / 1_000_000L
                    Log.i(TAG, "STQ1_0 warmup tokens=${warm.tokensGenerated} tps=${warm.tokensPerSecond} wallMs=$warmWall")
                }

                repeat(measuredRuns) { index ->
                    val memBefore = memorySnapshot()
                    val freqBefore = cpuFrequencyInfo()
                    val thermalBefore = thermalStatus()
                    val cpuStart = Process.getElapsedCpuTime()
                    val wallStart = SystemClock.elapsedRealtimeNanos()
                    val result = Llama.complete(model, prompt, "", maxTokens)
                    val wallMs = ((SystemClock.elapsedRealtimeNanos() - wallStart) / 1_000_000L).coerceAtLeast(1L)
                    val cpuMs = (Process.getElapsedCpuTime() - cpuStart).coerceAtLeast(0L)
                    val memAfter = memorySnapshot()
                    measurements += RunMeasurement(
                        number = index + 1,
                        result = result,
                        wallMs = wallMs,
                        processCpuMs = cpuMs,
                        memoryBefore = memBefore,
                        memoryAfter = memAfter,
                        peakPssKb = max(peakPss, memAfter.pssKb),
                        peakRssKb = listOfNotNull(peakRss, memAfter.rssKb).maxOrNull(),
                        cpuFreqBefore = freqBefore,
                        cpuFreqAfter = cpuFrequencyInfo(),
                        thermalBefore = thermalBefore,
                        thermalAfter = thermalStatus(),
                    )
                    Log.i(TAG, "STQ1_0 run=${index + 1} tokens=${result.tokensGenerated} tps=${result.tokensPerSecond} promptMs=${result.promptEvalTimeMs} genMs=${result.generateTimeMs} wallMs=$wallMs cpuMs=$cpuMs peakPssKb=$peakPss peakRssKb=$peakRss")
                    resultView.text = result.text.trim()
                    status.text = "Замер ${index + 1}/$measuredRuns завершён · ${formatTps(result.tokensPerSecond)} ток/с"
                }

                val seriesReport = buildMeasurementReport(measurements, warmup, prompt, threads, context, maxTokens, peakPss, peakRss)
                reports.add(seriesReport)
                reportView.text = reports.takeLast(3).joinToString("\n\n")
                copyButton.isEnabled = true
                val speeds = measurements.map { it.result.tokensPerSecond.toDouble() }.sorted()
                status.text = "Готово · медиана ${String.format(Locale.US, "%.2f", speeds[speeds.size / 2])} ток/с · среднее ${String.format(Locale.US, "%.2f", speeds.average())} ток/с."
            } catch (t: Throwable) {
                val errorReport = "=== ОШИБКА ИНФЕРЕНСА ===\n${t.message ?: t.javaClass.simpleName}\n${t.javaClass.name}\nПамять сейчас: ${formatMemory(memorySnapshot())}"
                reports.add(errorReport)
                reportView.text = reports.takeLast(3).joinToString("\n\n")
                status.text = "Ошибка инференса. Посмотри Logcat TapSTQ1CPU."
                Log.e(TAG, "STQ1_0 inference failed", t)
            } finally {
                monitor.cancel()
                busy = false
                setIdleControls(true)
                val success = loadedModel != null
                translateButton.isEnabled = success
                benchmarkButton.isEnabled = success
                unloadButton.isEnabled = success
                copyButton.isEnabled = reports.isNotEmpty()
            }
        }
    }

    private fun buildMeasurementReport(
        measurements: List<RunMeasurement>,
        warmup: Boolean,
        prompt: String,
        threads: Int,
        context: Int,
        maxTokens: Int,
        peakPssKb: Int,
        peakRssKb: Long?,
    ): String {
        val speeds = measurements.map { it.result.tokensPerSecond.toDouble() }.sorted()
        val totalWall = measurements.sumOf { it.wallMs }
        val totalCpu = measurements.sumOf { it.processCpuMs }
        return buildString {
            appendLine("=== ${if (warmup) "БЕНЧМАРК: ПРОГРЕВ + ${measurements.size} ЗАМЕРА" else "ОДИНОЧНЫЙ ЗАМЕР"} ===")
            appendLine("Модель: $modelName · ${formatBytes(modelBytes)} · STQ1_0")
            appendLine("Backend: CPU/ARM NEON only · Vulkan OFF · gpuLayers=0")
            appendLine("CPU threads=$threads · context=$context · maxTokens=$maxTokens · temp=0.7 · topP=0.6 · topK=20 · seed=42")
            appendLine("Входной текст: ${prompt.length} символов")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} · cores=${Runtime.getRuntime().availableProcessors()}")
            appendLine("Частоты CPU перед серией: ${cpuFrequencyInfo()}")
            appendLine("Thermal: ${thermalStatus()} · battery: ${batteryInfo()}")
            appendLine()
            measurements.forEach { m ->
                appendLine("--- Замер ${m.number} ---")
                appendLine("Tokens generated: ${m.result.tokensGenerated}")
                appendLine("Prompt eval: ${m.result.promptEvalTimeMs} мс")
                appendLine("Generation: ${m.result.generateTimeMs} мс")
                appendLine("llama.cpp speed: ${formatTps(m.result.tokensPerSecond)} ток/с")
                appendLine("Wall time: ${m.wallMs} мс")
                appendLine("Process CPU time: ${m.processCpuMs} мс · CPU/wall=${percent(m.processCpuMs.toDouble(), m.wallMs.toDouble())}")
                appendLine("Memory before: ${formatMemory(m.memoryBefore)}")
                appendLine("Memory after: ${formatMemory(m.memoryAfter)}")
                appendLine("Peak PSS/RSS during series: ${m.peakPssKb} KB / ${m.peakRssKb?.let { "$it KB" } ?: "n/a"}")
                appendLine("CPU frequency: ${m.cpuFreqBefore} → ${m.cpuFreqAfter}")
                appendLine("Thermal status: ${m.thermalBefore} → ${m.thermalAfter}")
                appendLine()
            }
            appendLine("=== ИТОГО ===")
            appendLine("Средняя скорость: ${String.format(Locale.US, "%.2f", speeds.average())} ток/с")
            appendLine("Медианная скорость: ${String.format(Locale.US, "%.2f", speeds[speeds.size / 2])} ток/с")
            appendLine("Минимум / максимум: ${String.format(Locale.US, "%.2f", speeds.first())} / ${String.format(Locale.US, "%.2f", speeds.last())} ток/с")
            appendLine("Средняя генерация: ${measurements.map { it.result.generateTimeMs }.average().toLong()} мс")
            appendLine("Суммарный wall time: $totalWall мс · process CPU time: $totalCpu мс")
            appendLine("Пик PSS серии: $peakPssKb KB · пик RSS: ${peakRssKb?.let { "$it KB" } ?: "n/a"}")
            appendLine("Sampling фиксирован: temp=0.7, topP=0.6, topK=20, seed=42.")
            appendLine("Источник: Tencent / Hy-MT2-1.8B-1.25Bit-GGUF")
        }
    }

    private fun unloadModel() {
        if (busy) return
        busy = true
        setIdleControls(false)
        status.text = "Выгружаю модель и закрываю SAF-дескриптор…"
        uiScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    loadedModel?.let { Llama.releaseModel(it) }
                    loadedModel = null
                    modelDescriptor?.close()
                    modelDescriptor = null
                    modelPath = null
                    modelBytes = 0
                    modelName = ""
                }
                modelLabel.text = "Модель выгружена. Выбери STQ1_0 GGUF заново."
                resultView.text = "Здесь появится русский перевод."
                status.text = "Модель выгружена. Теперь можно изменить число потоков или контекст."
            } catch (t: Throwable) {
                status.text = "Ошибка выгрузки: ${t.message ?: t.javaClass.simpleName}"
                Log.e(TAG, "unloadModel failed", t)
            } finally {
                busy = false
                setIdleControls(true)
                loadButton.isEnabled = modelPath != null
                translateButton.isEnabled = loadedModel != null
                benchmarkButton.isEnabled = loadedModel != null
                unloadButton.isEnabled = loadedModel != null
            }
        }
    }

    private fun setIdleControls(enabled: Boolean) {
        val noModel = loadedModel == null
        chooseButton.isEnabled = enabled && noModel
        threadsSpinner.isEnabled = enabled && noModel
        contextSpinner.isEnabled = enabled && noModel
        maxTokensSpinner.isEnabled = enabled && noModel
        if (!enabled) {
            loadButton.isEnabled = false
            translateButton.isEnabled = false
            benchmarkButton.isEnabled = false
            unloadButton.isEnabled = false
        }
    }

    private fun memorySnapshot(): MemorySnapshot {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        val proc = runCatching { File("/proc/self/status").readLines() }.getOrDefault(emptyList())
        fun procValue(key: String): Long? = proc.firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(":")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()
        return MemorySnapshot(
            pssKb = info.totalPss,
            rssKb = procValue("VmRSS"),
            hwmKb = procValue("VmHWM"),
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L,
            javaHeapUsedMb = javaUsed / (1024.0 * 1024.0),
            javaHeapMaxMb = runtime.maxMemory() / (1024.0 * 1024.0),
        )
    }

    private fun formatMemory(m: MemorySnapshot): String =
        "PSS=${m.pssKb} KB, RSS=${m.rssKb ?: -1} KB, HWM=${m.hwmKb ?: -1} KB, nativeHeap=${m.nativeHeapKb} KB, JavaHeap=${String.format(Locale.US, "%.1f/%.1f MB", m.javaHeapUsedMb, m.javaHeapMaxMb)}"

    private fun cpuFrequencyInfo(): String {
        val dirs = File("/sys/devices/system/cpu").listFiles()
            ?.filter { it.name.matches(Regex("cpu[0-9]+")) }
            ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }
            .orEmpty()
        if (dirs.isEmpty()) return "unavailable"
        val values = dirs.mapNotNull { dir ->
            val freq = runCatching { File(dir, "cpufreq/scaling_cur_freq").readText().trim().toLong() }.getOrNull()
            val online = if (dir.name == "cpu0") true else runCatching { File(dir, "online").readText().trim() != "0" }.getOrDefault(true)
            if (online && freq != null) "${dir.name}=${freq / 1000}MHz" else null
        }
        return if (values.isEmpty()) "sysfs access unavailable" else values.joinToString(", ")
    }

    private fun thermalStatus(): String {
        if (Build.VERSION.SDK_INT < 29) return "API<29"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val value = pm.currentThermalStatus
        val name = when (value) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
        return "$name ($value)"
    }

    private fun batteryInfo(): String {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "unavailable"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val state = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return "$pct% · ${if (charging) "charging/full" else "not charging"}"
    }

    private fun buildSystemReport(nativeInfo: String): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Board: ${Build.BOARD} · hardware: ${Build.HARDWARE} · product: ${Build.PRODUCT}")
        appendLine("Android: ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT} · build ${Build.DISPLAY}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Processors visible to app: ${Runtime.getRuntime().availableProcessors()}")
        appendLine("Total RAM: ${formatBytes(totalRamBytes())}")
        appendLine("CPU frequencies: ${cpuFrequencyInfo()}")
        appendLine("Thermal: ${thermalStatus()} · battery: ${batteryInfo()}")
        appendLine()
        appendLine("Runtime:")
        appendLine("llama.cpp STQ branch commit: 1e411d8f5a1e23525fa3265dfb4bd76265465397")
        appendLine("GGML_TYPE_STQ1_0 = 43")
        appendLine("Backend: CPU/ARM NEON only · GGML_VULKAN=OFF · GGML_OPENMP=OFF")
        appendLine("Native system info:")
        appendLine(nativeInfo)
    }

    private fun totalRamBytes(): Long {
        val mem = android.app.ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mem)
        return mem.totalMem
    }

    private fun buildFullReport(): String = buildString {
        appendLine("Hy-MT2 1.8B STQ1_0 CPU benchmark")
        appendLine("Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())}")
        appendLine()
        appendLine(systemInfo.text)
        appendLine()
        appendLine("Model: $modelName · ${formatBytes(modelBytes)}")
        appendLine("File URI omitted; no model copy created.")
        appendLine()
        reports.forEach { appendLine(it); appendLine() }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: android.database.Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        } finally {
            cursor?.close()
        }
    }

    private fun percent(cpuMs: Double, wallMs: Double): String =
        String.format(Locale.US, "%.1f%%", if (wallMs <= 0.0) 0.0 else cpuMs * 100.0 / wallMs)

    private fun signedKb(value: Int): String = (if (value >= 0) "+" else "") + "$value KB"
    private fun formatTps(value: Float): String = String.format(Locale.US, "%.2f", value)
    private fun formatBytes(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
        n >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", n / (1024.0 * 1024))
        else -> "$n B"
    }

    override fun onDestroy() {
        if (!busy) {
            runCatching { loadedModel?.let { Llama.releaseModel(it) } }
            loadedModel = null
            runCatching { modelDescriptor?.close() }
            modelDescriptor = null
        } else {
            Log.w(TAG, "Activity destroyed during native operation; keep model/fd until process teardown")
        }
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object { const val TAG = "TapSTQ1CPU" }
}
