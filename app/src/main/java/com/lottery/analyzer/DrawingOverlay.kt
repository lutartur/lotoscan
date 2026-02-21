package com.lottery.analyzer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Overlay для отрисовки рамок поверх камеры.
 *
 * ПРОБЛЕМА, которую решает этот класс:
 *
 * OnePlus 7: экран 1080×2340 (19.5:9).
 * Camera preview (например 1280×720, 16:9) при setDisplayOrientation(90)
 * отображается в портрете как 720×1280 (16:9).
 *
 * SurfaceView имеет размер всего cameraPreview (≈1080×2100 за вычетом панели).
 * 16:9 не совпадает с 19.5:9 → Android рисует preview с letterbox:
 *   scaledH = 1080 / (720/1280) = 1920px
 *   offsetY = (2100 - 1920) / 2 = 90px  ← чёрные полосы сверху/снизу
 *
 * Координаты из ML Kit — в пространстве 720×1280.
 * Без учёта letterbox они масштабировались бы на всю высоту View (2100px),
 * и рамки были бы смещены и неправильно растянуты.
 *
 * Решение: вычислить реальный viewport изображения внутри View,
 * масштабировать координаты именно в него.
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

    private val blockResults = mutableListOf<BlockResult>()

    // Размеры изображения в пространстве ML Kit после rotation=90:
    //   imageWidth  = previewHeight камеры (напр. 720)
    //   imageHeight = previewWidth  камеры (напр. 1280)
    private var imageWidth  = 720
    private var imageHeight = 1280

    // Кешируем viewport, чтобы не пересчитывать каждый кадр
    private var cachedViewport: RectF? = null
    private var lastViewW = 0f
    private var lastViewH = 0f

    // ── Paint-объекты ─────────────────────────────────────────────────────

    private val scanPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
    }

    private val scanLabelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val blockPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val labelPaint = Paint().apply {
        textSize = 42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    // ── Публичные методы ──────────────────────────────────────────────────

    /**
     * Вызывается из CameraActivity сразу после открытия камеры,
     * когда известен реальный preview size.
     *
     * @param imgWidth  = previewHeight камеры (т.к. rotation=90)
     * @param imgHeight = previewWidth  камеры
     */
    fun setImageSize(imgWidth: Int, imgHeight: Int) {
        imageWidth  = if (imgWidth  > 0) imgWidth  else 720
        imageHeight = if (imgHeight > 0) imgHeight else 1280
        cachedViewport = null // сбрасываем кеш
        invalidate()
    }

    /**
     * Обновляет список результатов распознавания.
     * Больше не принимает imgWidth/imgHeight — они задаются через setImageSize().
     */
    fun setBlockResults(results: List<BlockResult>) {
        blockResults.clear()
        blockResults.addAll(results)
    }

    // ── Расчёт viewport ───────────────────────────────────────────────────

    /**
     * Вычисляет прямоугольник внутри View, который реально занимает изображение камеры.
     *
     * Camera API с setDisplayOrientation(90) рисует preview в режиме "fit-center"
     * (сохраняет пропорции, центрирует). Если aspect ratio экрана и камеры не совпадают —
     * появляются чёрные полосы.
     */
    private fun computeViewport(vw: Float, vh: Float): RectF {
        if (cachedViewport != null && lastViewW == vw && lastViewH == vh) {
            return cachedViewport!!
        }

        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()
        val viewAspect  = vw / vh

        val viewport = if (imageAspect > viewAspect) {
            // Изображение "шире" чем View → letterbox сверху/снизу
            val scaledH = vw / imageAspect
            val offsetY = (vh - scaledH) / 2f
            RectF(0f, offsetY, vw, offsetY + scaledH)
        } else {
            // Изображение "уже" чем View → pillarbox по бокам
            val scaledW = vh * imageAspect
            val offsetX = (vw - scaledW) / 2f
            RectF(offsetX, 0f, offsetX + scaledW, vh)
        }

        android.util.Log.d("DrawingOverlay",
            "Viewport computed: image=${imageWidth}x${imageHeight} " +
            "(aspect ${"%.3f".format(imageAspect)}), " +
            "view=${vw.toInt()}x${vh.toInt()} " +
            "(aspect ${"%.3f".format(viewAspect)}), " +
            "viewport=$viewport"
        )

        cachedViewport = viewport
        lastViewW = vw
        lastViewH = vh
        return viewport
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

        // ── "Область сканирования" ────────────────────────────────────────
        // Доли от реального viewport изображения.
        // Для «Русского лото»: билет горизонтально почти на всю ширину,
        // вертикально занимает примерно верхние 55% изображения.
        // ПОДСТРОЙ под своё расположение телефона при съёмке.
        val SCAN_L = 0.03f
        val SCAN_R = 0.97f
        val SCAN_T = 0.15f
        val SCAN_B = 0.65f

        val scanRect = RectF(
            vp.left + vpW * SCAN_L,
            vp.top  + vpH * SCAN_T,
            vp.left + vpW * SCAN_R,
            vp.top  + vpH * SCAN_B
        )
        canvas.drawRect(scanRect, scanPaint)
        canvas.drawText(
            "ОБЛАСТЬ СКАНИРОВАНИЯ",
            scanRect.centerX(),
            scanRect.top - 14f,
            scanLabelPaint
        )

        // ── Рамки блоков ──────────────────────────────────────────────────
        if (blockResults.isEmpty()) return

        // Масштаб: пространство ML Kit (imageWidth × imageHeight) → viewport
        val scaleX = vpW / imageWidth.toFloat()
        val scaleY = vpH / imageHeight.toFloat()

        for (result in blockResults) {
            val r = result.rect

            // Координаты в пространстве экрана с учётом смещения viewport
            val left   = vp.left + r.left   * scaleX
            val top    = vp.top  + r.top    * scaleY
            val right  = vp.left + r.right  * scaleX
            val bottom = vp.top  + r.bottom * scaleY

            val pad = 22f
            val drawRect = RectF(left - pad, top - pad, right + pad, bottom + pad)

            // Полупрозрачная заливка
            fillPaint.color = Color.argb(
                45,
                Color.red(result.borderColor),
                Color.green(result.borderColor),
                Color.blue(result.borderColor)
            )
            canvas.drawRect(drawRect, fillPaint)

            // Рамка
            blockPaint.color = result.borderColor
            canvas.drawRect(drawRect, blockPaint)

            // Подпись
            val name = if (result.blockNumber == 1) "ВЕРХНИЙ" else "НИЖНИЙ"
            val stat = when {
                result.matchCount == 15   -> "✅ ${result.matchCount}/15"
                result.matchCount >= 13   -> "⚠️ ${result.matchCount}/15"
                else                      -> "❌ ${result.matchCount}/15"
            }
            labelPaint.color = result.borderColor
            canvas.drawText(
                "$name: $stat",
                drawRect.left + 10f,
                drawRect.top  - 10f,
                labelPaint
            )
        }
    }
}
