package com.faceauth.app.ml

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.face.Face

/**
 * Transparent overlay that draws face bounding boxes on top of the camera preview.
 *
 * ML Kit returns bounding boxes in the ROTATED (upright) image coordinate
 * space — i.e. after the sensor frame has been conceptually rotated by
 * rotationDegrees. CameraX PreviewView shows the image in that same
 * upright/display orientation. previewW/previewH passed into setData()
 * are the raw SENSOR frame dimensions (proxy.width/proxy.height), so for
 * a 90°/270° rotation the box's effective width/height axes are swapped
 * relative to previewW/previewH — we account for that explicitly using
 * the rotation value, rather than assuming a fixed swap.
 */
class FaceOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var faces      : List<Face> = emptyList()
    private var previewW   : Int = 1
    private var previewH   : Int = 1
    private var label      : String  = ""
    private var classB     : Boolean = false
    private var rotationDeg: Int = 0

    private val boxPaint = Paint().apply {
        style       = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        textSize      = 38f
        isFakeBoldText = true
        isAntiAlias   = true
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
    }

    fun setData(
        faces      : List<Face>,
        previewW   : Int,
        previewH   : Int,
        label      : String,
        classB     : Boolean,
        rotationDeg: Int = 0
    ) {
        this.faces       = faces
        this.previewW    = previewW
        this.previewH    = previewH
        this.label       = label
        this.classB      = classB
        this.rotationDeg = rotationDeg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (faces.isEmpty()) return

        // previewW/previewH are sensor-space dimensions. For a 90°/270°
        // rotation, the upright image's width/height are swapped relative
        // to sensor width/height — ML Kit's boxes live in that upright
        // space, so we must swap here too to compute the correct scale.
        val rotatedAxesSwap = rotationDeg == 90 || rotationDeg == 270
        val imgW = if (rotatedAxesSwap) previewH else previewW
        val imgH = if (rotatedAxesSwap) previewW else previewH

        val scaleX = width.toFloat()  / imgW.toFloat()
        val scaleY = height.toFloat() / imgH.toFloat()

        val colour = if (classB) Color.RED else Color.GREEN
        boxPaint.color  = colour
        textPaint.color = colour

        for (face in faces) {
            val bb = face.boundingBox

            // For front (selfie) camera the image is mirrored horizontally.
            val left   = (width - bb.right  * scaleX)
            val right  = (width - bb.left   * scaleX)
            val top    = bb.top    * scaleY
            val bottom = bb.bottom * scaleY

            // Guard against inverted or out-of-bounds rects
            val l = minOf(left,  right)
            val r = maxOf(left,  right)
            val t = minOf(top,   bottom).coerceAtLeast(0f)
            val b = maxOf(top,   bottom).coerceAtMost(height.toFloat())

            if (r - l < 10f || b - t < 10f) continue

            canvas.drawRoundRect(l, t, r, b, 12f, 12f, boxPaint)

            if (label.isNotEmpty()) {
                val tw = textPaint.measureText(label)
                val lx = l.coerceAtLeast(0f)
                val ty = (t - 48f).coerceAtLeast(0f)
                canvas.drawRect(lx, ty, lx + tw + 16f, ty + 44f, bgPaint)
                canvas.drawText(label, lx + 8f, ty + 34f, textPaint)
            }
        }
    }
}