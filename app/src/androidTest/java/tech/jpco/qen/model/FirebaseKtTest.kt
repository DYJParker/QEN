package tech.jpco.qen.model

import com.google.firebase.database.*
import durdinapps.rxfirebase2.RxFirebaseDatabase
import durdinapps.rxfirebase2.RxFirebaseDatabase.observeSingleValueEvent
import durdinapps.rxfirebase2.RxFirebaseDatabase.setValue
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.observers.BaseTestConsumer
import io.reactivex.observers.TestObserver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import tech.jpco.qen.iLogger
import tech.jpco.qen.log
import tech.jpco.qen.model.Firebase.UID
import tech.jpco.qen.model.Firebase.addNewPage
import tech.jpco.qen.model.Firebase.addTouchStream
import tech.jpco.qen.model.Firebase.arKey
import tech.jpco.qen.model.Firebase.awaitMaxPageAndSetIfAbsent
import tech.jpco.qen.model.Firebase.getMaxPage
import tech.jpco.qen.model.Firebase.getPage
import tech.jpco.qen.model.Firebase.iClearPage
import tech.jpco.qen.model.Firebase.mostRecentPage
import tech.jpco.qen.model.Firebase.pageUID
import tech.jpco.qen.model.Firebase.pages
import tech.jpco.qen.model.Firebase.touchHistory
import tech.jpco.qen.model.Firebase.uids
import tech.jpco.qen.viewModel.DrawPoint
import tech.jpco.qen.viewModel.TouchEventType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.isSubclassOf

typealias DatabaseTestAction = (DatabaseReference.() -> Completable)?

private const val DEBUG = true

private val exec = Executors.newFixedThreadPool(1)

internal class FirebaseKtTest : FirebaseTestTooling() {
    val execAction: DatabaseTestAction.(DatabaseReference) -> Unit = { ref ->
        this?.let {
            ref.it().subscribe()
        }
    }

    private fun DatabaseReference.rxSetValue(value: Any) =
        RxFirebaseDatabase.setValue(this, value)

    @Test
    fun newPageTransactionTest() {
        val value = transactionTestPrototype(mockAR, null)

        dLogger("Assertions running")
        assertEquals("Transaction did not return the new page #", 1, value)
    }

    @Test
    fun exPageTransactionTest() {
        val value = transactionTestPrototype(0f, { child("3").rxSetValue(true) })

        dLogger("Assertions running")
        assertEquals("Transaction did not return the existing page #", 3, value)
    }

    @Test
    fun transAwaitExternalPageSetTest() {
        val value = transactionTestPrototype(0f, { rxSetValue(false) }) {
            Thread.sleep(1000)
            child("5").rxSetValue(true)
        }

        dLogger("Assertions running")
        assertEquals("Transaction did not return the supplied page #", 5, value)
    }

    @Test
    fun transExternalPageSetTakesTooLongTest() {
        val value = transactionTestPrototype(0f, { rxSetValue(false) }) {
            Thread.sleep(3_500)
            child("5").rxSetValue(true)
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

        val tO = awaitMaxPageAndSetIfAbsent(Single.just(finalAR)).test()

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
            dLogger("gMPWEST action")
            child("7").rxSetValue(mapOf(arKey to 0)).log("gMPWEST", this@FirebaseKtTest)
        }.also { dLogger("Assertions running") }.assertValueCount(2).assertValueAt(1, 7)
    }

    private fun maxPageTestPrototype(postAction: DatabaseTestAction = null): TestObserver<Int> {
        Firebase.setTesting(true)

        val tO = Firebase.getMaxPage(sMockAR).doOnError { dLogger("Error??", it) }
            .doAfterNext {
                dLogger("test stream emitted", it)
                if (it == 1) postAction.execAction(pages)
            }
            .test().assertNoErrors()

        tO.await(2, TimeUnit.SECONDS)
        dLogger("tO's await expired")
        return tO
    }

