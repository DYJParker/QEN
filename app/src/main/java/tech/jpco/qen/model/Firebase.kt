package tech.jpco.qen.model

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import durdinapps.rxfirebase2.RxFirebaseAuth
import durdinapps.rxfirebase2.RxFirebaseChildEvent
import durdinapps.rxfirebase2.RxFirebaseDatabase
import durdinapps.rxfirebase2.exceptions.RxFirebaseDataException
import io.reactivex.*
import io.reactivex.functions.BiFunction
import io.reactivex.schedulers.Schedulers
import tech.jpco.qen.iLogger
import tech.jpco.qen.log
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.TouchEventType
import java.util.concurrent.TimeUnit

object Firebase : PagesRepository {
    private var testing: Boolean = false
    private const val testOffset = "Testing"
    private const val courtesyTimeoutInMillis = 3_000L

    private val database by lazy {
        iLogger("preAuth")
        RxFirebaseAuth.signInAnonymously(FirebaseAuth.getInstance().also { iLogger(it.toString()) })
            .toSingle()
            .map {
                iLogger("Authenticated", it.user!!.uid)

                iLogger("getting DB instance")
                FirebaseDatabase.getInstance()
                    .run {
                        if (testing) getReference(testOffset) else reference
                    }
//                    .child(it.user.uid)
            }
            .blockingGet()
    }

    @VisibleForTesting
    internal const val arKey = "AR"
    @VisibleForTesting
    internal const val xKey = "x"
    @VisibleForTesting
    internal const val yKey = "y"
    @VisibleForTesting
    internal const val touchTypeKey = "type"
    @VisibleForTesting
    internal const val mostRecentKey = "most recent"
    @VisibleForTesting
    internal const val uids = "UIDs"
    @VisibleForTesting
    internal val pages by lazy { database.child("pages") }
    @VisibleForTesting
    internal val touchHistory by lazy { database.child("touch history") }

    @VisibleForTesting
    internal val UID by lazy {
        FirebaseAuth.getInstance().currentUser!!.uid
    }

    @VisibleForTesting
    internal fun pageUID(currentPage: Int, uid: String = UID) = "$currentPage-$uid"


    override val mostRecentPage: Int
        get() {
            check(::maxPage.isInitialized) { "getMaxPage() must be called before mostRecentPage" }

            iLogger("inside mostRecentPage")
            return RxFirebaseDatabase.observeSingleValueEvent(database.child(mostRecentKey)) {
                it.getValue(Int::class.java)
            }
                .observeOn(Schedulers.io())
                .toSingle(1)
                .map { current ->
                    maxPage.firstOrError().blockingGet()
                        .let { max -> if (max < current) max else current }
                }
                .log("mostRecentPage", this)
                .blockingGet()!!
        }

//    override lateinit var currentPageClearedStream: Observable<Int>

    override fun setCurrentPageClearedListener(pageStream: Observable<Int>): Observable<Int> {
        return pageStream.switchMap { currentPage ->
            RxFirebaseDatabase.observeValueEvent(pages.child("$currentPage/$uids")) {
                it.exists()
            }.log("firebase clearpagestream page #$currentPage", this)
                .toObservable()
                .filter { !it }
                .map { currentPage }
        }
    }

    private inline fun <reified T : Number> DataSnapshot.exporter(wantKey: String): T {
        return child(wantKey).getValue(T::class.java)!!
    }

    private fun DataSnapshot.toDrawPoint(): DrawPoint {
        return DrawPoint(
            exporter(xKey),
            exporter(yKey),
            TouchEventType.valueOf(child(touchTypeKey).value as String)
        )
    }

