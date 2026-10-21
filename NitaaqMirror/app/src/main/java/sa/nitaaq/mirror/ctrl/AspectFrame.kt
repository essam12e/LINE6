package sa.nitaaq.mirror.ctrl

import android.content.Context
import android.view.View
import android.view.ViewGroup
import sa.nitaaq.mirror.core.CoordinateMapper

/**
 * Lays out its single child (the video SurfaceView) at the largest rectangle with the host's aspect ratio.
 * The child therefore shows the *whole* host screen (nothing cropped, nothing stretched), and the black bars
 * belong to this parent, so touches that start on them never reach the video view.
 */
class AspectFrame(ctx: Context) : ViewGroup(ctx) {
    private var aw = 9
    private var ah = 19

    fun setAspect(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == aw && h == ah)) return
        aw = w; ah = h
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val box = CoordinateMapper.fit(w, h, aw, ah)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(box.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(box.height, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val box = CoordinateMapper.fit(r - l, b - t, aw, ah)
        for (i in 0 until childCount) {
            val c: View = getChildAt(i)
            c.layout(box.left, box.top, box.right, box.bottom)
        }
    }
}
