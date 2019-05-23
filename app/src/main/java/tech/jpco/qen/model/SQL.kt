package tech.jpco.qen.model

import android.annotation.SuppressLint
import android.content.Context
import com.squareup.sqldelight.EnumColumnAdapter
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.squareup.sqldelight.runtime.rx.asObservable
import com.squareup.sqldelight.runtime.rx.mapToOneNonNull
import io.reactivex.Observable
import io.reactivex.Single
import tech.jpco.qen.Database
import tech.jpco.qen.iLogger
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.TouchEventType

class SQL private constructor(ctx: Context) : PagesRepository {
    companion object : SingletonHolder<SQL, Context>(::SQL)

    private val queries = Database(
        AndroidSqliteDriver(Database.Schema, ctx, "QEN.db"),
        TouchHistory.Adapter(EnumColumnAdapter())
    ).mainQueries

    private val maxPageQuery by lazy { queries.getMaxPage() }
    override fun getMaxPage(fallbackAR: Single<Float>): Observable<Int> =
        maxPageQuery
            .asObservable()
            .map {
                it.executeAsOneOrNull() ?: run {
                    addPage(fallbackAR.blockingGet())
                    maxPageQuery.executeAsOne()
                }
            }
            .distinctUntilChanged()

    private val mostRecentPageQuery by lazy { queries.getMostRecentPage() }
    override val mostRecentPage: Int
        get() = mostRecentPageQuery.executeAsOne()


    private fun getSelectedPagePoints(page: Int): List<DrawPoint> =
        queries.getPage(page, mapper).executeAsList()

    override fun clearPage(page: Int) {
        queries.clearPage(page)
        if (queries.getPage(page).executeAsList().isNotEmpty())
            throw IllegalStateException("Page was not cleared!")
    }

    override fun addPage(ar: Float) = queries.addPage(ar)

    override fun getPage(
        page: Int,
        retrieveContents: Boolean
    ): Pair<List<DrawPoint>, Float> = Pair(
        if (retrieveContents) getSelectedPagePoints(page) else listOf(),
        queries.getAR(page).executeAsOne()
    )

    private val mapper = { x: Float, y: Float, type: TouchEventType -> DrawPoint(x, y, type) }

    @SuppressLint("CheckResult")
    override fun addTouchStream(inStream: Observable<DrawPoint>, pageStream: Observable<Int>): Observable<DrawPoint> {
        pageStream
            .switchMap { currentPage: Int ->
                inStream.map { Pair(currentPage, it) }
            }
            .subscribe {
                iLogger("SQL touch input", it)
                val (currentPage, point) = it
                point.run { queries.addTouch(x, y, type, currentPage) }
            }

        return pageStream.switchMap { currentPage: Int ->
            queries.mostRecentTouch(currentPage, mapper)
                .asObservable()
                .doOnNext { iLogger("Page #$currentPage wtf", it.executeAsOneOrNull()) }
                .mapToOneNonNull()
                .doOnNext { iLogger("SQL touch output from page $currentPage", it) }
        }
    }
}