    @SuppressLint("CheckResult")
    override fun addTouchStream(
        inStream: Observable<DrawPoint>,
        pageStream: Observable<Int>
    ): Observable<List<Observable<DrawPoint>>> {
        val multiPageStream = pageStream.share()

        inStream
            .log("inStream", this)
            .withLatestFrom(
                multiPageStream.doOnNext {
                    database.child(mostRecentKey).setValue(it)
                    pages.child("$it/$uids/$UID").setValue(true)
                },
                BiFunction { newPoint: DrawPoint, currentPage: Int ->
                    touchHistory.child(pageUID(currentPage)) to newPoint
                }
            )
            .subscribe { it.first.push().setValue(it.second) }

        return multiPageStream
            .switchMap { currentPage ->
                RxFirebaseDatabase.observeValueEvent(pages.child("$currentPage/$uids"))
                    .toObservable()
                    .observeOn(Schedulers.io())
                    .log("uidList", this)
                    .map { snapshot ->
                        val addMyUid = {
                            snapshot.ref.updateChildren(mapOf(UID to true))
                            emptyList<Observable<DrawPoint>>()
                        }

                        if (!snapshot.hasChildren()) {
                            return@map addMyUid()
                        }

                        val currentStamp = touchHistory.push().key!!
                        iLogger("current stamp", currentStamp)

                        snapshot.children
                            .map { it.key!! }
                            .reorderUids { return@map addMyUid() }
                            .also { iLogger("UID list", it) }
                            .map { uidToListenTo ->
                                check(uidToListenTo != null)
                                RxFirebaseDatabase.observeChildEvent(
                                    touchHistory.child(pageUID(currentPage, uidToListenTo))
                                        .orderByKey()
                                        .startAt(currentStamp),
                                    BackpressureStrategy.BUFFER
                                )
                                    .observeOn(Schedulers.io())
                                    .doOnNext {
                                        iLogger("fb emitted", it.key to it.value.value)
                                    }
                                    .filter { childEvent -> childEvent.eventType == RxFirebaseChildEvent.EventType.ADDED }
                                    .map { childEvent ->
                                        childEvent.value.toDrawPoint()
                                    }
                                    .toObservable()
                                    .takeUntil(multiPageStream)
                                    .log(
                                        "touchstream for UID $uidToListenTo on page $currentPage",
                                        this
                                    )
                            }
                    }
            }.log("list of touchstreams", this)
    }

    private inline fun List<String>.reorderUids(onEmpty: (List<String>) -> List<String?>): List<String?> {
        val myIndex = indexOf(UID)
        if (myIndex < 0) return onEmpty(this)
        return if (myIndex == 0) this
        else mutableListOf(get(myIndex)).also {
            it.addAll(slice(0 until myIndex))
            if (myIndex < size - 1) it.addAll(slice(myIndex + 1 until size))
        }
    }


    override fun clearPage(page: Int) {
        iClearPage(page).subscribe()
    }

    @VisibleForTesting
    internal fun iClearPage(page: Int): Completable {
        fun nuke(key: String) = RxFirebaseDatabase.setValue(touchHistory.child(key), null)
        val targetList = mutableListOf<String>()
        iLogger("iClear firing")
        return RxFirebaseDatabase.observeSingleValueEvent(pages.child("$page/$uids"))
            .flatMapCompletable { userDataSnapshot ->
                targetList.addAll(
                    userDataSnapshot.children.map { pageUID(page, it.key!!) }
                )
                Completable.merge(
                    targetList.also { iLogger("list contents", it) }.map { nuke(it) } +
                            RxFirebaseDatabase.setValue(pages.child("$page/$uids"), null))
            }
    }

    override fun addPage(ar: Float) {
        iAddPage(ar).blockingAwait(1, TimeUnit.SECONDS)
            .also { assert(it) { "addPage() timed out!" } }
    }

    @VisibleForTesting
    internal fun iAddPage(ar: Float): Completable {
        return Completable.defer {
            check(::maxPage.isInitialized) { "getMaxPage() must be called before addPage()" }

            iLogger("getting max page")
            val newMax = maxPage.firstOrError().blockingGet() + 1
            iLogger("max page got")

            addNewPage(newMax, ar).also {
                iLogger("(current max page, AR being set)", (newMax - 1) to ar)
            }
        }
    }

    //    @Throws(IllegalStateException::class)
    override fun getPage(
        page: Int,
        retrieveContents: Boolean
    ): Pair<List<List<DrawPoint>>, Float> {
        return Single.zip(
            (if (retrieveContents) {
                RxFirebaseDatabase.observeSingleValueEvent(pages.child("$page/$uids")) { uidSnapshot: DataSnapshot ->
                    uidSnapshot.children.map { it.key!! }.reorderUids { it }
                }
                    .log("getPage uid List", this)
                    .flatMapSingleElement { uidList ->
                        Observable.fromIterable(uidList)
                            .log("getPage uid Observable", this)
                            .concatMapMaybe { uid ->
                                RxFirebaseDatabase.observeSingleValueEvent(
                                    touchHistory.child(pageUID(page, uid))
                                ) { pageListingSnap ->
                                    pageListingSnap.children.map { pointSnapshot ->
                                        pointSnapshot.toDrawPoint()
                                    }
                                }.log("getPage for uid $uid", this)
                            }.toList()
                    }.toSingle(emptyList())
            } else Single.just(emptyList())),
            RxFirebaseDatabase.observeSingleValueEvent(pages.child("$page/$arKey")) { it.value }
//                .doOnComplete { throw IllegalStateException("No recorded AR for the requested page, #$page") }
                .toSingle(0),
            BiFunction { list: List<List<DrawPoint>>, ar: Any? -> list to (ar as Number).toFloat() }
        ).blockingGet()
    }

