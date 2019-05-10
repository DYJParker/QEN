package tech.jpco.qen.model

import com.google.firebase.database.*
import durdinapps.rxfirebase2.RxFirebaseDatabase
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.observers.TestObserver
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import tech.jpco.qen.model.Firebase.UID
import tech.jpco.qen.model.Firebase.arKey
import tech.jpco.qen.model.Firebase.awaitMaxPageAndSetIfAbsent
import tech.jpco.qen.model.Firebase.getMaxPage
import tech.jpco.qen.model.Firebase.pages
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.TouchEventType
import tech.jpco.qen.viewModel.iLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.isSubclassOf

typealias DatabaseTestAction = (DatabaseReference.(DatabaseReference.CompletionListener) -> Unit)?

private const val DEBUG = true

internal class FirebaseKtTest : FirebaseTestTooling() {
    val execAction: DatabaseTestAction.(DatabaseReference) -> Unit = { ref ->
        this?.let {
            val latch = CountDownLatch(1)
            ref.it(DatabaseReference.CompletionListener { databaseError, _ ->
                assertNull("fb setvalue erred", databaseError)
                dLogger("Latch counting down", latch)
                latch.countDown()
            })
            dLogger("Latch awaiting", latch)
            latch.await(4_500, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun newPageTransactionTest() {
        val value = transactionTestPrototype(mockAR, null)

        dLogger("Assertions running")
        assertEquals("Transaction did not return the new page #", 1, value)
    }

    @Test
    fun exPageTransactionTest() {
        val value = transactionTestPrototype(0f, { child("3").setValue(true, it) })

        dLogger("Assertions running")
        assertEquals("Transaction did not return the existing page #", 3, value)
    }

    @Test
    fun transAwaitExternalPageSetTest() {
        val value = transactionTestPrototype(0f, { setValue(false, it) }) {
            Thread.sleep(1000)
            child("5").setValue(true, it)
        }

        dLogger("Assertions running")
        assertEquals("Transaction did not return the supplied page #", 5, value)
    }

    @Test
    fun transExternalPageSetTakesTooLongTest() {
        val value = transactionTestPrototype(0f, { setValue(false, it) }) {
            Thread.sleep(3_500)
            child("5").setValue(true, it)
        }

        dLogger("Assertions running")
        assertEquals("Transaction did not time out and set first page", 1, value)
    }

    @Throws(java.lang.AssertionError::class)
    private fun transactionTestPrototype(
        finalAR: Float,
        preAction: DatabaseTestAction,
        postAction: DatabaseTestAction = null
    ): Int {
        val pages = pages


        preAction.execAction(pages)

        val tO = pages.awaitMaxPageAndSetIfAbsent(Single.just(finalAR)).test()

        postAction.execAction(pages)

        return tO.await().assertValueCount(1).values()[0]
    }

    @Test
    fun getMaxPageFromBlankTest() {
        dLogger("-----------")
        maxPageTestPrototype().also { dLogger("Assertions running") }.assertValueCount(1).assertValueAt(0, 1)
        dLogger("-----------")
    }

    @Test
    fun getMaxPageWithExternalSetTest() {
        maxPageTestPrototype {
            child("7").setValue(true, it)
        }.also { dLogger("Assertions running") }.assertValueCount(2).assertValueAt(1, 7)
    }

    @Test
    fun getMaxPageMultipleTimes() {

    }

    private fun maxPageTestPrototype(postAction: DatabaseTestAction = null): TestObserver<Int> {
        val firebaseRepo = Firebase
        firebaseRepo.setTesting(true)

        val tO = firebaseRepo.getMaxPage(sMockAR).doOnError { dLogger("Error??", it) }
            .doAfterNext {
                dLogger("test stream emitted", it)
                if (it == 1) postAction.execAction(pages)
            }
            .test().assertNoErrors()

        tO.await(5, TimeUnit.SECONDS)
        dLogger("tO's await expired")
        return tO
    }

    @Test
    fun addSinglePageTest() {
        addPageTestPrototype()
    }

    @Test
    fun addMultiplePagesTest() {
        addPageTestPrototype(3) {
            lateinit var d: Disposable
            val maxPageStream =
                getMaxPage(Single.just(1000f))
                    .doOnSubscribe { dLogger("aMPT subscribed to getMaxPage()") }
                    .doOnNext { page ->
                        dLogger(
                            "addMultiplePagesTest() received from getMaxPage()",
                            page
                        )
                    }.publish().apply { connect { d = it } }

            val maxPageAwait: Completable.() -> Completable =
                { mergeWith(maxPageStream.firstOrError().ignoreElement()) }

            Firebase.addPage(3f)/*.blockingAwait()
                    Completable.complete()*/
                .maxPageAwait()
                .andThen(/*Completable.defer{
                    dLogger("reached defer's lambda")*/
                    it()
                    /*}*/
                )
                .maxPageAwait()
                .blockingAwait(8, TimeUnit.SECONDS).also { dLogger("aMPT()'s await finished in time", it) }
            d.dispose()
        }
    }

    @Throws(AssertionError::class)
    private fun addPageTestPrototype(
        numberExpectedEntries: Int = 2,
        action: (() -> Completable) -> Unit = { it().blockingAwait(2, TimeUnit.SECONDS) }
    ) {
        Firebase.setTesting(true)

        getMaxPage(Single.just(1f)).test().awaitCount(1)

        action {
            dLogger("reached inside action's lambda")
            Firebase.addPage(4f).also { dLogger("came back from action's lambda") }
        }

        singleValueTestRunner(pages) { snapshot ->
            dLogger("assertions running")
            assertEquals(
                "Pages list has the wrong number of entries",
                numberExpectedEntries,
                snapshot.childrenCount.toInt()
            )
            assertEquals(
                "Did not set AR correctly",
                4f,
                snapshot.children.last().child(arKey).value.let { (it as Number).toFloat() })
            assertEquals(
                "add page did not successfully increment the page number",
                numberExpectedEntries,
                snapshot.children.last().key!!.toInt()
            )
        }

        /*firebase.child("pages").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onCancelled(p0: DatabaseError) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun onDataChange(p0: DataSnapshot) {
                assertEquals(
                    "Pages list has the wrong number of entries",
                    numberExpectedEntries,
                    p0.childrenCount.toInt()
                )
                assertEquals(
                    "Did not set AR correctly",
                    4f,
                    p0.children.last().child(arKey).value.let { (it as Number).toFloat() })
                assertEquals(
                    "add page did not successfully increment the page number",
                    numberExpectedEntries,
                    p0.children.last().child(pageKey).value.let { (it as Long).toInt() }
                )
            }

        })*/

    }

    @Test
    fun singlePageTouchStreamTest() {
        touchStreamTestPrototype {
            addTouchStream(touchStreamTestContent.it(), Observable.just(10))
        }.assertValuesOnly(*touchStreamTestContent.toTypedArray())

        pageContributorListTestPrototype(10)
    }

    @Test
    fun twoPageTouchStreamTest() {
        val pages = listOf(1, 2)
        touchStreamTestPrototype {
            val pageStream = Observable.fromIterable(pages)
            addTouchStream(
                touchStreamTestContent.it().delaySubscription(pageStream.skip(1)),
                pageStream
            )
        }.assertValuesOnly(*touchStreamTestContent.toTypedArray())

        pages.forEach { pageContributorListTestPrototype(it) }
    }

    private val touchStreamTestContent = List(10) {
        DrawPoint(
            it.toFloat(),
            it.toFloat(),
            when (it) {
                0 -> TouchEventType.TouchDown
                9 -> TouchEventType.TouchUp
                else -> TouchEventType.TouchMove
            }
        )
    }

    private fun touchStreamTestPrototype(content: Firebase.(List<DrawPoint>.() -> Observable<DrawPoint>) -> Observable<DrawPoint>): TestObserver<DrawPoint> {
        Firebase.setTesting(true)

        val tO = Firebase.content {
            Observable.interval(5, TimeUnit.MILLISECONDS).map { this[it.toInt()] }.take(this.size.toLong())
        }.test()

        return tO.apply { await(2, TimeUnit.SECONDS) } //OF COURSE this is going to time out, it's an infinite stream!
    }

    private fun pageContributorListTestPrototype(page: Int) =
        RxFirebaseDatabase.observeSingleValueEvent(Firebase.touchHistory.child(page.toString()))
            .doOnSuccess {
                assertEquals(
                    "Number-only page listing didn't list my UID",
                    true,
                    it.hasChild(UID)
                )
            }.test().await().assertValueCount(1)

    @Test
    fun getPagesARTest() {
        Firebase.setTesting(true)
        val maxPageStream =
            Firebase.getMaxPage(Single.just(0.25f)).log("maxPageStream").share()
                .apply { test().awaitCount(1).assertOf { dLogger("maxPageStream fulfilled its await") } }
        dLogger("ramping up to concat")
        Completable.concat(List(7) {
            dLogger("iterated", it)
            val ar = (it + 2) / 4f
            Firebase.addPage(ar).doOnComplete { dLogger("${it}th page added") }
                .ambWith(maxPageStream.firstOrError().doOnSuccess { max ->
                    dLogger(
                        "submitted",
                        ar to max
                    )
                }.ignoreElement())
        }).blockingAwait(10, TimeUnit.SECONDS).also { dLogger("blocking await returned in time", it) }

        singleValueTestRunner(Firebase.pages.also { dLogger("", it.path) }.orderByChild(arKey).equalTo(1.25)) {
            assertEquals("Did not return the right page", 5, it.children.first().key!!.toInt())
        }
    }


}

open class FirebaseTestTooling {
    companion object Constants {
        val firebase = FirebaseDatabase.getInstance().getReference("Testing")
        const val mockAR = 2f
        val sMockAR = Single.just(mockAR)
        val initPagesOutput: List<Any> = listOf(mapOf(Push() to true, "AR" to mockAR))
        val push get() = Push()

        data class Push(val content: Any = true) {
            /*override fun equals(other: Any?): Boolean {
                if()
                return super.equals(other)
            }*/
        }

        const val NESTED_DATA = "nested data"
        const val A_SINGLE = "a single"
        const val PUSH_CHARS = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"
        const val PUSHED = "_pushed_"
    }

    protected fun <T> Observable<T>.log(name: String): Observable<T> =
        doOnComplete { dLogger("$name completed") }
            .doOnDispose { dLogger("$name got disposed") }
            .doOnEach { dLogger("$name emitted", it.value) }
            .doOnSubscribe { dLogger("$name was subscribed") }

    protected fun dLogger(output: String, obj: Any? = Unit) = if (DEBUG) this.iLogger(output, obj) else Unit

    protected val singleValueTestRunner: (Query, (DataSnapshot) -> Unit) -> Unit = { ref, test ->
        RxFirebaseDatabase.observeSingleValueEvent(ref).test().apply {
            assertTrue("SUT timed out!", await(10, TimeUnit.SECONDS))
        }.assertNoErrors().assertValueCount(1).values()[0].also(
            test
        )
    }

    protected fun messageFormat(expect: Any?, found: Any?): String = "Expected $expect, found $found"

    @Throws(java.lang.IllegalStateException::class, AssertionError::class)
    protected fun DataSnapshot.assertEqualWithPush(comparison: Any?) {
        firebaseTypeTest(comparison)

        if (!hasChildren()) {
            assertTrue(messageFormat(NESTED_DATA, value.klass()), !(comparison is List<*> || comparison is Map<*, *>))
            if (comparison is Double && comparison % 1 == 0.0) {
                assertTrue(messageFormat(comparison, value), value == comparison.toLong())
            } else assertTrue(messageFormat(comparison, value), value == comparison)
        } else {

            assertTrue(
                messageFormat(A_SINGLE, NESTED_DATA),
                comparison?.let {
                    it::class.isSubclassOf(Map::class) || it::class.isSubclassOf(List::class)
                } ?: false
            )
//            fail("Did not test the received nested data: ${value.klass()}")
        }
    }

    @Before
    fun setup() {
        val setupLatch = CountDownLatch(1)
        firebase.removeValue { dbError, _ ->
            if (dbError != null) throw IllegalStateException()
            dLogger("Setup executed")
            setupLatch.countDown()
        }
        setupLatch.await()
    }

    protected fun firebaseTypeTest(toBeChecked: Any?) {
        if (
            listOf(
                String::class,
                Long::class,
                Double::class,
                Boolean::class,
                Map::class,
                List::class,
                null,
                Push::class
            ).count { klass ->
                klass?.let {
                    toBeChecked.klass()?.isSubclassOf(it)
                } ?: (toBeChecked == null)
            } == 0
        ) throw IllegalStateException()
    }


}

internal class FirebaseToolingTests : FirebaseTestTooling() {


    @Rule
    @JvmField
    val exception: ExpectedException = ExpectedException.none()

    private fun aEWP_setup(firebaseValue: Any?, reference: Any? = Unit) {
        firebaseTypeTest(firebaseValue)

        val latch = CountDownLatch(1)


        if (firebaseValue is Constants.Push) firebase.push().setValue(firebaseValue.content)
        else firebase.setValue(firebaseValue)

        var err: Throwable? = null
        firebase.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onCancelled(p0: DatabaseError) {
                TODO("not implemented")
            }

            override fun onDataChange(p0: DataSnapshot) {

                try {
                    p0.assertEqualWithPush(if (reference == Unit) firebaseValue else reference)
                } catch (throwable: Throwable) {
                    err = throwable
                } finally {
                    latch.countDown()
                }

            }
        })

        latch.await()

        err?.let { throw it }
    }

    @Test
    fun aEWP_allSinglesInAndOut() = listOf("string", 1L, 2.0, false, null).forEach { aEWP_setup(it) }

    @Test
    fun aEWP_invalidOriginalNoRefStandard() {
        exception.expect(IllegalStateException::class.java)
        aEWP_setup(object {})
    }

    @Test
    fun aEWP_invalidRefStandard() {
        exception.expect(IllegalStateException::class.java)
        aEWP_setup("test", object {})
    }

    @Test
    fun aEWP_mismatchedSingles() {
        setupMismatch("a string", null)
    }

    @Test
    fun aEWP_emitCollectionCompareSingle() {
        setupMismatch(listOf(1, 2, 3), "a string", NESTED_DATA, A_SINGLE)
    }

    @Test
    fun aEWP_emitSingleCompareCollection() = setupMismatch("a string", listOf<Any>(), String::class, NESTED_DATA)

    @Test
    fun aEWP_emitMapCompareString() {
//        setupMismatch()
    }

    private fun setupMismatch(
        toFB: Any?,
        referenceSample: Any?,
        formattedTo: Any? = null,
        formattedRef: Any? = null
    ) {
        val fb = formattedTo ?: toFB
        val ref = formattedRef ?: referenceSample
        exception.expect(java.lang.AssertionError::class.java)
        exception.expectMessage(messageFormat(ref, fb))
        aEWP_setup(toFB, referenceSample)
    }

    @Test
    fun aEWP_push() {
        aEWP_setup(push, mapOf(push to true, push to true))
    }
}

fun Any?.klass() = this?.let { it::class }