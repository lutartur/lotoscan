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

        backButton.setOnClickListener { finish() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            camera = Camera.open()
            val params = camera!!.parameters

            val bestSize = getOptimalPreviewSize(params.supportedPreviewSizes, surfaceView.width, surfaceView.height)
            if (bestSize != null) {
                params.setPreviewSize(bestSize.width, bestSize.height)
                camera!!.parameters = params
                previewWidth  = bestSize.width
                previewHeight = bestSize.height
                AppLogger.i("Preview size: ${previewWidth}x${previewHeight}")
            }

            camera!!.setDisplayOrientation(90)
            camera!!.setPreviewDisplay(holder)

            // imageWidth=previewHeight, imageHeight=previewWidth (после rotation=90)
            overlay.setImageSize(previewHeight, previewWidth)

            val bufferSize = previewWidth * previewHeight * 3 / 2
            camera!!.addCallbackBuffer(ByteArray(bufferSize))
            camera!!.setPreviewCallbackWithBuffer(this)

            camera!!.startPreview()
            isPreviewing.set(true)
            statusText.text = "Сканирование..."
        } catch (e: Exception) {
            AppLogger.e("Camera init failed", e)
            mainHandler.post {
                Toast.makeText(this, "Ошибка камеры: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun getOptimalPreviewSize(sizes: List<Camera.Size>, viewW: Int, viewH: Int): Camera.Size? {
        if (sizes.isEmpty()) return null
        val targetRatio = viewH.toDouble() / viewW.toDouble()
        val TOLERANCE = 0.15
        val candidates = sizes.filter { Math.abs(it.width.toDouble() / it.height - targetRatio) < TOLERANCE }
        return (if (candidates.isNotEmpty()) candidates else sizes).maxByOrNull { it.width * it.height }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (holder.surface == null) return
        try { camera?.stopPreview(); isPreviewing.set(false) } catch (e: Exception) { }
        try { camera?.setPreviewDisplay(holder); camera?.startPreview(); isPreviewing.set(true) } catch (e: Exception) { }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            if (isPreviewing.get()) { camera?.stopPreview(); isPreviewing.set(false) }
            camera?.setPreviewCallback(null)
            camera?.setPreviewCallbackWithBuffer(null)
            camera?.release()
            camera = null
        } catch (e: Exception) { }
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
            camera?.release(); camera = null
            textRecognizer.close(); executor.shutdown()
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
                val image = InputImage.fromByteArray(frameData, pw, ph, 90, InputImage.IMAGE_FORMAT_NV21)
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
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

        // Собираем все числа от 1 до 90
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

        // ── КЛЮЧЕВОЕ ИЗМЕНЕНИЕ ─────────────────────────────────────────────
        // Билет состоит из 2 блоков по 3 строки (итого 6 строк).
        // Делим все числа пополам по Y: верхние 3 строки = блок 1, нижние 3 = блок 2.
        // Это надёжнее поиска "максимального разрыва", который ошибался.
        //
        // Находим диапазон Y всех чисел и делим его пополам.
        val minY = allNumbers.minOf { it.rect.centerY() }
        val maxY = allNumbers.maxOf { it.rect.centerY() }
        val midY = (minY + maxY) / 2

        val upper = allNumbers.filter { it.rect.centerY() <= midY }
        val lower = allNumbers.filter { it.rect.centerY() >  midY }

        AppLogger.d("Total: ${allNumbers.size}, minY=$minY, maxY=$maxY, midY=$midY, upper=${upper.size}, lower=${lower.size}")

        if (upper.isNotEmpty()) results.add(buildResult(1, upper))
        if (lower.isNotEmpty()) results.add(buildResult(2, lower))

        return results
    }

    private fun buildResult(blockNumber: Int, elements: List<TextElement>): DrawingOverlay.BlockResult {
        val numbers    = elements.mapNotNull { it.text.toIntOrNull() }.distinct().take(15)
        val matchCount = numbers.intersect(selectedNumbers.toSet()).size
        val color = when {
            matchCount == 15 -> Color.GREEN
            matchCount >= 13 -> Color.YELLOW
            else             -> Color.RED
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