    //TODO make this smarter with regard to pre-initialization access?
    private lateinit var maxPage: Observable<Int>

    override fun getMaxPage(fallbackAR: Single<Float>): Observable<Int> {
        val orderedPages = pages.orderByKey().limitToLast(1)

        val ongoingMaxPageObservable =
            RxFirebaseDatabase.observeValueEvent(orderedPages, BackpressureStrategy.LATEST)
                .map {
                    it.children.lastOrNull()?.key?.toInt() ?: 1
                }
                .toObservable()
                .observeOn(Schedulers.io())

        maxPage = awaitMaxPageAndSetIfAbsent(fallbackAR).toObservable()
            .concatWith(ongoingMaxPageObservable)
            .distinctUntilChanged().log("maxPage (pre-replay)", this).replay(1).refCount()

        return maxPage

    }

    @VisibleForTesting
    internal fun setTesting(offsetTest: Boolean) {
        testing = offsetTest
        check(offsetTest == (database.key == testOffset))
    }

    @VisibleForTesting
    internal val awaitMaxPageAndSetIfAbsent: (Single<Float>) -> Single<Int> = { arSingle ->
        val pushInitialPage = {
            iLogger("pushing first page")
            addNewPage(
                1,
                arSingle.subscribeOn(Schedulers.computation()).log(
                    "default page",
                    this
                ).blockingGet()
            ).toSingleDefault(1)
                .also { iLogger("pushed first page") }
        }

        pages.runTransaction {
            if (value == null) {
                value = false
                this
            } else {
                null
            }
        }.log("transaction return", this)
            .observeOn(Schedulers.io())
            .flatMap(
                { returnFromTransaction ->
                    this.iLogger("returnFromTransaction fired")
                    if (returnFromTransaction.hasChildren())
                        Maybe.just(returnFromTransaction.children.last().key!!.toInt())
                    else RxFirebaseDatabase.observeChildEvent(pages)
                        .log("first page watcher", this)
                        .filter {
                            it.eventType == RxFirebaseChildEvent.EventType.ADDED
                        }
                        .firstOrError().map {
                            it.value.key!!.toInt()
                        }
                        .timeout(courtesyTimeoutInMillis, TimeUnit.MILLISECONDS, pushInitialPage())
                        .toMaybe()
                },//onSuccess
                { Maybe.error(it) },//onError
                { pushInitialPage().toMaybe() }//onComplete
            ).toSingle()
    }

    @VisibleForTesting
    internal val addNewPage: (Int, Float) -> Completable = { pageNo, AR ->
        iLogger("addNewPage")
        Completable.create {
            this@Firebase.iLogger("attempting to add page $pageNo with AR $AR")
            pages.child(pageNo.toString()).setValue(mapOf(arKey to AR)) { dbError, _ ->
                RxFirebaseDatabase.setValue(database.child(mostRecentKey), pageNo)
                    .blockingAwait(500, TimeUnit.MILLISECONDS)
                    .also { assert(!it) { "setting mostRecentKey to $pageNo failed" } }
                this@Firebase.iLogger("returned from adding page $pageNo with AR $AR")
                if (!it.isDisposed) {
                    if (dbError != null) it.onError(dbError.toException())
                    else it.onComplete()
                }
            }
        }
    }
}

//Kotlinized implementation of FrangSierra's RxFirebase (https://github.com/FrangSierra/RxFirebase)
fun DatabaseReference.runTransaction(exec: MutableData.() -> MutableData?) =
    Maybe.create<DataSnapshot> { output ->
        this.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                val out = data.exec() ?: return Transaction.abort()
                return Transaction.success(out)
            }

            override fun onComplete(p0: DatabaseError?, committed: Boolean, p2: DataSnapshot?) {
                if (!output.isDisposed) {
                    if (p0 != null) output.onError(RxFirebaseDataException(p0))
                    else p2?.also {
                        if (!committed) output.onSuccess(p2)
                        else output.onComplete()
                    } ?: throw IllegalStateException()
                }
            }
        }, false)
    }

fun <T> Maybe<T>.toCompletable(transformer: (T) -> Completable) = Completable.create { cEmitter ->
    subscribe(
        {
            transformer(it).subscribe(cEmitter::onComplete)
        },
        {
            cEmitter.onError(it)
        },
        {
            cEmitter.onComplete()
        }
    )
}
