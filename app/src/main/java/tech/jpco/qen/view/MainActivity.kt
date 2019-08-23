package tech.jpco.qen.view

import android.Manifest
import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProviders
import com.jakewharton.rxbinding3.view.clicks
import com.jakewharton.rxbinding3.view.layoutChanges
import com.tbruyelle.rxpermissions2.RxPermissions
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.activity_main.*
import tech.jpco.qen.R
import tech.jpco.qen.iLogger
import tech.jpco.qen.log
import tech.jpco.qen.model.MyPagesRepository
import tech.jpco.qen.viewModel.MetaEvent
import tech.jpco.qen.viewModel.QenViewModel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

//Copied from https://proandroiddev.com/til-when-is-when-exhaustive-31d69f630a8b
val <T> T.exhaustive: T
    get() = this

class MainActivity : AppCompatActivity() {
    private val cd = CompositeDisposable()
    private val vm by lazy { ViewModelProviders.of(this).get(QenViewModel::class.java) }

    @SuppressLint("CheckResult")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupInStreams()

        RxPermissions(this)
            .request(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
            .subscribe {
                check(it)
            }

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
        fun <T : MetaEvent> Button.throttledMetaEvent(
            output: T,
            minMillis: Long = 500
        ): Observable<T> =
            this.clicks()
                .throttleFirst(minMillis, TimeUnit.MILLISECONDS)
                .map { output }


        val metaStream: Observable<MetaEvent> =
            qenPage.arStream.doOnNext { iLogger("AR", it) }
                .publish { ARs ->
                    Observable.merge(
                        ARs.startWith(0f).switchMap {
                            new_button.throttledMetaEvent(
                                MetaEvent.NewPage(
                                    it
                                )
                            )
                        },
                        ARs.take(1).map { MetaEvent.CurrentPage(it) },
                        cycle_button.throttledMetaEvent(MetaEvent.CyclePage, 50),
                        clear_button.throttledMetaEvent(MetaEvent.UiClearPage)
                    )
                }
                .doOnNext { iLogger("MetaEvent emitted new", it) }
                .replay(5000, TimeUnit.MILLISECONDS)
                .apply { connect() }
                .doOnNext { iLogger("MetaEvent replayed", it) }
                .subscribeOn(AndroidSchedulers.mainThread())
//                .observeOn(Schedulers.io())

        val touchStream =
            qenPage.touchStream
                .subscribeOn(AndroidSchedulers.mainThread())
//                .observeOn(Schedulers.io())

        Schedulers.from(Executors.newSingleThreadExecutor {
            Thread(it).apply {
                name = "VM thread"
            }
        }).also {
            it.offload {
                vm.supply(
                    MyPagesRepository.getInstance(applicationContext),
                    touchStream,
                    metaStream,
                    it
                )
            }
        }

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

    private fun Scheduler.offload(block: Boolean = true, load: () -> Unit) =
        Completable.create {
            load()
            it.onComplete()
        }
            .subscribeOn(this)
            .log("offloader", this@MainActivity)
            ./*run {
                if (block) blockingAwait()
                else */subscribe()
    /*}*/
}
