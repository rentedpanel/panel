package com.pro.injector

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.random.Random

class EspCanvasView(
    ctx: Context,
    private val screenWidth: Float,
    private val screenHeight: Float
) : View(ctx) {

    data class TargetEntity(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val bodyHeight: Float,
        var hp: Float = 100f,
        var dist: Float = 25f,
        var distDir: Float = 0.2f
    ) {
        val headX: Float get() = x
        val headY: Float get() = y - bodyHeight * 0.45f
        val footX: Float get() = x
        val footY: Float get() = y + bodyHeight * 0.45f
    }

    private val targets = mutableListOf<TargetEntity>()

    // Paints
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    private val skelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        color = Color.argb(200, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 16, 24, 40)
        style = Paint.Style.FILL
    }

    private val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 170)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.argb(230, 0, 255, 150)
    }

    private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        color = Color.argb(70, 0, 255, 150)
        maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.OUTER)
    }

    private val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.argb(90, 0, 210, 255)
    }

    private val hpBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 25, 25, 30)
    }

    private val hpFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 0, 230, 90)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Ring Animation Data
    private data class RingAnim(var t: Float = 0f, val speed: Float = 0.032f)
    private val ringAnims = mutableMapOf<Int, RingAnim>()

    private var pulse = 0f
    private var pulseDir = 1f

    private val originX get() = 0f
    private val originY get() = screenHeight * 0.5f

    init {
        // Required for BlurMaskFilter and smooth glow rendering
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        initSimulatedTargets()
    }

    private fun initSimulatedTargets() {
        val rand = Random(System.currentTimeMillis())
        val count = 3
        val bodyHeight = (screenHeight * 0.22f).coerceIn(160f, 280f)

        for (i in 0 until count) {
            val posX = screenWidth * (0.35f + i * 0.22f)
            val posY = screenHeight * (0.30f + (i % 2) * 0.25f)
            val vx = (rand.nextFloat() * 3.5f + 1.5f) * (if (rand.nextBoolean()) 1f else -1f)
            val vy = (rand.nextFloat() * 3.0f + 1.2f) * (if (rand.nextBoolean()) 1f else -1f)
            val initialDist = 15f + i * 20f

            targets.add(
                TargetEntity(
                    x = posX,
                    y = posY,
                    vx = vx,
                    vy = vy,
                    bodyHeight = bodyHeight,
                    hp = 100f - i * 15f,
                    dist = initialDist
                )
            )
            ringAnims[i] = RingAnim(t = (i * 0.33f) % 1f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Pulse state update
        pulse += pulseDir * 0.5f
        if (pulse > 5f) {
            pulse = 5f
            pulseDir = -1f
        } else if (pulse < 0f) {
            pulse = 0f
            pulseDir = 1f
        }

        // Draw top HUD status badge
        drawTopBadge(canvas)

        // Update physics & draw each target
        for (i in targets.indices) {
            val target = targets[i]
            updateTargetPhysics(target)
            drawTarget(canvas, target, i)
        }

        // 60 FPS continuous animation loop
        postInvalidateOnAnimation()
    }

    private fun updateTargetPhysics(t: TargetEntity) {
        t.x += t.vx
        t.y += t.vy

        val halfW = t.bodyHeight * 0.25f
        val halfH = t.bodyHeight * 0.5f

        // Screen boundary bouncing
        if (t.x - halfW < 40f) {
            t.x = 40f + halfW
            t.vx = -t.vx
        } else if (t.x + halfW > screenWidth - 40f) {
            t.x = screenWidth - 40f - halfW
            t.vx = -t.vx
        }

        if (t.y - halfH < 180f) {
            t.y = 180f + halfH
            t.vy = -t.vy
        } else if (t.y + halfH > screenHeight - 120f) {
            t.y = screenHeight - 120f - halfH
            t.vy = -t.vy
        }

        // Dynamic distance oscillation to showcase color changes
        t.dist += t.distDir
        if (t.dist > 65f) {
            t.dist = 65f
            t.distDir = -0.18f
        } else if (t.dist < 14f) {
            t.dist = 14f
            t.distDir = 0.18f
        }
    }

    private fun drawTopBadge(canvas: Canvas) {
        val badgeW = 420f
        val badgeH = 50f
        val left = (screenWidth - badgeW) * 0.5f
        val top = 80f
        val right = left + badgeW
        val bottom = top + badgeH

        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, badgePaint)
        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, badgeBorder)

        val badgeText = "⚡ ESP CANVAS ENGINE • ACTIVE (60 FPS)"
        val textWidth = textPaint.measureText(badgeText)
        val textX = left + (badgeW - textWidth) * 0.5f
        val textY = top + 32f

        canvas.drawText(badgeText, textX, textY, textPaint)
    }

    private fun drawTarget(canvas: Canvas, e: TargetEntity, idx: Int) {
        val anim = ringAnims[idx] ?: RingAnim().also { ringAnims[idx] = it }

        // Distance-based dynamic color coding
        val boxColor = when {
            e.dist < 20f -> Color.argb(220, 255, 59, 48)   // Red (<20m)
            e.dist < 50f -> Color.argb(220, 255, 149, 0)  // Orange (20-50m)
            else -> Color.argb(220, 255, 214, 10)         // Yellow (>50m)
        }

        val bodyH = e.footY - e.headY
        val boxW = bodyH * 0.35f

        // Tracer line: screen origin -> target head
        canvas.drawLine(originX, originY, e.headX, e.headY, tracerPaint)

        // Health (HP) bar above head
        val hpBarW = boxW * 2f
        val hpBarH = 7f
        val hpTop = e.headY - 18f
        val hpBottom = hpTop + hpBarH
        val hpLeft = e.headX - boxW
        val hpRight = e.headX + boxW

        canvas.drawRect(hpLeft, hpTop, hpRight, hpBottom, hpBgPaint)
        val fillWidth = hpBarW * (e.hp / 100f).coerceIn(0f, 1f)
        hpFillPaint.color = when {
            e.hp > 50f -> Color.argb(230, 52, 199, 89)
            e.hp > 25f -> Color.argb(230, 255, 149, 0)
            else -> Color.argb(230, 255, 59, 48)
        }
        canvas.drawRect(hpLeft, hpTop, hpLeft + fillWidth, hpBottom, hpFillPaint)

        // Bounding box & stylized corner brackets
        boxPaint.color = Color.argb(70, Color.red(boxColor), Color.green(boxColor), Color.blue(boxColor))
        canvas.drawRect(e.headX - boxW, e.headY, e.headX + boxW, e.footY, boxPaint)
        drawCorners(canvas, e.headX - boxW, e.headY, e.headX + boxW, e.footY, boxColor)

        // Dashed Skeleton spine (Head to Foot)
        canvas.drawLine(e.headX, e.headY, e.footX, e.footY, skelPaint)

        // Distance Tag
        val distStr = "${e.dist.toInt()}m"
        textPaint.color = boxColor
        canvas.drawText(distStr, e.headX + boxW + 8f, e.headY + 16f, textPaint)

        // Animated traveling ring
        val t = anim.t
        val rx = lerp(originX, e.headX, t)
        val ry = lerp(originY, e.headY, t)
        val baseRadius = lerp(30f, 12f, t) + pulse * (1f - t)

        // Glowing blur ring
        ringGlowPaint.color = Color.argb((70 * (1f - t * 0.4f)).toInt(), 0, 255, 150)
        canvas.drawCircle(rx, ry, baseRadius + 6f, ringGlowPaint)

        // Inner solid ring
        ringPaint.color = Color.argb(lerp(120f, 255f, t).toInt(), 0, 255, 150)
        canvas.drawCircle(rx, ry, baseRadius, ringPaint)

        // Impact flash dot at target head upon arrival
        if (t > 0.88f) {
            val alpha = (((t - 0.88f) / 0.12f) * 255).toInt().coerceIn(0, 255)
            dotPaint.color = Color.argb(alpha, 255, 59, 48)
            canvas.drawCircle(e.headX, e.headY, 6f + pulse, dotPaint)
        }

        // Advance animation time
        anim.t += anim.speed
        if (anim.t > 1f) {
            anim.t = 0f
        }
    }

    private fun drawCorners(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        cornerPaint.color = color
        val len = (r - l) * 0.28f

        // Top-left
        canvas.drawLine(l, t, l + len, t, cornerPaint)
        canvas.drawLine(l, t, l, t + len, cornerPaint)

        // Top-right
        canvas.drawLine(r - len, t, r, t, cornerPaint)
        canvas.drawLine(r, t, r, t + len, cornerPaint)

        // Bottom-left
        canvas.drawLine(l, b - len, l, b, cornerPaint)
        canvas.drawLine(l, b, l + len, b, cornerPaint)

        // Bottom-right
        canvas.drawLine(r - len, b, r, b, cornerPaint)
        canvas.drawLine(r, b - len, r, b, cornerPaint)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
