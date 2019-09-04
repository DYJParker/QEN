package tech.jpco.qen.model

import android.content.Context
import io.reactivex.Observable
import io.reactivex.Single
import tech.jpco.qen.viewModel.DrawPoint

class MyPagesRepository private constructor(ctx: Context) : PagesRepository by Firebase {
    companion object : SingletonHolder<MyPagesRepository, Context>(::MyPagesRepository)
}

interface PagesRepository {
    fun addTouchStream(inStream: Observable<DrawPoint>, pageStream: Observable<Int>): Observable<DrawPoint>
    fun clearPage(page: Int)

    val mostRecentPage: Int

    //    var currentPageClearedStream: Observable<Int>
    fun setCurrentPageClearedListener(pageStream: Observable<Int>): Observable<Int>

    fun addPage(ar: Float)

    //TODO refactor this to return a nullable list instead of an empty one
    fun getPage(
        page: Int,
        retrieveContents: Boolean = true
    ): Pair<List<DrawPoint>, Float>

    fun getMaxPage(fallbackAR: Single<Float>): Observable<Int>
}
