package tech.jpco.qen.viewModel

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import tech.jpco.qen.model.PagesRepository

val Any.TAG
    get() = this::class.simpleName ?: "Anon"

fun Any.iLogger(output: String, obj: Any? = Unit) {
    val name = Thread.currentThread().name
    val objS = if (obj == Unit) "" else ": $obj"
    Log.d(TAG, "$output$objS on ${name.substring(0, 1).toUpperCase()}${name.substring(1)}")
}

fun <T> Observable<T>.log(name: String, origin: Any): Observable<T> =
    doOnComplete { origin.iLogger("$name completed") }
        .doOnDispose { origin.iLogger("$name got disposed") }
        .doOnEach { origin.iLogger("$name emitted", it.value) }
        .doOnSubscribe { origin.iLogger("$name was subscribed") }


class QenViewModel : ViewModel() {
    private val cd = CompositeDisposable()

    //(Parallel) Dual Subject architecture per https://speakerdeck.com/oldergod/model-view-intent-for-android
    /* In theory, it keeps an "alive" data stream from UI interaction to UI output, allowing an event in flight to
     * be resubscribed by a recreated view before it has finished its journey.
     */
    //TODO test the above description!
    private val touchesIn = PublishSubject.create<DrawPoint>()
    private val metaIn = PublishSubject.create<MetaEvent>()
    private val mTouchesOut = PublishSubject.create<DrawPoint>()
    private val mMetaOut = BehaviorSubject.create<SelectedPage>()

    val touchesOut: Observable<DrawPoint> = mTouchesOut
    val metaOut: Observable<SelectedPage> = mMetaOut


    fun supply(repo: PagesRepository, touches: Observable<DrawPoint>, events: Observable<MetaEvent>) {
        iLogger("supply")
        if (!metaIn.hasObservers()) {
            val stateStream = metaProcessor(repo, metaIn.doOnNext { iLogger("stateStream setup", it) }).share()
                .apply { subscribe(mMetaOut) }
            mMetaOut.onSubscribe(cd)

            repo
                .addTouchStream(
                    touchesIn,
                    stateStream
                        .map { state -> state.current }
                        .distinctUntilChanged()
                )
                .subscribe(mTouchesOut)
            mTouchesOut.onSubscribe(cd)
        }

        events.doOnNext { iLogger("Received from UI", it) }.subscribe(metaIn)
        touches.subscribe(touchesIn)
    }

    override fun onCleared() {
        cd.dispose()
        super.onCleared()
    }

    @VisibleForTesting
    internal fun metaProcessor(
        repo: PagesRepository,
        inStream: Observable<MetaEvent>
    ): Observable<SelectedPage> {
        return repo
            .getMaxPage(inStream.ofType(MetaEvent.CurrentPage::class.java).firstOrError().map { it.ar })
            .doOnNext { iLogger("Max page is", it) }
            .switchMap { maxPage ->
                fun retrievePage(newCurrentPage: Int, retrieveActualPage: Boolean = false): SelectedPage {
                    val (content, ratio) = repo.getPage(newCurrentPage, retrieveActualPage)
                    return SelectedPage(
                        newCurrentPage,
                        maxPage,
                        content,
                        ratio
                    )
                }

                //NB: scan()'s default is the origin of blank page on NewPage as well as init on app open
                fun Observable<MetaEvent>.process() =
                    scan(
                        retrievePage(repo.mostRecentPage).also { iLogger("Default emitted", it) }
                    ) { previousState: SelectedPage, incomingEvent: MetaEvent ->
                        val currentPage = previousState.current

                        when (incomingEvent) {
                            is MetaEvent.CyclePage -> {
                                retrievePage(
                                    if (currentPage < maxPage)
                                        currentPage + 1
                                    else 1
                                )
                            }
                            is MetaEvent.ClearPage -> {
                                repo.clearPage(currentPage)
                                retrievePage(currentPage, false)
                            }
                            is MetaEvent.SelectPage -> {
                                if (incomingEvent.page > maxPage) throw IllegalStateException()
                                retrievePage(incomingEvent.page)
                            }
                            is MetaEvent.NewPage -> throw IllegalStateException()
                            is MetaEvent.CurrentPage -> retrievePage(currentPage)
                        }
                    }

                iLogger("Proc switchmap ticked")

                inStream
                    .doOnNext { iLogger("Processor received", it) }
                    .publish { observable ->
                        observable.ofType(MetaEvent.NewPage::class.java).subscribe { repo.addPage(it.ar) }
                        observable.filter { it !is MetaEvent.NewPage }.process()
                    }

            }
            .doOnNext { iLogger("Processor emitted", it) }
    }

}