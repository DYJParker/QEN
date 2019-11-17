package tech.jpco.qen.viewModel

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.PublishSubject
import tech.jpco.qen.iLogger
import tech.jpco.qen.log
import tech.jpco.qen.model.PagesRepository

class QenViewModel : ViewModel() {
    private val cd = CompositeDisposable()

    //(Parallel) Dual Subject architecture per https://speakerdeck.com/oldergod/model-view-intent-for-android
    /* In theory, it keeps an "alive" data stream from UI interaction to UI output, allowing an event in flight to
     * be resubscribed by a recreated view before it has finished its journey.
     */
    //TODO test the above description!
    private val touchesIn = PublishSubject.create<DrawPoint>()
    private val metaIn = PublishSubject.create<MetaEvent>()
    private val touchesOutSubject = PublishSubject.create<List<Observable<DrawPoint>>>()
    private val metaOutSubject = BehaviorSubject.create<SelectedPage>()

    val touchesOut: Observable<List<Observable<DrawPoint>>> = touchesOutSubject
    val metaOut: Observable<SelectedPage> = metaOutSubject


    @SuppressLint("CheckResult")
    fun supply(
        repo: PagesRepository,
        touches: Observable<DrawPoint>,
        events: Observable<MetaEvent>,
        scheduler: Scheduler
    ) {
        iLogger("supply")
        if (!metaIn.hasObservers()) {
            val pageStreamProxy = PublishSubject.create<MetaEvent>()
            iLogger("connecting outstreams from VM")
            val stateStream =
                metaProcessor(
                    repo,
                    metaIn
                        .log("metaIn", this)
                        .mergeWith(pageStreamProxy.observeOn(scheduler)),
                    scheduler
                )
                    .share()
                    .apply { subscribe(metaOutSubject) }
            metaOutSubject.onSubscribe(cd)

            val pageStream =
                stateStream
                    .map { state -> state.current }
                    .distinctUntilChanged()
                    .share()

            repo.setCurrentPageClearedListener(pageStream)
                .log("DB clear page stream", this)
                .map { MetaEvent.DbClearPage(it) }
                .subscribe(pageStreamProxy)

            repo
                .addTouchStream(
                    touchesIn,
                    pageStream
                )
                .observeOn(scheduler)
                .map {
                    it.mapIndexed { index, observable ->
                        observable.log("touch stream #$index", this).also {
                            iLogger("logging touch stream $index")
                        }
                    }
                }
                .subscribe(touchesOutSubject)
            touchesOutSubject.onSubscribe(cd)
        }

        events
            .log("Received from UI", this)
            /*.mergeWith(
                repo.currentPageClearedStream
                    .log("DB clear page stream", this)
                    .map { MetaEvent.DbClearPage(it) }
            )*/
            .observeOn(scheduler)
            .subscribe(metaIn)
        touches
            .observeOn(scheduler)
            .subscribe(touchesIn)

    }

    override fun onCleared() {
        cd.clear()
        super.onCleared()
    }

    @VisibleForTesting
    internal fun metaProcessor(
        repo: PagesRepository,
        inStream: Observable<MetaEvent>,
        scheduler: Scheduler
    ): Observable<SelectedPage> = repo
        .getMaxPage(
            inStream
                .ofType(MetaEvent.CurrentPage::class.java)
                .firstOrError()
                .map { it.aspectRatio }
                .log("arSingle", this)
                .cache()
                .doOnSuccess { iLogger("arSingle emitted") }
                .also { it.subscribe() }
            /*Single.just(-1f)*/
        )
        .observeOn(scheduler)
        .doOnNext { iLogger("Max page is", it) }
        .switchMap { maxPage ->
            fun retrievePage(
                newCurrentPage: Int,
                retrieveActualPage: Boolean = true
            ): SelectedPage {
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
                    retrievePage(repo.mostRecentPage).also {
                        this@QenViewModel.iLogger("Defaulted to", it)
                    }
                ) { previousState: SelectedPage, incomingEvent: MetaEvent ->
                    val currentPage = previousState.current

                    val mootPage = SelectedPage(currentPage, maxPage)

                    when (incomingEvent) {
                        is MetaEvent.CyclePage -> {
                            retrievePage(
                                if (currentPage < maxPage)
                                    currentPage + 1
                                else 1
                            )
                        }
                        is MetaEvent.UiClearPage -> {
                            repo.clearPage(currentPage)
                            mootPage
                        }
                        is MetaEvent.SelectPage -> {
                            check(incomingEvent.page <= maxPage)
                            retrievePage(incomingEvent.page)
                        }
                        is MetaEvent.NewPage -> {
                            repo.addPage(incomingEvent.aspectRatio)
                            mootPage
                        }
                        is MetaEvent.CurrentPage -> retrievePage(currentPage)
                        is MetaEvent.DbClearPage -> {
                            if (incomingEvent.intendedPage == currentPage)
                                retrievePage(currentPage)
                            else mootPage
                        }
                    }
                }
                    //this filter allows me to use the primary constructor of SelectedPage as a "do not issue" flag
                    .filter { !it.ratio.isNaN() }
//                    .skip(1)

            iLogger("Proc switchmap ticked")

            inStream
                .doOnSubscribe { iLogger("Processor was subscribed") }
                .doOnNext { iLogger("Processor received", it) }
                /*.doOnNext { repo.addPage(-99f) }
                .flatMap { Observable.empty<SelectedPage>() }
                .startWith(retrievePage(maxPage))*/
                /*.publish { observable ->
                    observable.ofType(MetaEvent.NewPage::class.java)
                        .subscribe { repo.addPage(it.aspectRatio) }
                    observable.ofType(MetaEvent.UiClearPage::class.java)
                        .subscribe{repo.clearPage(curren)}
                    observable.filter { it !is MetaEvent.NewPage }*/.process()
            /*}*/

        }
        .doOnNext { iLogger("Processor emitted", it) }
}