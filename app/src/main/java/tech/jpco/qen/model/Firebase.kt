package tech.jpco.qen.model

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import com.google.firebase.database.*
import durdinapps.rxfirebase2.RxFirebaseChildEvent
import durdinapps.rxfirebase2.RxFirebaseDatabase
import durdinapps.rxfirebase2.exceptions.RxFirebaseDataException
import io.reactivex.*
import io.reactivex.functions.BiFunction
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.TouchEventType
import tech.jpco.qen.viewModel.iLogger
import tech.jpco.qen.viewModel.log
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
    internal val pages by lazy { database.child("pages") }
    @VisibleForTesting
    internal val touchHistory by lazy { database.child("touch history") }
    @VisibleForTesting
    internal val pageUID = { currentPage: Int -> "$currentPage-$UID" }

    override val mostRecentPage: Int
        get() = TODO()

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
                    touchHistory.child("$it/$UID").setValue(true)
                },
                BiFunction { t1: DrawPoint, t2: Int ->
                    Pair(touchHistory.child(pageUID(t2)), t1)
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
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    @SuppressLint("CheckResult")
    override fun addPage(ar: Float): Completable {
        return Completable.defer {
            if (!::maxPage.isInitialized) throw IllegalStateException("getMaxPage() must be called before addPage()")

            val newMax = maxPage.firstOrError().blockingGet() + 1
            database.child(mostRecentKey).setValue(newMax)

            pages.addNewPage(newMax, ar).also {
                iLogger("(current max page, AR being set)", (newMax - 1) to ar)
            }
        }
    }

    override fun getPage(
        page: Int,
        retrieveContents: Boolean
    ): Pair<List<DrawPoint>, Float> {
        TODO()
        /*return Single.zip(
            RxFirebaseDatabase.observeSingleValueEvent(touchHistory.child(pageUID(page))) { pageListingSnap ->
                pageListingSnap.children.toList().map { pointSnap ->
                    pointSnap.toDrawPoint()
                }
            },
            RxFirebaseDatabase.observeSingleValueEvent(pages.child(page.toString()).or){

            }
        )*/
    }

    private lateinit var maxPage: Observable<Int>

    override fun getMaxPage(fallbackAR: Single<Float>): Observable<Int> {
        val orderedPages = pages.orderByKey().limitToLast(1)

        val ongoingMaxPageObservable =
            RxFirebaseDatabase.observeValueEvent(orderedPages, BackpressureStrategy.LATEST)
                .map {
                    it.children.last().key!!.toInt()
                }.toObservable()

        maxPage = /*(
                if (::maxPage.isInitialized) Observable.empty()
                else*/ pages.awaitMaxPageAndSetIfAbsent(fallbackAR)
            .toObservable()
            /*)*/
            .concatWith(ongoingMaxPageObservable)
            .distinctUntilChanged().log("maxPage (pre-replay)", this).replay(1).refCount()

        return maxPage

    }

    @VisibleForTesting
    fun setTesting(offsetTest: Boolean) {
        testing = offsetTest
        if (offsetTest != (database.key == testOffset)) throw IllegalStateException()
    }

    @VisibleForTesting
    val awaitMaxPageAndSetIfAbsent: DatabaseReference.(Single<Float>) -> Single<Int> = { arSingle ->
        val pushInitialPage = {
            addNewPage(1, arSingle.blockingGet()).toSingleDefault(1)
        }

        runTransaction {
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
                else RxFirebaseDatabase.observeChildEvent(this)
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

    //Takes a Ref as a receiver so that awaitMax...() is "static."
    val addNewPage: DatabaseReference.(Int, Float) -> Completable = { pageNo, AR ->
        Completable.create {
            this@Firebase.iLogger("attempting to add page $pageNo with AR $AR")
            child(pageNo.toString()).setValue(mapOf(arKey to AR)) { dbError, _ ->
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
