package tech.jpco.qen

import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import io.reactivex.observers.DefaultObserver
import io.reactivex.observers.TestObserver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler
import tech.jpco.qen.RxJavaMulticastingTests.DummyEmission.*
import java.util.concurrent.TimeUnit

//TODO for complete proof, rewrite this with injection of original, problematic .publish() as well as the working .replay(1).refcount()
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RxJavaMulticastingTests {
    @Test
    fun `take & merge test`() {
        runner(listAddition = {
            take(1).map { D(it) }
        })
    }

    @Test
    @ExtendWith(Negator::class)
    fun `take & startWith test`() {
        runner(afterEffect = { ARs ->
            startWith(ARs.take(1).map { D(it) })
        })
    }

    @Test
    fun `first & merge test`() {
        runner(listAddition = {
            firstOrError().map { D(it) }.toObservable().cast(DummyEmission::class.java)
        })
    }

    @Test
    @ExtendWith(Negator::class)
    fun `first & startWith test`() {
        runner(afterEffect = { ARs ->
            startWith(ARs.firstOrError().map { D(it) }.toObservable())
        })
    }

    @Test
    @ExtendWith(Negator::class)
    fun `take & concatWith test`() {
        runner(preEffect = { ARs ->
            ARs.take(1).map { D(it) }.cast(DummyEmission::class.java).concatWith(this)
        })
    }

    @Test
    @ExtendWith(Negator::class)
    fun `first & concatWith test`() {
        runner(preEffect = { ARs ->
            ARs.firstOrError().map { D(it) }.cast(DummyEmission::class.java).toObservable().concatWith(this)
        })
    }

    private fun runner(
        listAddition: (Observable<Int>.() -> Observable<DummyEmission>)? = null,
        afterEffect: Effect = null,
        preEffect: Effect = null
    ) {
        if (listOf(listAddition, afterEffect, preEffect).count { it != null } == 0) throw IllegalArgumentException()



        fun <T> streamGenerator(
            t: T,
            millis: Long,
            input: Observable<T> = Observable.just(t).repeat(5)
        ) = Observable.zip(
            input,
            Observable.interval(millis, TimeUnit.MILLISECONDS),
            BiFunction { emission: T, _: Long -> emission }
        )

        val aStream = streamGenerator(A, 130)
        val bStream = streamGenerator(B, 280)
        val cStreamGenerator = { ar: Int -> streamGenerator(C(ar), 25) }

        val arStream = streamGenerator(1, 60, Observable.range(1, 3))

        var millis: Long = 0

        fun generateTester(
            lam: Observable<Int>.() -> Observable<DummyEmission>,
            debug: Boolean
        ): TestObserver<DummyEmission> {

            val tester = if (!debug) TestObserver.create<DummyEmission>() else {
                fun dateDif(): Long {
                    if (millis == 0L) throw IllegalStateException()
                    return System.currentTimeMillis() - millis
                }

                val debugger = object : DefaultObserver<DummyEmission>() {
                    override fun onComplete() = println("Completed at ${dateDif()}\n")

                    override fun onNext(t: DummyEmission) = println("$t at ${dateDif()}")

                    override fun onError(e: Throwable) = println("$e at ${dateDif()}")
                }

                TestObserver.create(debugger)
            }

            millis = 0

            return arStream.replay(1).refCount().lam()
                .also { if (debug) millis = System.currentTimeMillis() }
                .subscribeWith(tester)
        }


        fun generateStream() = { ARs: Observable<Int> ->
            val effectRunner: Observable<DummyEmission>.(Effect) -> Observable<DummyEmission> = {
                it?.invoke(this, ARs) ?: this
            }

            Observable.merge(
                mutableListOf(
                    aStream,
                    bStream,
                    ARs.switchMap(cStreamGenerator).take(5)
                ).apply {
                    if (listAddition != null) add(ARs.listAddition())
                }
            )
                .effectRunner(preEffect)
                .effectRunner(afterEffect)
        }


        fun assertOrder(tester: TestObserver<DummyEmission>) {
            tester.await().assertValueSequence(
                listOf(
                    D(1),
                    C(1),
                    C(1),
                    A,
                    C(2),
                    C(2),
                    C(3),
                    A,
                    B,
                    A,
                    A,
                    B,
                    A,
                    B,
                    B,
                    B
                )
            )
        }

        assertOrder(generateTester(generateStream(), false))


    }

    sealed class DummyEmission {
        object A : DummyEmission()
        object B : DummyEmission()
        data class C(val z: Any) : DummyEmission()
        data class D(val z: Any) : DummyEmission()

    }

    private object Negator : TestExecutionExceptionHandler {
        override fun handleTestExecutionException(context: ExtensionContext?, throwable: Throwable?) {
            System.err.println("Test \"${context?.displayName}\" failed as expected, with throwable: \n$throwable\n")
        }
    }

}

typealias Effect = (Observable<RxJavaMulticastingTests.DummyEmission>.(Observable<Int>) -> Observable<RxJavaMulticastingTests.DummyEmission>)?