    @Test
    fun emptyMostRecentPageTest() {
        exception.expect(IllegalStateException::class.java)
        mostRecentPage
    }

    @Test
    fun mostRecentPagePerAddNewPageTest() {
        assertEquals(
            "addNewPage() didn't set the page to most recent",
            999,
            mostRecentPageTestPrototype { Firebase.addNewPage(999, 0f) }
        )
    }

    @Test
    fun mostRecentPagePerTouchStreamTest() {
        assertEquals(
            "addTouchStream() did not set the UI's new page to most recent",
            99,
            mostRecentPageTestPrototype {
                addTouchStream(
                    Observable.just(DrawPoint(0f, 0f)), Observable.just(99)
                ).firstOrError().ignoreElement()
            }
        )
    }

    private fun mostRecentPageTestPrototype(postAction: (() -> Completable)): Int {
        fun Completable.awaitAssert() = blockingAwait(5, TimeUnit.SECONDS)
            .also { assertTrue("getMaxPage() timed out!", it) }

        getMaxPage(Single.just(0f)).firstOrError().ignoreElement().awaitAssert()

        postAction().awaitAssert()

        return mostRecentPage
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

            Firebase.iAddPage(3f)
                .maxPageAwait()
                .andThen(it())
                .maxPageAwait()
                .blockingAwait(4, TimeUnit.SECONDS).also { dLogger("aMPT()'s await finished in time", it) }
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
            Firebase.iAddPage(4f).also { dLogger("came back from action's lambda") }
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
            val pageStream = Observable.fromIterable(pages).share()
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
        RxFirebaseDatabase.observeSingleValueEvent(Firebase.pages.child("$page/$uids"))
            .doOnSuccess {
                dLogger("pCLTP assert running")
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
            Firebase.iAddPage(ar).doOnComplete { dLogger("${it}th page added") }
                .ambWith(maxPageStream.firstOrError().doOnSuccess { max ->
                    dLogger("submitted", ar to max)
                }.ignoreElement())
        }).blockingAwait(10, TimeUnit.SECONDS).also { dLogger("blocking await returned in time", it) }

