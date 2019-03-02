package tech.jpco.qen.model

import android.content.Context
import io.reactivex.Observable
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.MetaEvent
import tech.jpco.qen.viewModel.MetaState

class MyUserRepository private constructor(ctx: Context) : UserRepository {
    companion object : SingletonHolder<MyUserRepository, Context>(::MyUserRepository)

    private val sqlInstance = SQL.getInstance(ctx.applicationContext)

    override fun addTouchStream(inStream: Observable<DrawPoint>): Observable<DrawPoint> =
        inStream
            .compose { sqlInstance.addTouchStream(it) }

    override fun addMetaStream(inStream: Observable<MetaEvent>): Observable<MetaState> {
        return sqlInstance.addMetaStream(inStream)
    }
}

interface UserRepository {
    fun addTouchStream(inStream: Observable<DrawPoint>): Observable<DrawPoint>
    fun addMetaStream(inStream: Observable<MetaEvent>): Observable<MetaState>
}
