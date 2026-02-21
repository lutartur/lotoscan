package com.lottery.analyzer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * DrawingOverlay — рисует:
 *  1. "Область сканирования" (пунктирная рамка) — охватывает оба блока билета целиком.
 *  2. Две рамки: ВЕРХНИЙ (верхняя половина области) и НИЖНИЙ (нижняя половина).
 *     Рамки всегда ВНУТРИ области сканирования и по ширине совпадают с ней.
 *
 * Координатная система:
 *  - ML Kit с rotation=90 возвращает bbox в пространстве (imageWidth × imageHeight),
 *    где imageWidth = previewHeight камеры, imageHeight = previewWidth камеры.
 *  - Camera API рисует preview fit-center внутри SurfaceView.
 *    Если aspect ratio не совпадает → letterbox (чёрные полосы).
 *    computeViewport() вычисляет реальный прямоугольник изображения.
 */
class DrawingOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BlockResult(
        val blockNumber: Int,
        val rect: Rect,          // bbox в пространстве ML Kit (используется только для Y-позиции)
        val numbers: List<Int>,
        val matchCount: Int,
        val borderColor: Int
    )

    private val blockResults = mutableListOf<BlockResult>()
    private var imageWidth  = 720
    private var imageHeight = 1280
    private var cachedViewport: RectF? = null
    private var lastViewW = 0f
    private var lastViewH = 0f

    // ── Область сканирования (доли от viewport изображения) ───────────────
    // Горизонтально: почти вся ширина (оставляем малый отступ для красоты)
    // Вертикально: подстроено под реальное положение билета в кадре
    // При необходимости подправь SCAN_T и SCAN_B
    private val SCAN_L = 0.02f   // 2% от левого края viewport
    private val SCAN_R = 0.98f   // до 98% — почти вся ширина
    private val SCAN_T = 0.12f   // верхний край области сканирования
    private val SCAN_B = 0.62f   // нижний край области сканирования

    // ── Paint ─────────────────────────────────────────────────────────────

    private val scanPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
        isAntiAlias = true
    }

    private val scanLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    // Разделительная линия между блоками
    private val dividerPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    private val blockPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        isAntiAlias = true
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(160, 0, 0, 0)
    }

    private val labelPaint = Paint().apply {
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // ── Публичные методы ──────────────────────────────────────────────────

    fun setImageSize(imgWidth: Int, imgHeight: Int) {
        imageWidth  = if (imgWidth  > 0) imgWidth  else 720
        imageHeight = if (imgHeight > 0) imgHeight else 1280
        cachedViewport = null
        invalidate()
    }

    fun setBlockResults(results: List<BlockResult>) {
        blockResults.clear()
        blockResults.addAll(results)
    }

    // ── Viewport (letterbox compensation) ────────────────────────────────

    private fun computeViewport(vw: Float, vh: Float): RectF {
        if (cachedViewport != null && lastViewW == vw && lastViewH == vh) return cachedViewport!!

        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
        val viewAspect  = vw / vh

        val vp = if (imageAspect > viewAspect) {
            // Letterbox сверху/снизу
            val scaledH = vw / imageAspect
            val offsetY = (vh - scaledH) / 2f
            RectF(0f, offsetY, vw, offsetY + scaledH)
        } else {
            // Pillarbox по бокам
            val scaledW = vh * imageAspect
            val offsetX = (vw - scaledW) / 2f
            RectF(offsetX, 0f, offsetX + scaledW, vh)
        }

        android.util.Log.d("DrawingOverlay",
            "Viewport: image=${imageWidth}x${imageHeight} aspect=${"%.3f".format(imageAspect)}" +
            " view=${vw.toInt()}x${vh.toInt()} aspect=${"%.3f".format(viewAspect)}" +
            " vp=$vp"
        )

        cachedViewport = vp; lastViewW = vw; lastViewH = vh
        return vp
    }

    // ── onDraw ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw == 0f || vh == 0f) return

        val vp  = computeViewport(vw, vh)
        val vpW = vp.width()
        val vpH = vp.height()

        // ── 1. "Область сканирования" ─────────────────────────────────────
        val scanRect = RectF(
            vp.left + vpW * SCAN_L,
            vp.top  + vpH * SCAN_T,
            vp.left + vpW * SCAN_R,
            vp.top  + vpH * SCAN_B
        )
        canvas.drawRect(scanRect, scanPaint)
        canvas.drawText("ОБЛАСТЬ СКАНИРОВАНИЯ", scanRect.centerX(), scanRect.top - 10f, scanLabelPaint)

        // Середина области сканирования — граница между блоками
        val midScanY = (scanRect.top + scanRect.bottom) / 2f
        canvas.drawLine(scanRect.left, midScanY, scanRect.right, midScanY, dividerPaint)

        // ── 2. Рамки блоков ───────────────────────────────────────────────
        if (blockResults.isEmpty()) return

        // Масштаб ML Kit → viewport
        val scaleX = vpW / imageWidth.toFloat()
        val scaleY = vpH / imageHeight.toFloat()

        for (result in blockResults) {
            val r = result.rect

            // Y-координаты из ML Kit (для позиционирования по вертикали внутри блока)
            val rawTop    = vp.top + r.top    * scaleY
            val rawBottom = vp.top + r.bottom * scaleY

            // ── ШИРИНА: всегда равна ширине области сканирования ──────────
            val drawLeft  = scanRect.left
            val drawRight = scanRect.right

            // ── ВЫСОТА: ограничена соответствующей половиной scanRect ─────
            // Блок 1 → верхняя половина scanRect
            // Блок 2 → нижняя половина scanRect
            val halfTop    = scanRect.top
            val halfBottom = midScanY
            val drawTop: Float
            val drawBottom: Float

            if (result.blockNumber == 1) {
                // Верхний блок: clamp в [scanRect.top .. midScanY]
                drawTop    = rawTop.coerceIn(scanRect.top, midScanY)
                drawBottom = rawBottom.coerceIn(scanRect.top, midScanY)
            } else {
                // Нижний блок: clamp в [midScanY .. scanRect.bottom]
                drawTop    = rawTop.coerceIn(midScanY, scanRect.bottom)
                drawBottom = rawBottom.coerceIn(midScanY, scanRect.bottom)
            }

            // Если блок не распознан вообще (empty rect) — используем всю половину
            val finalTop    = if (drawTop >= drawBottom) (if (result.blockNumber == 1) scanRect.top  else midScanY)    else drawTop
            val finalBottom = if (drawTop >= drawBottom) (if (result.blockNumber == 1) midScanY      else scanRect.bottom) else drawBottom

            val pad = 4f
            val drawRect = RectF(drawLeft - pad, finalTop - pad, drawRight + pad, finalBottom + pad)
                .also { it.intersect(scanRect) } // никогда не выходим за scanRect

            // Заливка
            fillPaint.color = Color.argb(50,
                Color.red(result.borderColor),
                Color.green(result.borderColor),
                Color.blue(result.borderColor)
            )
            canvas.drawRect(drawRect, fillPaint)

            // Рамка
            blockPaint.color = result.borderColor
            canvas.drawRect(drawRect, blockPaint)

            // Подпись с тёмным фоном для читаемости
            val name = if (result.blockNumber == 1) "ВЕРХНИЙ" else "НИЖНИЙ"
            val stat = when {
                result.matchCount == 15 -> "✅ ${result.matchCount}/15"
                result.matchCount >= 13 -> "⚠️ ${result.matchCount}/15"
                else                    -> "❌ ${result.matchCount}/15"
            }
            val labelText = "$name: $stat"
            labelPaint.color = result.borderColor
            val labelX = drawRect.left + 14f
            val labelY = drawRect.top + 44f  // внутри рамки сверху

            // Фон под текстом
            val textBounds = Rect()
            labelPaint.getTextBounds(labelText, 0, labelText.length, textBounds)
            canvas.drawRect(
                labelX - 4f, labelY + textBounds.top - 4f,
                labelX + textBounds.width() + 8f, labelY + textBounds.bottom + 4f,
                labelBgPaint
            )
            canvas.drawText(labelText, labelX, labelY, labelPaint)
        }
    }
}

// Расширение для RectF.intersect без изменения если нет пересечения
private fun RectF.intersect(other: RectF): RectF {
    if (this.left < other.right && this.right > other.left &&
        this.top < other.bottom && this.bottom > other.top) {
        this.left   = maxOf(this.left,   other.left)
        this.top    = maxOf(this.top,    other.top)
        this.right  = minOf(this.right,  other.right)
        this.bottom = minOf(this.bottom, other.bottom)
    }
    return this
}
