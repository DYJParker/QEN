package tech.jpco.qen.model

import io.reactivex.Observable
import io.reactivex.Single
import tech.jpco.qen.viewModel.DrawPoint

/*
class MyPagesRepository private constructor(ctx: Context) : PagesRepository {
    companion object : SingletonHolder<MyPagesRepository, Context>(::MyPagesRepository)

    private val currentRepo = SQL.getInstance(ctx.applicationContext)

    override fun addTouchStream(inStream: Observable<DrawPoint>, pageStream: Observable<Int>): Observable<DrawPoint> =
        currentRepo.addTouchStream(inStream, pageStream)

    override fun getMaxPage(fallbackAR: Single<Float>): Observable<Int> = currentRepo.getMaxPage(fallbackAR)

    override fun getSelectedPagePoints(page: Int) = currentRepo.getSelectedPagePoints(page)

    override val mostRecentPage
        get() = currentRepo.mostRecentPage

    override fun clearPage(page: Int) = currentRepo.clearPage(page)

    override fun addPage(ar: Float) = currentRepo.addPage(ar)

    override fun getAR(page: Int) = currentRepo.getAR(page)
}
*/

interface PagesRepository {
    fun addTouchStream(inStream: Observable<DrawPoint>, pageStream: Observable<Int>): Observable<DrawPoint>
    fun getSelectedPagePoints(page: Int): List<DrawPoint>
    fun clearPage(page: Int)

    val mostRecentPage: Int

    fun addPage(ar: Float)
    fun getAR(page: Int): Float
    fun getMaxPage(fallbackAR: Single<Float>): Observable<Int>
}
