package tech.jpco.qen

import android.util.Log
import io.reactivex.*
import io.reactivex.disposables.Disposable

fun <T> Observable<T>.log(name: String, origin: Any): Observable<T> =
    doOnComplete(origin.completer(name))
        .doOnDispose(origin.disposer(name))
        .doOnNext { origin.iLogger("$name emitted", it) }
        .doOnSubscribe(origin.subscriber(name))

fun Completable.log(name: String, origin: Any): Completable =
    doOnSubscribe(origin.subscriber(name))
        .doOnDispose(origin.disposer(name))
        .doOnEvent { origin.iLogger("$name completed", it ?: "successfully") }

fun <T> Single<T>.log(name: String, origin: Any): Single<T> =
    doOnSubscribe(origin.subscriber(name))
        .doOnEvent { content: T?, throwable: Throwable? ->
            origin.iLogger(
                "$name returned",
                throwable ?: content ?: "null value"
            )
        }
        .doOnDispose(origin.disposer(name))

fun <T> Flowable<T>.log(name: String, origin: Any): Flowable<T> =
    doOnSubscribe { origin.iLogger("$name was subscribed") }
        .doOnEach { origin.iLogger("$name evented", it.value ?: it.error ?: "onComplete") }
        .doOnCancel { origin.iLogger("$name got canceled") }

fun <T> Maybe<T>.log(name: String, origin: Any): Maybe<T> =
    doOnSubscribe { origin.iLogger("$name was subscribed") }
        .doOnEvent { content, throwable ->
            iLogger(
                "$name returned",
                throwable ?: content ?: "a completion"
            )
        }

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