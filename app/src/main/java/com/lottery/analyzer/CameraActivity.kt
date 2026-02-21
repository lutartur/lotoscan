package com.lottery.analyzer

import android.graphics.Color
import android.graphics.Rect
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lottery.analyzer.util.AppLogger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private var camera: Camera? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var cameraPreview: FrameLayout
    private lateinit var overlay: DrawingOverlay
    private lateinit var statusText: TextView
    private lateinit var backButton: Button
    private var selectedNumbers: List<Int> = emptyList()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    private val isPreviewing = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)
    private var lastProcessingTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        selectedNumbers = intent.getIntegerArrayListExtra("selectedNumbers")?.toList() ?: emptyList()
        
        AppLogger.i("CameraActivity created with ${selectedNumbers.size} numbers")
        
        surfaceView = findViewById(R.id.surfaceView)
        cameraPreview = findViewById(R.id.cameraPreview)
        statusText = findViewById(R.id.statusText)
        backButton = findViewById(R.id.backButton)
        overlay = findViewById(R.id.drawingOverlay)
        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(this)
        statusText.text = "Инициализация камеры..."
        
        backButton.setOnClickListener {
            AppLogger.d("Back button clicked")
            finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            AppLogger.d("Surface created: ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
            camera = Camera.open()
            val params = camera!!.parameters

            // Выбираем оптимальный размер превью
            val previewSize = getOptimalPreviewSize(params.supportedPreviewSizes, surfaceView.width, surfaceView.height)
            if (previewSize != null) {
                params.setPreviewSize(previewSize.width, previewSize.height)
                camera!!.parameters = params
                AppLogger.d("Preview size set to ${previewSize.width}x${previewSize.height}")
            }

            // Устанавливаем ориентацию камеры для портретного режима (90 градусов)
            camera!!.setDisplayOrientation(90)
            camera!!.setPreviewDisplay(holder)

            // Устанавливаем callback buffer для предотвращения блокировки превью
            if (previewSize != null) {
                val bufferSize = previewSize.width * previewSize.height * 3 / 2
                camera!!.addCallbackBuffer(ByteArray(bufferSize))
                camera!!.setPreviewCallbackWithBuffer(this)
            }

            camera!!.startPreview()
            isPreviewing.set(true)
            statusText.text = "Сканирование..."
            AppLogger.i("Camera started successfully")
        } catch (e: Exception) {
            AppLogger.e("Camera initialization failed", e)
            e.printStackTrace()
            mainHandler.post {
                Toast.makeText(this, "Ошибка камеры: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun getOptimalPreviewSize(sizes: List<Camera.Size>, w: Int, h: Int): Camera.Size? {
        if (sizes.isEmpty()) return null
        
        // Для портретной ориентации меняем ширину и высоту местами
        // Камера установлена горизонтально, но мы держим телефон вертикально
        val targetWidth = h  // Высота view становится шириной после поворота на 90°
        val targetHeight = w  // Ширина view становится высотой после поворота
        
        var bestSize: Camera.Size? = null
        var minDiff = Double.MAX_VALUE
        
        for (size in sizes) {
            // Ищем размер с минимальной разницей
            val diff = Math.abs(size.width - targetWidth) + Math.abs(size.height - targetHeight)
            if (diff < minDiff) {
                minDiff = diff.toDouble()
                bestSize = size
            }
        }
        
        AppLogger.d("Optimal preview: ${bestSize?.width}x${bestSize?.height} for target ${targetWidth}x${targetHeight}")
        return bestSize
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLogger.d("Surface changed: ${width}x${height}")
        // Перезапускаем превью при изменении размера
        if (holder.surface == null) return
        try {
            camera?.stopPreview()
            isPreviewing.set(false)
        } catch (e: Exception) {
            AppLogger.w("Error stopping preview", e)
        }
        try {
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
            isPreviewing.set(true)
            AppLogger.d("Preview restarted")
        } catch (e: Exception) {
            AppLogger.e("Error restarting preview", e)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLogger.d("Surface destroyed")
        try {
            if (isPreviewing.get()) {
                camera?.stopPreview()
                isPreviewing.set(false)
            }
            camera?.setPreviewCallback(null)
            camera?.setPreviewCallbackWithBuffer(null)
            camera?.release()
            camera = null
            AppLogger.i("Camera released")
        } catch (e: Exception) {
            AppLogger.e("Error releasing camera", e)
        }
    }

    override fun onPause() {
        super.onPause()
        AppLogger.d("onPause")
        // Останавливаем превью для экономии ресурсов
        if (isPreviewing.get()) {
            camera?.stopPreview()
            isPreviewing.set(false)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.d("onResume")
        // Возобновляем превью
        if (camera != null && surfaceHolder.surface != null) {
            try {
                camera?.startPreview()
                isPreviewing.set(true)
                AppLogger.d("Preview resumed")
            } catch (e: Exception) {
                AppLogger.e("Error resuming preview", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d("onDestroy")
        try {
            if (isPreviewing.get()) {
                camera?.stopPreview()
            }
            camera?.release()
            camera = null
            textRecognizer.close()
            executor.shutdown()
            AppLogger.i("Resources cleaned up")
        } catch (e: Exception) {
            AppLogger.e("Error cleaning up resources", e)
        }
    }

    private var processing = AtomicBoolean(false)
    private var lastFrameTime = 0L

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        // Освобождаем кадр сразу для предотвращения блокировки превью
        camera.addCallbackBuffer(data)
        
        // Пропускаем кадры если предыдущий еще обрабатывается или прошло мало времени
        val currentTime = System.currentTimeMillis()
        if (processing.get() || (currentTime - lastFrameTime) < 500) {
            return
        }
        
        processing.set(true)
        lastFrameTime = currentTime
        
        // Копируем данные для асинхронной обработки
        val frameData = data.copyOf()
        val params = camera.parameters
        val previewWidth = params.previewSize.width
        val previewHeight = params.previewSize.height
        
        executor.execute {
            try {
                val startTime = System.currentTimeMillis()
                
                // ML Kit получает изображение в ландшафтной ориентации
                // rotation = 90 означает поворот по часовой стрелке на 90 градусов
                val image = InputImage.fromByteArray(frameData, previewWidth, previewHeight, 90, InputImage.IMAGE_FORMAT_NV21)
                
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        // ML Kit с rotation=90 возвращает координаты для повернутого изображения
                        // Размеры: previewHeight становится шириной, previewWidth становится высотой
                        val blockResults = detectAndAnalyzeBlocks(visionText, previewHeight, previewWidth)
                        mainHandler.post {
                            // Передаем размеры повернутого изображения для масштабирования
                            overlay.setBlockResults(blockResults, previewHeight, previewWidth)
                            overlay.invalidate()
                            updateStatus(blockResults)
                        }
                        
                        val duration = System.currentTimeMillis() - startTime
                        if (duration > 500) {
                            AppLogger.w("Processing took ${duration}ms")
                        }
                    }
                    .addOnFailureListener { e ->
                        AppLogger.e("Text recognition failed", e)
                    }
                    .addOnCompleteListener {
                        processing.set(false)
                    }
            } catch (e: Exception) {
                AppLogger.e("Preview frame processing error", e)
                processing.set(false)
            }
        }
    }

    private fun detectAndAnalyzeBlocks(visionText: com.google.mlkit.vision.text.Text, width: Int, height: Int): List<DrawingOverlay.BlockResult> {
        val results = mutableListOf<DrawingOverlay.BlockResult>()
        
        // Сначала собираем все числа
        val allNumbers = mutableListOf<TextElement>()
        
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val rect = element.boundingBox
                    val rawText = element.text

                    if (rect != null) {
                        val centerY = rect.centerY()
                        
                        // Фильтруем слишком широкие элементы
                        val elementWidth = rect.right - rect.left
                        val elementHeight = rect.bottom - rect.top
                        if (elementWidth > 200 || elementHeight > 100) {
                            continue
                        }
                        // Фильтруем элементы, которые не являются числами
                        if (rawText == null || rawText.length < 1 || rawText.length > 3) {
                            continue
                        }

                        // Проверяем, является ли текст числом от 1 до 90
                        val num = rawText.toIntOrNull()
                        if (num == null || num !in 1..90) {
                            continue
                        }

                        allNumbers.add(TextElement(rawText, rect, 0))
                    }
                }
            }
        }
        
        if (allNumbers.isEmpty()) {
            AppLogger.d("No numbers detected")
            return results
        }
        
        // Сортируем числа по Y-координате
        val sortedByY = allNumbers.sortedBy { it.rect.centerY() }
        
        // Ищем самый большой разрыв по Y между соседними числами
        var maxGap = 0
        var gapIndex = -1
        for (i in 1 until sortedByY.size) {
            val gap = sortedByY[i].rect.centerY() - sortedByY[i - 1].rect.centerY()
            if (gap > maxGap) {
                maxGap = gap
                gapIndex = i
            }
        }
        
        val boundaryY = if (gapIndex > 0) {
            (sortedByY[gapIndex - 1].rect.centerY() + sortedByY[gapIndex].rect.centerY()) / 2
        } else {
            sortedByY[sortedByY.size / 2].rect.centerY()
        }
        
        // Разделяем числа на верхние и нижние относительно найденного разрыва
        val upperNumberElements = sortedByY.filter { it.rect.centerY() < boundaryY }
        val lowerNumberElements = sortedByY.filter { it.rect.centerY() >= boundaryY }
        
        AppLogger.d("=== Detection with gap-based Y-split ===")
        AppLogger.d("Total numbers: ${allNumbers.size}, Max gap: $maxGap at index $gapIndex, Boundary Y: $boundaryY")
        AppLogger.d("Upper block: ${upperNumberElements.size} numbers")
        AppLogger.d("Lower block: ${lowerNumberElements.size} numbers")
        
        // Создаём рамки для блоков на основе распознанных чисел
        if (upperNumberElements.isNotEmpty()) {
            results.add(createBlockResultFromElements(1, upperNumberElements, width, height))
        }
        if (lowerNumberElements.isNotEmpty()) {
            results.add(createBlockResultFromElements(2, lowerNumberElements, width, height))
        }

        return results
    }
    
    /**
     * Создаёт рамку блока на основе распознанных элементов
     */
    private fun createBlockResultFromElements(
        blockNumber: Int,
        elements: List<TextElement>,
        previewWidth: Int,
        previewHeight: Int
    ): DrawingOverlay.BlockResult {
        val extractedNumbers = elements.mapNotNull { it.text.toIntOrNull() }.distinct().take(15)
        val matchCount = extractedNumbers.intersect(selectedNumbers.toSet()).size
        val borderColor = when {
            matchCount == 15 -> Color.GREEN
            matchCount >= 13 -> Color.YELLOW
            else -> Color.RED
        }

        // Находим границы по всем элементам
        if (elements.isEmpty()) {
            return DrawingOverlay.BlockResult(blockNumber, Rect(0, 0, 100, 100), extractedNumbers, matchCount, borderColor)
        }

        val minX = elements.minOf { it.rect.left }
        val minY = elements.minOf { it.rect.top }
        val maxX = elements.maxOf { it.rect.right }
        val maxY = elements.maxOf { it.rect.bottom }

        val boundingRect = Rect(minX, minY, maxX, maxY)
        
        AppLogger.d("Block $blockNumber: ${elements.size} elements, ${extractedNumbers.size} numbers, matches: $matchCount, rect=[$boundingRect]")

        return DrawingOverlay.BlockResult(blockNumber, boundingRect, extractedNumbers, matchCount, borderColor)
    }

    private fun updateStatus(results: List<DrawingOverlay.BlockResult>) {
        val status = StringBuilder()
        for (result in results) {
            val blockName = if (result.blockNumber == 1) "ВЕРХНИЙ" else "НИЖНИЙ"
            val statusText = when (result.borderColor) {
                Color.GREEN -> "✅ ПОЛНОЕ СОВПАДЕНИЕ"
                Color.YELLOW -> "⚠️ ЧАСТИЧНОЕ"
                else -> "❌ НЕСОВПАДЕНИЕ"
            }
            status.append("БЛОК ${result.blockNumber} ($blockName):\n$statusText (${result.matchCount}/15)\n")
        }
        statusText.text = status.toString()
    }

    data class TextElement(val text: String, val rect: Rect, val blockNumber: Int = 0)
}
