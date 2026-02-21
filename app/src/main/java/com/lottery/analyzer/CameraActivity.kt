package com.lottery.analyzer

import android.content.Context
import android.graphics.*
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

    data class BlockResult(
        val blockNumber: Int, 
        val boundingRect: Rect, 
        val extractedNumbers: List<Int>, 
        val matchCount: Int, 
        val borderColor: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        selectedNumbers = intent.getIntegerArrayListExtra("selectedNumbers")?.toList() ?: emptyList()
        
        AppLogger.i("CameraActivity created with ${selectedNumbers.size} numbers")
        
        surfaceView = findViewById(R.id.surfaceView)
        cameraPreview = findViewById(R.id.cameraPreview)
        statusText = findViewById(R.id.statusText)
        backButton = findViewById(R.id.backButton)
        overlay = DrawingOverlay(this)
        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(this)
        cameraPreview.addView(overlay)
        statusText.text = "Инициализация камеры..."
        
        backButton.setOnClickListener {
            AppLogger.d("Back button clicked")
            finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            AppLogger.d("Surface created, opening camera...")
            camera = Camera.open()
            val params = camera!!.parameters
            // Устанавливаем правильный размер превью для портретной ориентации
            val previewSize = getOptimalPreviewSize(params.supportedPreviewSizes, surfaceView.width, surfaceView.height)
            if (previewSize != null) {
                params.setPreviewSize(previewSize.width, previewSize.height)
                camera!!.parameters = params
                AppLogger.d("Preview size set to ${previewSize.width}x${previewSize.height}")
            }
            // Устанавливаем ориентацию камеры для портретного режима
            camera!!.setDisplayOrientation(90)
            camera!!.setPreviewDisplay(holder)
            camera!!.setPreviewCallback(this)
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
        val targetHeight = w
        val targetWidth = h
        var bestSize: Camera.Size? = null
        var minDiff = Double.MAX_VALUE
        for (size in sizes) {
            val diff = Math.abs(size.height - targetHeight) + Math.abs(size.width - targetWidth)
            if (diff < minDiff) {
                minDiff = diff.toDouble()
                bestSize = size
            }
        }
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

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        // Защита от повторной обработки
        if (!isPreviewing.get() || !isProcessing.compareAndSet(false, true)) {
            return
        }
        
        executor.execute {
            try {
                val startTime = System.currentTimeMillis()
                val params = camera.parameters
                val width = params.previewSize.width
                val height = params.previewSize.height
                
                val image = InputImage.fromByteArray(data, width, height, 0, InputImage.IMAGE_FORMAT_NV21)
                
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val blockResults = detectAndAnalyzeBlocks(visionText, width, height)
                        mainHandler.post {
                            overlay.setBlockResults(blockResults)
                            overlay.invalidate()
                            updateStatus(blockResults)
                        }
                        
                        // Логирование производительности
                        val duration = System.currentTimeMillis() - startTime
                        lastProcessingTime = duration
                        if (duration > 500) {
                            AppLogger.w("Processing took ${duration}ms")
                        }
                    }
                    .addOnFailureListener { e ->
                        AppLogger.e("Text recognition failed", e)
                    }
                    .addOnCompleteListener {
                        isProcessing.set(false)
                    }
            } catch (e: Exception) {
                AppLogger.e("Preview frame processing error", e)
                isProcessing.set(false)
            }
        }
    }

    private fun detectAndAnalyzeBlocks(visionText: com.google.mlkit.vision.text.Text, width: Int, height: Int): List<BlockResult> {
        val results = mutableListOf<BlockResult>()
        val allElements = mutableListOf<TextElement>()
        
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val rect = element.boundingBox
                    if (rect != null) allElements.add(TextElement(element.text, rect))
                }
            }
        }
        
        if (allElements.isEmpty()) return results
        
        val midY = height / 2
        val upperElements = allElements.filter { it.rect.centerY() < midY }
        val lowerElements = allElements.filter { it.rect.centerY() >= midY }
        
        if (upperElements.isNotEmpty()) results.add(processBlock(1, upperElements, width, height))
        if (lowerElements.isNotEmpty()) results.add(processBlock(2, lowerElements, width, height))
        
        return results
    }

    private fun processBlock(blockNumber: Int, elements: List<TextElement>, screenWidth: Int, screenHeight: Int): BlockResult {
        val sortedByLines = elements.groupBy { (it.rect.top / 30).toInt() }.toSortedMap()
        val extractedNumbers = mutableListOf<Int>()
        
        for (line in sortedByLines.values) {
            val sortedByX = line.sortedBy { it.rect.left }
            for (element in sortedByX) {
                val num = element.text.toIntOrNull()
                if (num != null && num in 1..90 && !extractedNumbers.contains(num)) extractedNumbers.add(num)
            }
            if (extractedNumbers.size >= 15) break
        }
        
        val numbersInBlock = extractedNumbers.take(15)
        val matchCount = numbersInBlock.intersect(selectedNumbers.toSet()).size
        val borderColor = when {
            matchCount == 15 -> Color.GREEN
            matchCount >= 13 -> Color.YELLOW
            else -> Color.RED
        }
        
        val minX = elements.minOf { it.rect.left }
        val minY = elements.minOf { it.rect.top }
        val maxX = elements.maxOf { it.rect.right }
        val maxY = elements.maxOf { it.rect.bottom }
        val padding = 10
        val boundingRect = Rect(maxOf(minX - padding, 0), maxOf(minY - padding, 0), minOf(maxX + padding, screenWidth), minOf(maxY + padding, screenHeight))
        
        return BlockResult(blockNumber, boundingRect, numbersInBlock, matchCount, borderColor)
    }

    private fun updateStatus(results: List<BlockResult>) {
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

    data class TextElement(val text: String, val rect: Rect)

    inner class DrawingOverlay(context: Context) : android.view.View(context) {
        private var scaledBlockResults: List<BlockResult> = emptyList()
        private val borderPaint = Paint().apply { 
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
        }
        private val textPaint = Paint().apply { 
            textSize = 50f
            color = Color.WHITE
            isFakeBoldText = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        fun setBlockResults(results: List<BlockResult>) { 
            // Масштабируем прямоугольники под размер view
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            scaledBlockResults = if (viewWidth > 0 && viewHeight > 0) {
                // Превью камеры повернуто на 90 градусов, нужно масштабировать
                results.map { result ->
                    val scaledRect = Rect(
                        (result.boundingRect.left * viewWidth / 720f).toInt(),
                        (result.boundingRect.top * viewHeight / 1280f).toInt(),
                        (result.boundingRect.right * viewWidth / 720f).toInt(),
                        (result.boundingRect.bottom * viewHeight / 1280f).toInt()
                    )
                    result.copy(boundingRect = scaledRect)
                }
            } else {
                results
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (result in scaledBlockResults) {
                borderPaint.color = result.borderColor
                borderPaint.alpha = 255
                borderPaint.strokeWidth = 10f
                canvas.drawRect(result.boundingRect, borderPaint)

                val blockLabel = if (result.blockNumber == 1) "ВЕРХНИЙ" else "НИЖНИЙ"
                val infoText = "${blockLabel}: ${result.matchCount}/15"
                textPaint.color = result.borderColor
                canvas.drawText(infoText, result.boundingRect.left.toFloat() + 20, result.boundingRect.top.toFloat() - 20, textPaint)
            }
        }
    }
}