        singleValueTestRunner(Firebase.pages.orderByChild(arKey).equalTo(1.25)) {
            assertEquals("Did not return the right page", 5, it.children.first().key!!.toInt())
        }
    }

    @Test
    fun clearPageUnitTest() {
        syntheticPageAndTouchHistoryTestPrototype { uidIndexBefore, touchListingBefore, uidIndexToIndexedUID ->

            iClearPage(2).log("clearPageUnit", this).blockingAwait(2, TimeUnit.SECONDS)

            val toBeRemoved = uidIndexBefore.filter { it.key == "2" }
            val uidIndexAfter = uidIndexBefore - "2"
            val touchListingAfter = touchListingBefore - toBeRemoved.flatMap(uidIndexToIndexedUID)

            observeSingleValueEvent(touchHistory)
                .singleEventTest<Any>()
                .touchComparo(touchListingAfter)

            observeSingleValueEvent(pages)
                .singleEventTest<Map<String, Any>>()
                .pagesComparo(uidIndexAfter)
        }
    }

    private fun syntheticPageAndTouchHistoryTestPrototype(
        test: (
            Map<String, Map<String, Map<String, Boolean>>>,
            Map<String, List<DrawPoint>>,
            ((Map.Entry<String, Map<String, Map<String, Boolean>>>) -> List<String>)
        ) ->
        Unit
    ) {
        var runningOffset = 0
        fun drawPointList(size: Int) = List(size) {
            val xy = (it + runningOffset).toFloat()
            DrawPoint(xy, xy)
        }.also { runningOffset += size }


        fun uidsWrap(inner: Map<String, Boolean>) = mapOf(uids to inner)
        val uidIndexBefore = mapOf(
            "1" to uidsWrap(mapOf("AAA" to true)),
            "2" to uidsWrap(listOf("AAA", "BBB", "CCC").associateWith { true }),
            "10" to uidsWrap(mapOf("CCC" to true))
        )

        val uidIndexToIndexedUID = { pageEntry: Map.Entry<String, Map<String, Map<String, Boolean>>> ->
            pageEntry.value.getValue(uids).map { pageUID(pageEntry.key.toInt(), it.key) }
        }

        val touchListingBefore = uidIndexBefore.flatMap(uidIndexToIndexedUID)
            .associateWith { drawPointList(3) }

        RxFirebaseDatabase.updateChildren(pages, uidIndexBefore)
            .andThen(
                RxFirebaseDatabase.updateChildren(touchHistory, touchListingBefore)
            ).blockingAwait(2, TimeUnit.SECONDS)

        test(uidIndexBefore, touchListingBefore, uidIndexToIndexedUID)
    }

    @Test
    fun clearPageInputIntegrationTest() {
        val pages = listOf(1, 2, 3, 5)
        val touches = List(20) { DrawPoint(it.toFloat(), it.toFloat()) }

        val countMap = mapOf(2 to 5, 3 to 10, 5 to 5)


        Observable.fromIterable(pages)
            .concatMap {
                var startIndex = 0
                Observable.concat(
                    addNewPage(it, it.toFloat()).toSingleDefault(it).toObservable(),
                    countMap[it]?.let { currentCount ->
                        Observable.fromIterable(touches.slice(startIndex.until(startIndex + currentCount)))
                            .apply { startIndex += currentCount }
                    } ?: Observable.empty()
                )//.concatMapSingle { Single.just(it).delay(5, TimeUnit.MILLISECONDS) }
            }
            .log("combiStream")
            .publish {
                addTouchStream(
                    it.ofType(DrawPoint::class.java),
                    it.ofType(Int::class.javaObjectType)
                )
            }
            .log("touchStreamResult")
            .test()
            .awaitCount(20, BaseTestConsumer.TestWaitStrategy.SLEEP_10MS)
            .apply { await(1, TimeUnit.SECONDS) }
            .assertValueCount(20)

        iClearPage(3).blockingAwait(2, TimeUnit.SECONDS)

        observeSingleValueEvent(touchHistory).singleEventTest<Any>()
            .touchComparo(
                {
                    var startIndex = 0
                    countMap.mapValues {
                        touches.slice(startIndex.until(startIndex + it.value)).apply { startIndex += it.value }
                    } - 3
                }.invoke().mapKeys { pageUID(it.key) }
            )

        observeSingleValueEvent(Firebase.pages).singleEventTest<Map<String, Any>>()
            .pagesComparo(
                (pages - 3).associate { it.toString() to mapOf(uids to mapOf(UID to true)) }
            )
    }

    private fun Map<String, Map<String, Any>>.pagesComparo(expectedPagesMap: Map<String, Map<String, Map<String, Boolean>>>) {
        dLogger("clearPagesComp expected", expectedPagesMap)
        dLogger("clearPagesComp actual", this)
        filter { it.value[uids] != null }
            .forEach { entry: Map.Entry<String, Map<String, Any>> ->
                assertEquals(
                    "Firebase did not return the correct UID index for ${entry.key}",
                    expectedPagesMap.getValue(entry.key).getValue(uids).keys,
                    (entry.value.getValue(uids) as Map<String, Boolean>).keys
                )
            }
        assertEquals(
            "Firebase returned too many UID indices",
            expectedPagesMap.count { it.value[uids] != null },
            count { it.value.apply { dLogger("", this) }[uids] != null }
        )
    }

    //firebase actually returns Map<String, Map<String, Map<String, Any>>>
    private fun Map<String, Any>.touchComparo(expectedTouchMap: Map<String, List<DrawPoint>>) {
        dLogger("clearTouchComp expected", expectedTouchMap)
        dLogger("clearTouchComp actual", this)

        fun Map<String, Map<String, Any>>.toList(): List<Map<String, Any>> {
            return map { it.value }
        }

        this.toSortedMap().mapValues { numberUidMapEntry ->
            try {
                (numberUidMapEntry.value as Map<String, Map<String, Any>>).toSortedMap().map { pushMapEntry ->
                    pushMapEntry.value.mapValues { it.value }
                }
            } catch (e: ClassCastException) {
                numberUidMapEntry.value as List<Map<String, Any>>
            }
        }.also { dLogger("reformatted touch list", it) }

        assertEquals("Firebase and exemplar size did not match", expectedTouchMap.size, this.size)
    }

    @Test
    fun getPageArAbsentFailureTest() {
        exception.expect(IllegalStateException::class.java)
        getPage(1, false)
    }

    @Test
    fun getPageArTest() {
        val expectedAR = -1.5f
        setValue(pages.child("1/$arKey"), expectedAR).blockingAwait(2, TimeUnit.SECONDS)

        val actAR = getPage(1, false).second

        assertEquals("Did not return the correct AR", expectedAR, actAR)
    }

    @Test
    fun getPageListSingleUserUnitSuccessTest() = getPageUnitTestPrototype(touchStreamTestContent, true) {
        assertEquals("Did not return the correct DrawPoints", touchStreamTestContent, it)
    }

    @Test
    fun getPageSingleUserListOptOutTest() = getPageUnitTestPrototype(touchStreamTestContent, false) {
        assertEquals("Did not return an empty list", listOf<DrawPoint>(), it)
    }

    @Test
    fun getPageSingleUserEmptyTest() = getPageUnitTestPrototype(listOf(), false) {
        assertEquals("Did not return an empty list", listOf<DrawPoint>(), it)
    }

    private fun getPageUnitTestPrototype(
        expPoints: List<DrawPoint>,
        actuallyFetch: Boolean,
        test: (List<DrawPoint>) -> Unit
    ) {
        setValue(pages.child("1/$arKey"), 0)
            .mergeWith(setValue(touchHistory.child(pageUID(1)), expPoints))
            .blockingAwait(2, TimeUnit.SECONDS)

        getPage(1, actuallyFetch).first.also { test(it) }
    }

    private inline fun <reified T> Maybe<DataSnapshot>.singleEventTest() =
        test().awaitCount(1).values()[0].value.run {
            (this as? List<Map<String, Any>?>)
                ?.withIndex()
                ?.filter { it.value != null }
                ?.associate { it.index.toString() to it.value!! }
                ?: this
        } as Map<String, T>
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

    @Rule
    @JvmField
    val exception: ExpectedException = ExpectedException.none()

    protected fun <T> Observable<T>.log(name: String): Observable<T> =
        doOnComplete { dLogger("$name completed") }
            .doOnDispose { dLogger("$name got disposed") }
            .doOnNext { dLogger("$name emitted", "$it of type ${it.klass()}") }
            .doOnError { dLogger("$name errored", it) }
            .doOnSubscribe { dLogger("$name was subscribed") }

    protected fun dLogger(output: String, obj: Any? = Unit) = if (DEBUG) this.iLogger(output, obj) else Unit

    protected val singleValueTestRunner: (Query, (DataSnapshot) -> Unit) -> Unit = { ref, test ->
        RxFirebaseDatabase.observeSingleValueEvent(ref)
            .test()
            .apply {
                assertTrue("SUT timed out!", await(10, TimeUnit.SECONDS))
            }
            .assertNoErrors()
            .assertValueCount(1)
            .values()[0]
            .also(test)
    }

    protected fun messageFormat(expect: Any?, found: Any?): String = "Expected $expect, found $found"

    @Throws(java.lang.IllegalStateException::class, AssertionError::class)
    protected fun DataSnapshot.assertEqualWithPush(comparison: Any?) {
        firebaseTypeTest(comparison)

        if (!hasChildren()) {
            assertTrue(
                messageFormat(NESTED_DATA, value.klass()),
                !(comparison is List<*> || comparison is Map<*, *>)
            )
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
        Firebase.setTesting(true)
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