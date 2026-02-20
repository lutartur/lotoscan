package com.lottery.analyzer

import android.content.Context
import android.graphics.*
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private var camera: Camera? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var cameraPreview: FrameLayout
    private lateinit var overlay: DrawingOverlay
    private lateinit var statusText: TextView
    private var selectedNumbers: List<Int> = emptyList()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    
    data class BlockResult(val blockNumber: Int, val boundingRect: Rect, val extractedNumbers: List<Int>, val matchCount: Int, val borderColor: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        selectedNumbers = intent.getIntegerArrayListExtra("selectedNumbers")?.toList() ?: emptyList()
        surfaceView = findViewById(R.id.surfaceView)
        cameraPreview = findViewById(R.id.cameraPreview)
        statusText = findViewById(R.id.statusText)
        overlay = DrawingOverlay(this)
        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(this)
        cameraPreview.addView(overlay)
        statusText.text = "Сканирование..."
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            camera = Camera.open()
            camera?.setPreviewDisplay(holder)
            camera?.setPreviewCallback(this)
            camera?.startPreview()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка камеры", Toast.LENGTH_SHORT).show()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            camera?.stopPreview()
            camera?.release()
            camera = null
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        executor.execute {
            try {
                val params = camera.parameters
                val width = params.previewSize.width
                val height = params.previewSize.height
                val image = InputImage.fromByteArray(data, width, height, 0, InputImage.IMAGE_FORMAT_NV21)
                textRecognizer.process(image).addOnSuccessListener { visionText ->
                    val blockResults = detectAndAnalyzeBlocks(visionText, width, height)
                    mainHandler.post {
                        overlay.setBlockResults(blockResults)
                        overlay.invalidate()
                        updateStatus(blockResults)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
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
        private var blockResults: List<BlockResult> = emptyList()
        private val borderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f }
        private val textPaint = Paint().apply { textSize = 50f; color = Color.WHITE; isFakeBoldText = true }

        fun setBlockResults(results: List<BlockResult>) { blockResults = results }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (result in blockResults) {
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

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        textRecognizer.close()
    }
}
