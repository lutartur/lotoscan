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

    // Реальный размер preview (в ландшафтной ориентации как возвращает Camera API)
    private var previewWidth  = 1280
    private var previewHeight = 720

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        selectedNumbers = intent.getIntegerArrayListExtra("selectedNumbers")?.toList() ?: emptyList()

        AppLogger.i("CameraActivity created with ${selectedNumbers.size} numbers")

        surfaceView   = findViewById(R.id.surfaceView)
        cameraPreview = findViewById(R.id.cameraPreview)
        statusText    = findViewById(R.id.statusText)
        backButton    = findViewById(R.id.backButton)
        overlay       = findViewById(R.id.drawingOverlay)
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

            // Выбираем оптимальный preview size
            val bestSize = getOptimalPreviewSize(
                params.supportedPreviewSizes,
                surfaceView.width,
                surfaceView.height
            )
            if (bestSize != null) {
                params.setPreviewSize(bestSize.width, bestSize.height)
                camera!!.parameters = params
                previewWidth  = bestSize.width
                previewHeight = bestSize.height
                AppLogger.i("Preview size: ${previewWidth}x${previewHeight}")
            }

            // setDisplayOrientation(90) — портретный режим
            camera!!.setDisplayOrientation(90)
            camera!!.setPreviewDisplay(holder)

            // После того как знаем preview size — сообщаем overlay
            // В портретном режиме (rotation=90): imageWidth=previewHeight, imageHeight=previewWidth
            overlay.setImageSize(previewHeight, previewWidth)

            // Buffer callback
            val bufferSize = previewWidth * previewHeight * 3 / 2
            camera!!.addCallbackBuffer(ByteArray(bufferSize))
            camera!!.setPreviewCallbackWithBuffer(this)

            camera!!.startPreview()
            isPreviewing.set(true)
            statusText.text = "Сканирование..."
            AppLogger.i("Camera started")
        } catch (e: Exception) {
            AppLogger.e("Camera initialization failed", e)
            mainHandler.post {
                Toast.makeText(this, "Ошибка камеры: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /**
     * Ищем preview size с соотношением сторон максимально близким к экрану,
     * и максимально возможного разрешения.
     *
     * Camera sizes приходят в ландшафте (width > height).
     * SurfaceView в портрете: width < height.
     * Целевое соотношение для камеры = surfaceView.height / surfaceView.width
     * (переворачиваем, т.к. камера ландшафтная).
     */
    private fun getOptimalPreviewSize(sizes: List<Camera.Size>, viewW: Int, viewH: Int): Camera.Size? {
        if (sizes.isEmpty()) return null

        // Целевой aspect ratio камеры (ландшафт) = высота экрана / ширина экрана
        val targetRatio = viewH.toDouble() / viewW.toDouble()
        val RATIO_TOLERANCE = 0.1

        // Фильтруем размеры с подходящим aspect ratio
        val candidates = sizes.filter { size ->
            val ratio = size.width.toDouble() / size.height.toDouble()
            Math.abs(ratio - targetRatio) < RATIO_TOLERANCE
        }

        // Берём самый большой из подходящих (лучше для OCR)
        val best = (if (candidates.isNotEmpty()) candidates else sizes)
            .maxByOrNull { it.width * it.height }

        AppLogger.i("Optimal preview: ${best?.width}x${best?.height}, targetRatio=${"%.3f".format(targetRatio)}")
        return best
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        AppLogger.d("Surface changed: ${width}x${height}")
        if (holder.surface == null) return
        try { camera?.stopPreview(); isPreviewing.set(false) } catch (e: Exception) { }
        try {
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
            isPreviewing.set(true)
        } catch (e: Exception) {
            AppLogger.e("Error restarting preview", e)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        AppLogger.d("Surface destroyed")
        try {
            if (isPreviewing.get()) { camera?.stopPreview(); isPreviewing.set(false) }
            camera?.setPreviewCallback(null)
            camera?.setPreviewCallbackWithBuffer(null)
            camera?.release()
            camera = null
        } catch (e: Exception) {
            AppLogger.e("Error releasing camera", e)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isPreviewing.get()) { camera?.stopPreview(); isPreviewing.set(false) }
    }

    override fun onResume() {
        super.onResume()
        if (camera != null && surfaceHolder.surface != null) {
            try { camera?.startPreview(); isPreviewing.set(true) } catch (e: Exception) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isPreviewing.get()) camera?.stopPreview()
            camera?.release()
            camera = null
            textRecognizer.close()
            executor.shutdown()
        } catch (e: Exception) { }
    }

    private val processing = AtomicBoolean(false)
    private var lastFrameTime = 0L

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        camera.addCallbackBuffer(data)

        val currentTime = System.currentTimeMillis()
        if (processing.get() || (currentTime - lastFrameTime) < 500) return

        processing.set(true)
        lastFrameTime = currentTime

        val frameData = data.copyOf()
        val pw = previewWidth
        val ph = previewHeight

        executor.execute {
            try {
                // Передаём кадр в ML Kit.
                // rotation=90: ML Kit повернёт изображение, bbox будут в пространстве ph×pw
                // (т.е. imageWidth=ph, imageHeight=pw в портрете).
                val image = InputImage.fromByteArray(
                    frameData, pw, ph, 90, InputImage.IMAGE_FORMAT_NV21
                )

                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        // ML Kit bbox координаты: x∈[0..ph], y∈[0..pw]
                        // Передаём imageWidth=ph, imageHeight=pw
                        val results = detectAndAnalyzeBlocks(visionText)
                        mainHandler.post {
                            overlay.setBlockResults(results)
                            overlay.invalidate()
                            updateStatus(results)
                        }
                    }
                    .addOnFailureListener { e -> AppLogger.e("OCR failed", e) }
                    .addOnCompleteListener { processing.set(false) }
            } catch (e: Exception) {
                AppLogger.e("Frame processing error", e)
                processing.set(false)
            }
        }
    }

    private fun detectAndAnalyzeBlocks(visionText: com.google.mlkit.vision.text.Text): List<DrawingOverlay.BlockResult> {
        val results = mutableListOf<DrawingOverlay.BlockResult>()
        val allNumbers = mutableListOf<TextElement>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val rect = element.boundingBox ?: continue
                    val rawText = element.text ?: continue

                    if (rawText.length < 1 || rawText.length > 2) continue

                    val elementWidth  = rect.right  - rect.left
                    val elementHeight = rect.bottom - rect.top
                    if (elementWidth > 200 || elementHeight > 100) continue

                    val num = rawText.toIntOrNull() ?: continue
                    if (num !in 1..90) continue

                    allNumbers.add(TextElement(rawText, rect))
                }
            }
        }

        if (allNumbers.isEmpty()) {
            AppLogger.d("No numbers detected")
            return results
        }

        // Разделяем на верхний/нижний блок по наибольшему разрыву по Y
        val sortedByY = allNumbers.sortedBy { it.rect.centerY() }

        var maxGap = 0
        var gapIndex = -1
        for (i in 1 until sortedByY.size) {
            val gap = sortedByY[i].rect.centerY() - sortedByY[i - 1].rect.centerY()
            if (gap > maxGap) { maxGap = gap; gapIndex = i }
        }

        val boundaryY = if (gapIndex > 0)
            (sortedByY[gapIndex - 1].rect.centerY() + sortedByY[gapIndex].rect.centerY()) / 2
        else
            sortedByY[sortedByY.size / 2].rect.centerY()

        val upper = sortedByY.filter { it.rect.centerY() <  boundaryY }
        val lower = sortedByY.filter { it.rect.centerY() >= boundaryY }

        AppLogger.d("Total: ${allNumbers.size}, gap=$maxGap@$gapIndex, boundaryY=$boundaryY, upper=${upper.size}, lower=${lower.size}")

        if (upper.isNotEmpty()) results.add(buildResult(1, upper))
        if (lower.isNotEmpty()) results.add(buildResult(2, lower))

        return results
    }

    private fun buildResult(blockNumber: Int, elements: List<TextElement>): DrawingOverlay.BlockResult {
        val numbers    = elements.mapNotNull { it.text.toIntOrNull() }.distinct().take(15)
        val matchCount = numbers.intersect(selectedNumbers.toSet()).size
        val color = when {
            matchCount == 15   -> Color.GREEN
            matchCount >= 13   -> Color.YELLOW
            else               -> Color.RED
        }
        val minX = elements.minOf { it.rect.left   }
        val minY = elements.minOf { it.rect.top    }
        val maxX = elements.maxOf { it.rect.right  }
        val maxY = elements.maxOf { it.rect.bottom }

        AppLogger.d("Block $blockNumber rect=[$minX,$minY,$maxX,$maxY] matches=$matchCount numbers=$numbers")

        return DrawingOverlay.BlockResult(blockNumber, Rect(minX, minY, maxX, maxY), numbers, matchCount, color)
    }

    private fun updateStatus(results: List<DrawingOverlay.BlockResult>) {
        val sb = StringBuilder()
        for (r in results) {
            val name   = if (r.blockNumber == 1) "ВЕРХНИЙ" else "НИЖНИЙ"
            val status = when (r.borderColor) {
                Color.GREEN  -> "✅ ПОЛНОЕ СОВПАДЕНИЕ"
                Color.YELLOW -> "⚠️ ЧАСТИЧНОЕ"
                else         -> "❌ НЕСОВПАДЕНИЕ"
            }
            sb.append("БЛОК ${r.blockNumber} ($name):\n$status (${r.matchCount}/15)\n")
        }
        statusText.text = sb.toString()
    }

    data class TextElement(val text: String, val rect: Rect)
}
