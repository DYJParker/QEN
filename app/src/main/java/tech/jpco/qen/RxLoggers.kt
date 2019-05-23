package tech.jpco.qen

import android.util.Log
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.disposables.Disposable

fun <T> Observable<T>.log(name: String, origin: Any): Observable<T> =
    doOnComplete(origin.completer(name))
        .doOnDispose(origin.disposer(name))
        .doOnEach { origin.iLogger("$name emitted", it.value) }
        .doOnSubscribe(origin.subscriber(name))

fun Completable.log(name: String, origin: Any): Completable =
    doOnSubscribe(origin.subscriber(name))
        .doOnDispose(origin.disposer(name))
        .doOnEvent { origin.iLogger("$name completed", it ?: "successfully") }

private fun Any.completer(name: String): () -> Unit =
    { iLogger("$name completed") }

private fun Any.disposer(name: String): () -> Unit =
    { iLogger("$name got disposed") }

private fun Any.subscriber(name: String): (Disposable) -> Unit =
    { iLogger("$name was subscribed") }

val Any.TAG
    get() = this::class.simpleName ?: "Anon"

fun Any.iLogger(output: String, obj: Any? = Unit) {
    val name = Thread.currentThread().name
    val objS = if (obj == Unit) "" else ": $obj"
    Log.d(TAG, "$output$objS on ${name.substring(0, 1).toUpperCase()}${name.substring(1)}")
}