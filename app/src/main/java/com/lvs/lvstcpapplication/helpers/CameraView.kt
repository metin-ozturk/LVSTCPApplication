package com.lvs.lvstcpapplication.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.LinearLayout
import com.lvs.lvstcpapplication.R

const val TAG = "CameraView"

class CameraView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : LinearLayout(context, attrs, defStyleAttr) {

    var isMirrored = false
    var isSplitView: Boolean = false

    private var paint: Paint = Paint()

    private var totalDegreesRotated = 0

    var camWidth: Int = 0
    var camHeight: Int = 0

    private val cameraFrameView: CameraFrameView by lazy { findViewById(R.id.lvs_camera_frame_view)}

    init {
        paint.color = Color.BLACK
        paint.isAntiAlias = true
    }

    fun displayFrame(frame: Bitmap?) {
        frame?.let { rFrame ->
            cameraFrameView.setDisplay(rFrame, totalDegreesRotated, isMirrored, isSplitView)
        }
    }

    fun rotate(withDegree: Int) {
        if (withDegree == 90) {
            totalDegreesRotated =
                    if (totalDegreesRotated + 90 >= 360) 0 else totalDegreesRotated + 90
        } else if (withDegree == 180) {
            totalDegreesRotated =
                    if (totalDegreesRotated + 180 >= 360) (totalDegreesRotated + 180) % 360 else totalDegreesRotated + 180
        }
    }

}