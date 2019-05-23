package tech.jpco.qen.view

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProviders
import com.jakewharton.rxbinding3.view.clicks
import com.jakewharton.rxbinding3.view.layoutChanges
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import tech.jpco.qen.R
import tech.jpco.qen.iLogger
import tech.jpco.qen.model.SQL
import tech.jpco.qen.viewModel.MetaEvent
import tech.jpco.qen.viewModel.QenViewModel
import java.util.concurrent.TimeUnit

//Copied from https://proandroiddev.com/til-when-is-when-exhaustive-31d69f630a8b
val <T> T.exhaustive: T
    get() = this

class MainActivity : AppCompatActivity() {
    private val cd = CompositeDisposable()
    private val vm by lazy { ViewModelProviders.of(this).get(QenViewModel::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupInStreams()

    }

    override fun onStart() {
        super.onStart()
        setupOutStreams()
    }

    override fun onDestroy() {
        super.onDestroy()
        cd.dispose()
    }

    private fun setupOutStreams() {
        fun <T : MetaEvent> Button.throttledMetaEvent(output: T, minMillis: Long = 500): Observable<T> =
            this.clicks()
                .throttleFirst(minMillis, TimeUnit.MILLISECONDS)
                .map { output }


        val metaStream: Observable<MetaEvent> =
            qenPage.arStream.doOnNext { iLogger("AR", it) }
                .publish { ARs ->
                    Observable.merge(
                        ARs.startWith(0f).switchMap { new_button.throttledMetaEvent(MetaEvent.NewPage(it)) },
                        ARs.take(1).map { MetaEvent.CurrentPage(it) },
                        cycle_button.throttledMetaEvent(MetaEvent.CyclePage, 50),
                        clear_button.throttledMetaEvent(MetaEvent.ClearPage)
                    )
                }
                .doOnNext { iLogger("MetaEvent emitted new", it) }
                .observeOn(Schedulers.io())

        val touchStream =
            qenPage.touchStream
                .observeOn(Schedulers.io())

        vm.supply(SQL.getInstance(applicationContext), touchStream, metaStream)
    }

    private fun setupInStreams() {
        cd.addAll(
            vm.touchesOut
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    qenPage.drawSegment(it)
                },
            vm.metaOut
                .observeOn(AndroidSchedulers.mainThread())
                .delaySubscription(qenPage.layoutChanges())
                .subscribe {
                    iLogger("Main activity received", it)
                    if (it.content.isNotEmpty()) qenPage.drawPage(it.content, it.ratio)
                    else qenPage.clearPage()
                }
        )
    }
}
