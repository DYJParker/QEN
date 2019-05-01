package tech.jpco.qen.model

import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import com.google.firebase.database.*
import durdinapps.rxfirebase2.RxFirebaseDatabase
import durdinapps.rxfirebase2.exceptions.RxFirebaseDataException
import io.reactivex.BackpressureStrategy
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.BehaviorSubject
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.iLogger

object Firebase : PagesRepository {
    private var testing: Boolean = false
    private const val testOffset = "Testing"
    private val database by lazy {
        FirebaseDatabase.getInstance().let {
            if (testing) it.getReference(testOffset) else it.reference
        }
    }
    private val pages by lazy { database.child("pages") }

    override val mostRecentPage: Int
        get() = TODO()

    override fun addTouchStream(inStream: Observable<DrawPoint>, pageStream: Observable<Int>): Observable<DrawPoint> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getSelectedPagePoints(page: Int): List<DrawPoint> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun clearPage(page: Int) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    @SuppressLint("CheckResult")
    override fun addPage(ar: Float) {
        maxPage.firstOrError().subscribe { currentMax ->
            pages.push().setValue(mapOf("page" to (currentMax + 1), "AR" to ar))
        }
    }

    override fun getAR(page: Int): Float {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private val maxPage = BehaviorSubject.create<Int>()

    override fun getMaxPage(fallbackAR: Single<Float>): Observable<Int> {
        val orderedPages = pages.orderByKey().limitToLast(1)

        RxFirebaseDatabase.observeValueEvent(orderedPages, BackpressureStrategy.LATEST)
            .startWith(orderedPages.ref.getMaxPageAndSetIfAbsent(fallbackAR).toFlowable())
            .doOnNext { iLogger("FB outputted", it) }
            .map {
                1 + (it.children.last().key?.toInt() ?: throw IllegalStateException())
            }.distinctUntilChanged().toObservable().subscribe(maxPage)

        return maxPage

    }

    @VisibleForTesting
    fun setTesting(offsetTest: Boolean) {
        testing = offsetTest
        if (offsetTest == (database.key != testOffset)) throw IllegalStateException()
    }

    @VisibleForTesting
    val getMaxPageAndSetIfAbsent: DatabaseReference.(Single<Float>) -> Single<DataSnapshot> = {
        runTransaction {
            if (value == null)
                child("0/AR").value = it.blockingGet()
            this
        }.doOnSuccess {
            iLogger("Snapshot contents coming into OnSuccess()", it)
            it.children.last().run {
                if (this.childrenCount < 2) {
                    iLogger("ref being pushed", key)
                    ref.push().setValue(true)
                }
            }

        }
    }
}

const val ABORT = "_ABORT_"
//Kotlinized implementation of FrangSierra's RxFirebase (https://github.com/FrangSierra/RxFirebase)
fun DatabaseReference.runTransaction(exec: MutableData.() -> MutableData?) =
    Single.create<DataSnapshot> { output ->
        this.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                val out = data.exec() ?: return Transaction.abort()
                return Transaction.success(out)
            }

            override fun onComplete(p0: DatabaseError?, committed: Boolean, p2: DataSnapshot?) {
                if (!output.isDisposed) {
                    if (p0 != null) output.onError(RxFirebaseDataException(p0))
                    else output.onSuccess(p2 ?: throw IllegalStateException())
                }
            }

        }
            , false)
    }
