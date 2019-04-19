package tech.jpco.qen.model

import com.google.firebase.database.*
import io.reactivex.Observable
import io.reactivex.Single
import tech.jpco.qen.viewModel.DrawPoint

object Firebase : PagesRepository {
    private val database by lazy { FirebaseDatabase.getInstance().reference }
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

    override fun addPage(ar: Float) {
        TODO()
    }

    override fun getAR(page: Int): Float {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getMaxPage(fallbackAR: Single<Float>): Observable<Int> {
        pages.orderByKey().limitToLast(1).addListenerForSingleValueEvent(object: ValueEventListener{
            override fun onCancelled(error: DatabaseError) {
                throw error.toException()
            }

            override fun onDataChange(data: DataSnapshot) {

            }

        })

        pages.runTransaction(object : Transaction.Handler {
            override fun onComplete(error: DatabaseError?, p1: Boolean, p2: DataSnapshot?) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun doTransaction(p0: MutableData): Transaction.Result {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

        }

        )
    }
}