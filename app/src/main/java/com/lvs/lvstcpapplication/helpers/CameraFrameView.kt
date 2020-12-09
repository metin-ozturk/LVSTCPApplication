package com.lvs.lvstcpapplication.helpers

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

class CameraFrameView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : View(context, attrs, defStyleAttr) {

    private var frameImage: Bitmap? = null
    private var camRect: Rect? = null

    private val isPortrait: Boolean
        get() = height > width

    private var totalDegreesRotated: Int = 0
    private val isCameraViewPerpendicular: Boolean
        get() = totalDegreesRotated == 90 || totalDegreesRotated == 270

    private var isSplitView: Boolean = false
    private var isMirrored: Boolean = false

    private var paint: Paint = Paint()

    init {
        paint.color = Color.BLACK
        paint.isAntiAlias = true
    }

    fun setDisplay(frame: Bitmap, totalDegreesRotated: Int, isMirrored: Boolean, isSplitView: Boolean) {
        val previousTotalDegreesRotated = this.totalDegreesRotated
        val previousIsSplitStatus = this.isSplitView

        frameImage = frame
        this.totalDegreesRotated = totalDegreesRotated
        this.isMirrored = isMirrored
        this.isSplitView = isSplitView

        if (previousTotalDegreesRotated != totalDegreesRotated || previousIsSplitStatus != isSplitView) measureCamRect()
        invalidate()
    }

    override fun draw(canvas: Canvas?) {
        super.draw(canvas)

        if (canvas == null) {
            Log.d(TAG, "Canvas is null")
            return
        }

        val frameImage = frameImage ?: return

        canvas.save()

        if (isMirrored) mirrorCanvas(canvas)
        canvas.rotate(totalDegreesRotated.toFloat(), width.toFloat() / 2, height.toFloat() / 2)
        camRect?.let { canvas.drawBitmap(frameImage, null, it, paint) }


        canvas.restore()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        measureCamRect()
    }

    private fun mirrorCanvas(canvas: Canvas) {
        canvas.scale(-1F, 1F, width.toFloat() / 2F, height.toFloat() / 2F)
    }


    private fun measureCamRect() {
        val localHeight: Int = if (isPortrait) {
            if (isCameraViewPerpendicular) {
                if (isSplitView) 3 * height / 4 else width
            } else 3 * width / 4
        } else {
            when {
                isCameraViewPerpendicular -> 3 * height / 4
                isSplitView -> 3 * width / 4
                else -> height
            }
        }

        val localWidth: Int = if (isPortrait) {
            if (isCameraViewPerpendicular) {
                if (isSplitView) height else 4 * width / 3
            } else width
        } else {
            when {
                isCameraViewPerpendicular -> height
                isSplitView -> width
                else -> 4 * height / 3
            }
        }

        var verticalMargin = 0
        var horizontalMargin = 0

        if (isPortrait) {
            if (isCameraViewPerpendicular || isSplitView) horizontalMargin = ((width - localWidth) / 2)
            verticalMargin = ((height - localHeight) / 2)
        } else {
            if (isCameraViewPerpendicular || isSplitView) verticalMargin = ((height - localHeight) / 2)
            horizontalMargin = ((width - localWidth) / 2)
        }

        camRect = Rect(
                horizontalMargin,
                verticalMargin,
                localWidth + horizontalMargin,
                localHeight + verticalMargin
        )

    }
}