package com.example.mobile_assistant

import android.content.Context
import android.graphics.drawable.PictureDrawable
import android.util.AttributeSet
import android.view.View
import androidx.annotation.RawRes
import androidx.appcompat.widget.AppCompatImageView
import com.caverock.androidsvg.SVG

class SvgLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    @RawRes
    private var svgRawResourceId: Int = 0
    private var svg: SVG? = null

    init {
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = true
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.SvgLogoView, defStyleAttr, 0)
        svgRawResourceId = typedArray.getResourceId(R.styleable.SvgLogoView_svgRawResource, 0)
        typedArray.recycle()

        if (svgRawResourceId != 0) {
            loadSvg(svgRawResourceId)
        }
    }

    fun setSvgRawResource(@RawRes rawResourceId: Int) {
        if (svgRawResourceId == rawResourceId) return
        svgRawResourceId = rawResourceId
        loadSvg(rawResourceId)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateDrawable()
    }

    private fun loadSvg(@RawRes rawResourceId: Int) {
        svg = runCatching { SVG.getFromResource(resources, rawResourceId) }.getOrNull()
        updateDrawable()
    }

    private fun updateDrawable() {
        val currentSvg = svg ?: return
        if (width <= 0 || height <= 0) return
        setImageDrawable(PictureDrawable(currentSvg.renderToPicture(width, height)))
    }
}
