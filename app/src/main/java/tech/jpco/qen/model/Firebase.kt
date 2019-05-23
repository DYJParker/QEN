package tech.jpco.qen.model

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import com.google.firebase.database.*
import durdinapps.rxfirebase2.RxFirebaseChildEvent
import durdinapps.rxfirebase2.RxFirebaseDatabase
import durdinapps.rxfirebase2.exceptions.RxFirebaseDataException
import io.reactivex.*
import io.reactivex.functions.BiFunction
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
        FirebaseDatabase.getInstance().let {
            if (testing) it.getReference(testOffset) else it.reference
        }
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
    internal const val UID = "UID"
    @VisibleForTesting
    internal const val mostRecentKey = "most recent"
    @VisibleForTesting
    internal const val uids = "UIDs"
    @VisibleForTesting
    internal val pages by lazy { database.child("pages") }
    @VisibleForTesting
    internal val touchHistory by lazy { database.child("touch history") }

    @VisibleForTesting
    internal fun pageUID(currentPage: Int, uid: String = UID) = "$currentPage-$uid"


    override val mostRecentPage: Int
        get() = RxFirebaseDatabase.observeSingleValueEvent(database.child(mostRecentKey)) {
            it.getValue(Int::class.java)
        }.doOnComplete { throw IllegalStateException("most recent page didn't exist!") }.blockingGet()!!

    private inline fun <reified T : Number> DataSnapshot.exporter(wantKey: String): T {
        return child(wantKey).getValue(T::class.java)!!
    }

    private fun DataSnapshot.toDrawPoint(): DrawPoint {
        return DrawPoint(
            exporter(xKey), exporter(yKey), TouchEventType.valueOf(child(touchTypeKey).value as String)
        )
    }

    @SuppressLint("CheckResult")
    override fun addTouchStream(inStream: Observable<DrawPoint>, pageStream: Observable<Int>): Observable<DrawPoint> {
        inStream
            .withLatestFrom(
                pageStream.doOnNext {
                    database.child(mostRecentKey).setValue(it)
                    pages.child("$it/$uids/$UID").setValue(true)
                },
                BiFunction { t1: DrawPoint, t2: Int ->
                    touchHistory.child(pageUID(t2)) to t1
                }
            )
            .subscribe { it.first.push().setValue(it.second) }

        return pageStream.switchMap { currentPage ->
            RxFirebaseDatabase.observeChildEvent(
                touchHistory.child(pageUID(currentPage)),
                BackpressureStrategy.BUFFER
            ).doOnNext {
                iLogger("fb emitted", it.eventType to it.value.value)
            }.filter {
                it.eventType == RxFirebaseChildEvent.EventType.ADDED
            }.map { childEvent ->
                childEvent.value.toDrawPoint()
            }.toObservable()
        }
    }

    override fun clearPage(page: Int) {
        iClearPage(page)
    }

    @SuppressLint("CheckResult")
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
                Completable.merge(targetList.also { iLogger("list contents", it) }.map { nuke(it) } +
                        RxFirebaseDatabase.setValue(pages.child("$page/$uids"), null))
            }
    }

    override fun addPage(ar: Float) {
        iAddPage(ar).subscribe()
    }

    @VisibleForTesting
    internal fun iAddPage(ar: Float): Completable {
        return Completable.defer {
            if (!::maxPage.isInitialized) throw IllegalStateException("getMaxPage() must be called before addPage()")

            val newMax = maxPage.firstOrError().blockingGet() + 1

            addNewPage(newMax, ar).also {
                iLogger("(current max page, AR being set)", (newMax - 1) to ar)
            }
        }
    }

    //    @Throws(IllegalStateException::class)
    override fun getPage(
        page: Int,
        retrieveContents: Boolean
    ): Pair<List<DrawPoint>, Float> {
        return Single.zip(
            (if (retrieveContents) {
                RxFirebaseDatabase.observeSingleValueEvent(touchHistory.child(pageUID(page))) { pageListingSnap ->
                    pageListingSnap.children.toList().map { pointSnap ->
                        pointSnap.toDrawPoint()
                    }
                }.toSingle(listOf())
            } else Single.just(listOf())),
            RxFirebaseDatabase.observeSingleValueEvent(pages.child("$page/$arKey")) { it.value }
                .doOnComplete { throw IllegalStateException("No recorded AR for the requested page") }
                .flatMapSingle { Single.just((it as Number).toFloat()) },
            BiFunction { list: List<DrawPoint>, ar: Float -> list to ar }
        ).blockingGet()
    }

    private lateinit var maxPage: Observable<Int>

    override fun getMaxPage(fallbackAR: Single<Float>): Observable<Int> {
        val orderedPages = pages.orderByKey().limitToLast(1)

        val ongoingMaxPageObservable =
            RxFirebaseDatabase.observeValueEvent(orderedPages, BackpressureStrategy.LATEST)
                .map {
                    it.children.last().key!!.toInt()
                }.toObservable()

        maxPage = awaitMaxPageAndSetIfAbsent(fallbackAR).toObservable()
            .concatWith(ongoingMaxPageObservable)
            .distinctUntilChanged().log("maxPage (pre-replay)", this).replay(1).refCount()

        return maxPage

    }

    @VisibleForTesting
    internal fun setTesting(offsetTest: Boolean) {
        testing = offsetTest
        if (offsetTest != (database.key == testOffset)) throw IllegalStateException()
    }

    @VisibleForTesting
    internal val awaitMaxPageAndSetIfAbsent: (Single<Float>) -> Single<Int> = { arSingle ->
        val pushInitialPage = {
            addNewPage(1, arSingle.blockingGet()).toSingleDefault(1)
        }

        pages.runTransaction {
            if (value == null) {
                value = false
                this
            } else {
                null
            }
        }.flatMap(
            { returnFromTransaction ->
                if (returnFromTransaction.hasChildren())
                    Maybe.just(returnFromTransaction.children.last().key!!.toInt())
                else RxFirebaseDatabase.observeChildEvent(pages)
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
        Completable.create {
            this@Firebase.iLogger("attempting to add page $pageNo with AR $AR")
            pages.child(pageNo.toString()).setValue(mapOf(arKey to AR)) { dbError, _ ->
                database.child(mostRecentKey).setValue(pageNo)
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
