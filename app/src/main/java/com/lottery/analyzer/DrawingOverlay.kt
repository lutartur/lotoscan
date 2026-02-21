package com.lottery.analyzer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Overlay для отрисовки рамок поверх камеры.
 *
 * Система координат:
 *  - ML Kit возвращает координаты в пространстве ПОВЁРНУТОГО изображения.
 *    При rotation=90: ширина = previewHeight (высота оригинального кадра),
 *                     высота = previewWidth  (ширина оригинального кадра).
 *  - CameraActivity передаёт именно эти (уже повёрнутые) размеры через setBlockResults.
 *  - Здесь мы просто масштабируем из imageW×imageH → viewW×viewH.
 */
class DrawingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BlockResult(
        val blockNumber: Int,
        val rect: Rect,
        val numbers: List<Int>,
        val matchCount: Int,
        val borderColor: Int
    )

    // --- Параметры "Области сканирования" ---
    // Область задаётся в долях от размера View, чтобы не зависеть от разрешения.
    // Горизонтально: 5% слева, 5% справа.
    // Вертикально:   15% сверху, 55% снизу (билет занимает верхние ~45% экрана камеры).
    // Подстрой эти значения под реальное положение билета в кадре.
    private val SCAN_LEFT_FRAC   = 0.04f
    private val SCAN_RIGHT_FRAC  = 0.96f
    private val SCAN_TOP_FRAC    = 0.10f
    private val SCAN_BOTTOM_FRAC = 0.62f

    private val blockResults = mutableListOf<BlockResult>()

    // Размеры изображения, которое вернул ML Kit (уже с учётом rotation).
    // imageWidth  = previewHeight (т.к. rotation=90)
    // imageHeight = previewWidth
    private var imageWidth  = 1
    private var imageHeight = 1

    // Paint для "Области сканирования"
    private val scanPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
    }

    // Paint для подписи "ОБЛАСТЬ СКАНИРОВАНИЯ"
    private val scanLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // Paint для рамок блоков
    private val blockPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    // Paint для подписи блока
    private val labelPaint = Paint().apply {
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    // Paint для заливки рамки блока (полупрозрачная)
    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    fun setBlockResults(results: List<BlockResult>, imgWidth: Int, imgHeight: Int) {
        blockResults.clear()
        blockResults.addAll(results)
        imageWidth  = if (imgWidth  > 0) imgWidth  else 1
        imageHeight = if (imgHeight > 0) imgHeight else 1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val vw = width.toFloat()
        val vh = height.toFloat()

        // ── 1. Рисуем "Область сканирования" ──────────────────────────────
        val scanRect = RectF(
            vw * SCAN_LEFT_FRAC,
            vh * SCAN_TOP_FRAC,
            vw * SCAN_RIGHT_FRAC,
            vh * SCAN_BOTTOM_FRAC
        )
        canvas.drawRect(scanRect, scanPaint)
        canvas.drawText(
            "ОБЛАСТЬ СКАНИРОВАНИЯ",
            scanRect.centerX(),
            scanRect.top - 12f,
            scanLabelPaint
        )

        // ── 2. Рисуем рамки распознанных блоков ───────────────────────────
        if (blockResults.isEmpty()) return

        // Коэффициенты масштабирования: image → view
        val scaleX = vw / imageWidth.toFloat()
        val scaleY = vh / imageHeight.toFloat()

        for (result in blockResults) {
            val r = result.rect

            // Переводим координаты в экранные
            val left   = r.left   * scaleX
            val top    = r.top    * scaleY
            val right  = r.right  * scaleX
            val bottom = r.bottom * scaleY

            // Добавляем небольшой отступ, чтобы рамка не обрезала крайние цифры
            val pad = 18f
            val drawRect = RectF(left - pad, top - pad, right + pad, bottom + pad)

            // Полупрозрачная заливка
            fillPaint.color = Color.argb(40,
                Color.red(result.borderColor),
                Color.green(result.borderColor),
                Color.blue(result.borderColor)
            )
            canvas.drawRect(drawRect, fillPaint)

            // Рамка
            blockPaint.color = result.borderColor
            canvas.drawRect(drawRect, blockPaint)

            // Подпись
            val blockName = if (result.blockNumber == 1) "ВЕРХНИЙ" else "НИЖНИЙ"
            val statusStr = when (result.matchCount) {
                15   -> "✅ ${result.matchCount}/15"
                in 13..14 -> "⚠️ ${result.matchCount}/15"
                else -> "❌ ${result.matchCount}/15"
            }
            labelPaint.color = result.borderColor
            canvas.drawText(
                "$blockName: $statusStr",
                drawRect.left + 8f,
                drawRect.top - 8f,
                labelPaint
            )
        }
    }
}
