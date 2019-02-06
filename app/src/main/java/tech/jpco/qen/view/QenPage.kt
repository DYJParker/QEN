package tech.jpco.qen.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.support.v4.content.res.ResourcesCompat
import android.util.AttributeSet
import android.util.Log
import android.view.View
import tech.jpco.qen.R
import tech.jpco.qen.viewModel.DrawPoint
import java.lang.IllegalStateException

private const val TAG = "QEN"

class QenPage @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var mHeight = 0
    private var mWidth = 0
    private var AR = 0.0f
    private lateinit var bufferBitmap: Bitmap
    private lateinit var bufferCanvas: Canvas
    private val paint = Paint()
    private var newTouch = false

    init {
        paint.color = ResourcesCompat.getColor(resources, R.color.primary_dark_material_dark, null)
        paint.strokeWidth = 12f
    }

    fun drawSegment(start: DrawPoint?, end: DrawPoint?) {
        if (start == null || end == null) throw IllegalStateException()
        Log.d(TAG, "${start.normX}, ${start.normY}, ${start.type}; ${end.normX}, ${end.normY}, ${end.type}")
        bufferCanvas.drawLine(start.normX, start.normY, end.normX, end.normY, paint)
        invalidate()
    }


    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas?.drawBitmap(bufferBitmap, 0f, 0f, paint)
    }

    fun normalize(x: Float, y: Float): Pair<Float, Float> = Pair(x / mWidth, y / mHeight)

    private fun denormalize(relX: Float, relY: Float) = Pair(relX * mWidth, relY * mHeight)


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        mHeight = h
        mWidth = w
        AR = w.toFloat() / h
        //TODO make this resilient/persistent
        bufferBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bufferCanvas = Canvas(bufferBitmap)
    }

}