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
import com.jakewharton.rxbinding3.view.layoutChanges
import com.jakewharton.rxbinding3.view.touches
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
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
    private val hypoteneuse
        get() = sqrt((height * height + width * width).toDouble())
            .also { if (it == 0.0) iLogger("hypoteneuse was zero!") }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        canvas?.drawBitmap(bufferBitmap, 0f, 0f, paint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        Log.d(TAG, "onSizeChanged called")
        ar = w.toFloat() / h
        paint.strokeWidth = (hypoteneuse / 300).toFloat()
        //TODO make this resilient/persistent
        bufferBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bufferCanvas = Canvas(bufferBitmap)
        super.onSizeChanged(w, h, oldw, oldh)
    }


    val touchStream: Observable<DrawPoint>
        get() {
            val normalizedDistance = normDistance(15)
            return touches()
                .filterForActionAndTime(33)
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
                .filterForMinDistance { x, y -> abs(x) + abs(y) < normalizedDistance }
        }

    val arStream: Observable<Float> = layoutChanges().map { ar }

    fun observeTouchStreamList(touchStreams: List<Observable<DrawPoint>>): List<Disposable> {
        var counter = 0
        return touchStreams.map { userStream ->
            val localPaint = newPaint(counter++)
            userStream.scan(
                Pair<DrawPoint?, DrawPoint>(null, DrawPoint(Float.NaN, Float.NaN))
            ) { pair, new ->
                if (pair.second.x.isNaN() || new.type == TouchEventType.TouchDown)
                    return@scan null to new
                pair.second to new
            }
                .log("paired touchstream", this)
                .filter { it.first != null }
                .map { it as Pair<DrawPoint, DrawPoint> }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    iLogger("drawing ${it.second}")
                    it.drawLine(localPaint)
                    invalidate()
                }
        }
    }

    private val goldenConjugate = (2 / (sqrt(5f) + 1))
    private val paintSequenceList = mutableListOf<Paint>()
    private val newPaint: (Int) -> Paint = { counter ->
        iLogger("paint counter", counter)
        if (paintSequenceList.size > counter) paintSequenceList[counter]
        else Paint().apply {
            strokeWidth = paint.strokeWidth
            color =
                if (counter == 0) Color.BLACK
                //below is a riff on https://gamedev.stackexchange.com/a/46469
                else Color.HSVToColor(
                    floatArrayOf(
                        360 * (((counter - 1) * goldenConjugate) % 1),
                        1f,
                        0.85f
                    )
                )
            iLogger("actual color", color)
        }.also { paintSequenceList += it }
    }

    fun clearPage() {
        iLogger("clearPage called")
        bufferBitmap.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun drawPage(list: List<List<DrawPoint>>, newAR: Float) {
        clearPage()
        var counter = 0
        list.forEach { userList ->
            iLogger("drawPage counter", counter)
            val localPaint = newPaint(counter)
            userList.zipWithNext().forEach {
                if (it.first.type != TouchEventType.TouchUp) it.drawLine(localPaint)
            }
            counter++
        }
        iLogger("---- invalidating")
        invalidate()
    }

    private fun Pair<DrawPoint, DrawPoint>.drawLine(paint: Paint) =
        (first.denormalize() to second.denormalize()).run {
            bufferCanvas.drawLine(first.x, first.y, second.x, second.y, paint)
        }

    private fun normalize(x: Float, y: Float): Pair<Float, Float> = (x / width) to (y / height)


    @Suppress("SameParameterValue")
    private fun normDistance(pixels: Int) =
        (pixels / (hypoteneuse)).also { iLogger("normalized distance", it) }

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
