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
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import tech.jpco.qen.R
import tech.jpco.qen.TAG
import tech.jpco.qen.iLogger
import tech.jpco.qen.log
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.TouchEventType
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

class QenPage @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private var ar = Float.NaN
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
//        mHeight = h
//        mWidth = w
        ar = w.toFloat() / h
        //TODO make this resilient/persistent
        bufferBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
        .filterForMinDistance { x, y -> abs(x) + abs(y) < normDistance(10) }

    val arStream: Observable<Float> = layoutChanges().map { ar }

    fun oldDrawSegment(new: DrawPoint, shouldInvalidate: Boolean = true) {
        val (denormX, denormY) = oldDenormalize(new.x, new.y)
        val denormNew = new.copy(x = denormX, y = denormY)
        if (lastPoint != null && denormNew.type != TouchEventType.TouchDown) {
            lastPoint?.apply {
                bufferCanvas.drawLine(x, y, denormNew.x, denormNew.y, paint)
                if (shouldInvalidate) invalidate()
            }
        }
        lastPoint = if (denormNew.type == TouchEventType.TouchUp) null else denormNew
    }

    fun observeTouchStream(touchStream: Observable<DrawPoint>): Disposable =
        touchStream
            .scan(Pair<DrawPoint?, DrawPoint>(null, DrawPoint(Float.NaN, Float.NaN))) { pair, new ->
                val denormNew = new.denormalize()
                if (pair.second.x.isNaN() || new.type == TouchEventType.TouchDown)
                    return@scan null to denormNew
                pair.second to denormNew
            }
            .log("paired touchstream", this)
            .filter { it.first != null }
            .map { it as Pair<DrawPoint, DrawPoint> }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                iLogger("drawing ${it.second}")
                it.apply { bufferCanvas.drawLine(first.x, first.y, second.x, second.y, paint) }
                invalidate()
            }

    fun clearPage() {
        iLogger("clearPage called")
        bufferBitmap.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun drawPage(list: List<DrawPoint>, newAR: Float) {
        clearPage()
        list.forEach { oldDrawSegment(it, false) }
        invalidate()
    }

    //comment-detritus from aborted aspect-ratio implementation
    private fun normalize(x: Float, y: Float): Pair<Float, Float> =
        /*if (aspectRatio < 1)*/ Pair(x / width, y / height)
    /*else Pair(y / mHeight, x / mWidth)*/

    @Suppress("SameParameterValue")
    private fun normDistance(pixels: Int) =
        pixels / (sqrt((height * height + width * width).toDouble()))

    //comment-detritus from aborted aspect-ratio implementation
    private fun oldDenormalize(relX: Float, relY: Float) =
        /*if (aspectRatio < 1)*/ Pair(relX * width, relY * height)
    /*else Pair(relY * mHeight, relX * mWidth)*/

    private fun DrawPoint.denormalize() = copy(x = x * width, y = y * height)

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

    private fun Observable<DrawPoint>.filterForMinDistance(tooClose: (Float, Float) -> Boolean): Observable<DrawPoint> =
        scan(DrawPoint(Float.NaN, Float.NaN)) { previousValid: DrawPoint, incoming: DrawPoint ->
            if (incoming.type == TouchEventType.TouchMove) {
                val exX = previousValid.x
                val exY = previousValid.y
                if (!exX.isNaN() && !exY.isNaN()) {
                    val xDif = incoming.x - exX
                    val yDif = incoming.y - exY
                    if (tooClose(xDif, yDif)) {
                        return@scan previousValid
                    }
                }

            }
            incoming
        }
            .filter { !it.x.isNaN() }
            .distinctUntilChanged()
}
