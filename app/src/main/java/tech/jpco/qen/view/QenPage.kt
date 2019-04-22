package tech.jpco.qen.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.jakewharton.rxbinding3.view.layoutChanges
import com.jakewharton.rxbinding3.view.touches
import io.reactivex.Observable
import tech.jpco.qen.R
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.TAG
import tech.jpco.qen.viewModel.TouchEventType
import java.util.concurrent.TimeUnit

class QenPage @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var mHeight = 0
    private var mWidth = 0
    var ar = Float.NaN
        private set
    private lateinit var bufferBitmap: Bitmap
    private lateinit var bufferCanvas: Canvas
    private val paint = Paint()
    private var lastPoint: DrawPoint? = null

    init {
        paint.color = ResourcesCompat.getColor(resources, R.color.colorPrimaryDark, null)
        paint.strokeWidth = 7f
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas?.drawBitmap(bufferBitmap, 0f, 0f, paint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        Log.d(TAG, "onSizeChanged called")
        mHeight = h
        mWidth = w
        ar = w.toFloat() / h
        //TODO make this resilient/persistent
        bufferBitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888)
        bufferCanvas = Canvas(bufferBitmap)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    val touchStream: Observable<DrawPoint> = touches()
        .filterForActionAndTime(50)
        .map {
            val (normX, normY) = normalize(it.x, it.y)
            val type = when (it.action) {
                MotionEvent.ACTION_DOWN -> TouchEventType.TouchDown
                MotionEvent.ACTION_UP -> TouchEventType.TouchUp
                MotionEvent.ACTION_MOVE -> TouchEventType.TouchMove
                else -> throw IllegalArgumentException(
                    "Tried to turn a MotionEvent other than Down, Up, or " +
                            "Move into a DrawPoint!"
                )
            }
            DrawPoint(normX, normY, type)
        }
        .filterForMinDistance { x, y -> Math.abs(x) + Math.abs(y) < normDistance(10) }

    val arStream: Observable<Float> = layoutChanges().map { ar }

    fun drawSegment(new: DrawPoint, shouldInvalidate: Boolean = true) {
        val (denormX, denormY) = denormalize(new.x, new.y)
        val denormNew = new.copy(x = denormX, y = denormY)
        if (lastPoint != null && denormNew.type != TouchEventType.TouchDown) {
            lastPoint?.apply {
                bufferCanvas.drawLine(x, y, denormNew.x, denormNew.y, paint)
                if (shouldInvalidate) invalidate()
            }
        }
        lastPoint = if (denormNew.type == TouchEventType.TouchUp) null else denormNew
    }

    fun clearPage() {
        Log.d(TAG, "clearPage called")
        bufferBitmap.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun drawPage(list: List<DrawPoint>, newAR: Float) {
        clearPage()
        list.forEach { drawSegment(it, false) }
        Log.d(TAG, "drawPage completing")
        invalidate()
    }

    //comment-detritus from aborted aspect-ratio implementation
    private fun normalize(x: Float, y: Float): Pair<Float, Float> =
        /*if (ar < 1)*/ Pair(x / mWidth, y / mHeight)
    /*else Pair(y / mHeight, x / mWidth)*/

    private fun normDistance(pixels: Int) = pixels / (Math.sqrt((mHeight * mHeight + mWidth * mWidth).toDouble()))

    //comment-detritus from aborted aspect-ratio implementation
    private fun denormalize(relX: Float, relY: Float) =
        /*if (ar < 1)*/ Pair(relX * mWidth, relY * mHeight)
    /*else Pair(relY * mHeight, relX * mWidth)*/

    private fun Observable<MotionEvent>.filterForActionAndTime(minMilli: Long): Observable<MotionEvent> =
        publish { multiStream ->
            Observable.merge(
                multiStream
                    .filter {
                        it.action == MotionEvent.ACTION_DOWN ||
                                it.action == MotionEvent.ACTION_UP
                    },
                multiStream
                    .filter {
                        it.action == MotionEvent.ACTION_MOVE
                    }
                    .throttleFirst(minMilli, TimeUnit.MILLISECONDS)

            )
        }


    //scan() emits Pair(the last valid point, the new point IFF it's valid)
    //what's better, .filter().map() or .flatmap() using Observable.empty() and Observable.just(it)?
    private fun Observable<DrawPoint>.filterForMinDistance(tooClose: (Float, Float) -> Boolean): Observable<DrawPoint> =
        scan(Pair<DrawPoint, DrawPoint?>(DrawPoint(Float.NaN, Float.NaN), null)) { standard, incoming ->
            if (incoming.type == TouchEventType.TouchMove) {
                val exX = standard.first.x
                val exY = standard.first.y
                if (exX != Float.NaN && exY != Float.NaN) {
                    val xDif = incoming.x - exX
                    val yDif = incoming.y - exY
                    if (tooClose(xDif, yDif)) {
                        return@scan Pair(standard.first, null)
                    }
                }

            }
            Pair(incoming, incoming)

        }
            .filter {
                it.second != null
            }
            .map {
                it.second!!
            }
}