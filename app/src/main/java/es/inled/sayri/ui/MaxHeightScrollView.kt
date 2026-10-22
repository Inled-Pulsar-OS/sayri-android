package es.inled.sayri.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * NestedScrollView that enforces a maximum measured height (maxHeightPx).
 * Allows content to expand naturally when small, and caps height with internal scrolling
 * when content exceeds maxHeightPx.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : NestedScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = -1
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hSpec = if (maxHeightPx > 0) {
            val mode = MeasureSpec.getMode(heightMeasureSpec)
            val size = MeasureSpec.getSize(heightMeasureSpec)
            val target = if (mode == MeasureSpec.UNSPECIFIED) {
                maxHeightPx
            } else {
                minOf(size, maxHeightPx)
            }
            MeasureSpec.makeMeasureSpec(target, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, hSpec)
    }
}
