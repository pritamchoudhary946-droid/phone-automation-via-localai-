package com.example.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * A soft, glassy "AI orb" for the floating controller: an iris-to-violet sphere with
 * an ambient glow, a slow rotating light sweep and a gentle breathing motion.
 *
 * Drawn with plain Canvas/Shader so it costs almost nothing and needs no extra
 * animation dependency. Position (drag) is driven by the parent FrameLayout.
 */
class AgentOrbView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#C9C8FF")
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var rotation = 0f
    private var breath = 0f

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 7000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidate()
        }
    }

    private val breathe = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            breath = it.animatedValue as Float
            invalidate()
        }
    }

    private var shadersReady = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShaders(w.toFloat(), h.toFloat())
        shadersReady = true
    }

    private fun buildShaders(w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f

        glowPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.parseColor("#405B5BE8"),
                Color.parseColor("#1A5B5BE8"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0.55f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        // Off-centre highlight gives the orb a lit, spherical feel.
        orbPaint.shader = RadialGradient(
            cx - radius * 0.30f, cy - radius * 0.34f, radius * 1.42f,
            intArrayOf(
                Color.parseColor("#C7C6FF"),
                Color.parseColor("#6E6BF0"),
                Color.parseColor("#4A46D6"),
                Color.parseColor("#241F7A")
            ),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )

        sweepPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#66FFFFFF"),
                Color.TRANSPARENT,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.12f, 0.30f, 1f)
        )

        highlightPaint.shader = RadialGradient(
            cx - radius * 0.34f, cy - radius * 0.38f, radius * 0.52f,
            intArrayOf(Color.parseColor("#EAF0FF"), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!shadersReady) return

        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 2f
        // Breathing: the sphere gently grows/shrinks, the glow pulses a little more.
        val orbRadius = base * (0.72f + 0.03f * breath)
        val glowRadius = base
        glowPaint.alpha = (150 + 60 * breath).toInt()

        // 1. Ambient glow.
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 2. Sphere body.
        canvas.drawCircle(cx, cy, orbRadius, orbPaint)

        // 3. Rotating light sweep, clipped to the sphere.
        val save = canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addCircle(cx, cy, orbRadius, android.graphics.Path.Direction.CW) })
        canvas.rotate(rotation, cx, cy)
        sweepPaint.alpha = 150
        canvas.drawCircle(cx, cy, orbRadius, sweepPaint)
        canvas.restoreToCount(save)

        // 4. Top-left specular highlight.
        canvas.drawCircle(cx, cy, orbRadius, highlightPaint)

        // 5. Crisp rim.
        canvas.drawCircle(cx, cy, orbRadius, rimPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        spin.start()
        breathe.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        breathe.cancel()
        super.onDetachedFromWindow()
    }
}
