package tech.jpco.qen.model

import com.google.firebase.database.*
import io.reactivex.Single
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
import tech.jpco.qen.model.Firebase.getMaxPageAndSetIfAbsent
import tech.jpco.qen.viewModel.iLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.isSubclassOf

internal class FirebaseKtTest : FirebaseTestTooling() {
    @Test
    fun newPageTransactionTest() {
        val value = transactionTestPrototype(mockAR, null)

        JSONAssert.assertEquals(value, "[{AR=$mockAR}]", JSONCompareMode.STRICT)
    }

    @Test
    fun exPageTransactionTest() {
        val value = transactionTestPrototype(0f) {
            it.getMaxPageAndSetIfAbsent(sMockAR).test().await()
        }

        val depushed = value.replace(Regex("[$PUSH_CHARS]{20}"), PUSHED)
        JSONAssert.assertEquals(depushed, "[{AR=$mockAR, $PUSHED=true}]", JSONCompareMode.STRICT)
    }

    private fun transactionTestPrototype(finalAR: Float, preaction: ((DatabaseReference) -> Unit)?): String {
        val pages = firebase.child("pages")

        preaction?.invoke(pages)

        val tO = pages.getMaxPageAndSetIfAbsent(Single.just(finalAR)).test()

        return tO.await().assertValueCount(1).values()[0].value.toString()
    }

    @Test
    fun getMaxPageFromBlankTest() {
        val firebaseRepo = Firebase
        firebaseRepo.setTesting(true)

        val tO = firebaseRepo.getMaxPage(sMockAR).doOnNext { iLogger("repo emitted", it) }.test()
        tO.await(2, TimeUnit.SECONDS)
        tO.assertValueCount(1).assertValueAt(0, 1)
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
        firebase.removeValue().addOnCompleteListener {
            iLogger("Setup executed")
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