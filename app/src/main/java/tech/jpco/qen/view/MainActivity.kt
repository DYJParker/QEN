package tech.jpco.qen.view

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.MotionEvent.*
import com.jakewharton.rxbinding3.view.touches
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import tech.jpco.qen.R
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.TouchEventType
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.TimeUnit

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {
    private val cd = CompositeDisposable()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val output = qenPage.touches()
            .publish { touchStream ->
                Observable.merge(
                    touchStream.filter {
                        it.action == ACTION_DOWN ||
                                it.action == ACTION_UP
                    },
                    touchStream.filter { it.action == ACTION_MOVE }
                        .throttleFirst(100L, TimeUnit.MILLISECONDS)
                )
            }
            .doOnNext {
                Log.d(TAG, "raw: ${it.x}, ${it.y}")
            }
            //TODO normalize these coordinates
            .map {
                DrawPoint(
                    it.x,
                    it.y,
                    when (it.action) {
                        ACTION_DOWN -> TouchEventType.TouchDown
                        ACTION_UP -> TouchEventType.TouchUp
                        ACTION_MOVE -> TouchEventType.TouchMove
                        else -> throw IllegalArgumentException(
                            "Tried to turn a MotionEvent other than Down, Up, or " +
                                    "Move into a DrawPoint!"
                        )
                    }
                )
            }
//            .distinctUntilChanged { old, new ->
//                val xdif = old.normX - new.normX
//                val ydif = old.normY - new.normY
//                Log.d(TAG, "$xdif, $ydif")
//                new.type == TouchEventType.TouchMove &&
//                        Math.abs(xdif) + Math.abs(ydif) < 10
//            }
            .doOnNext {
                Log.d(TAG, "DPed: ${it.normX}, ${it.normY}, ${it.type}")
            }

        cd.add(
            output
                .scan(Pair<DrawPoint?, DrawPoint?>(null, null)) { previous, incoming ->
                    Pair(
                        previous.second,
                        incoming
                    )
                }
                .skip(2L)
                .subscribe {
                    qenPage.drawSegment(it.first, it.second)
                }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cd.dispose()
    }
}
