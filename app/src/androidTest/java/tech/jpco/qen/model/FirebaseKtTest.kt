package tech.jpco.qen.model

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import io.reactivex.Single
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import tech.jpco.qen.model.Constants.firebase
import tech.jpco.qen.model.Firebase.getMaxPageAndSetIfAbsent
import tech.jpco.qen.viewModel.iLogger
import java.util.concurrent.CountDownLatch
import kotlin.reflect.full.isSubclassOf

internal class FirebaseKtTest {
    companion object;

    @Test
    fun newPageTransactionTest() {
        val pages = firebase.child("pages")
        Constants.setup()

        val tO = pages.getMaxPageAndSetIfAbsent(Single.just(2f)).test()

        val snapshot = tO.await().values().run {
            assertTrue(size == 1)
            this[0]
        }

        iLogger("snapshot outputs", snapshot.value)
        iLogger("snapshot output class", snapshot.klass())
    }


}

internal class FirebaseTestTooling {
    private fun messageFormat(expect: Any?, found: Any?): String = "Expected $expect, found $found"
    private val nestedData = "nested data"

    fun DataSnapshot.assertEqualWithPush(comparison: Any?) {

        if (!hasChildren()) {
            assertTrue(messageFormat(nestedData, value.klass()), !(comparison is List<*> || comparison is Map<*, *>))
            if (comparison is Double && comparison % 1 == 0.0) {
                assertTrue(messageFormat(comparison, value), value == comparison.toLong())
            } else assertTrue(messageFormat(comparison, value), value == comparison)
        } else {

            assertTrue(messageFormat("something strange", "collection"),
                comparison?.let {
                    it::class.isSubclassOf(Map::class) || it::class.isSubclassOf(List::class)
                } ?: false
            )
//            fail("Did not test the received nested data: ${value.klass()}")
        }
    }

    @Rule
    @JvmField
    val exception: ExpectedException = ExpectedException.none()

    @Before
    fun setup() = Constants.setup()

    private fun aEWP_setup(original: Any?, comparison: Any? = Unit) {
        if (
            listOf(
                String::class,
                Long::class,
                Double::class,
                Boolean::class,
                Map::class,
                List::class,
                null
            ).count { klass ->
                (if (comparison != Unit) comparison else original).let { output ->
                    klass?.let {
                        output.klass()?.isSubclassOf(it)
                    } ?: (output == null)
                }

            } == 0
        ) throw IllegalStateException()

        val latch = CountDownLatch(1)


        if (original == Constants.ANY) firebase.push().setValue(true)
        else firebase.setValue(original)

        var err: AssertionError? = null
        firebase.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onCancelled(p0: DatabaseError) {
                TODO("not implemented")
            }

            override fun onDataChange(p0: DataSnapshot) {

                try {
                    p0.assertEqualWithPush(if (comparison == Unit) original else comparison)
                } catch (assertErr: AssertionError) {
                    err = assertErr
                } finally {
                    latch.countDown()
                }

            }
        })

        latch.await()

        err?.let { throw it }
    }


    @Test
    fun aEWP_allSingles() = listOf("string", 1L, 2.0, false, null).forEach { aEWP_setup(it) }

    @Test
    fun aEWP_singleExpectList() {
        exception.expect(AssertionError::class.java)
        aEWP_setup("a string", listOf<Any>())
    }

    @Test
    fun aEWP_invalidOriginalNoComparison() {
        exception.expect(IllegalStateException::class.java)
        aEWP_setup(object {})
    }

    @Test
    fun aEWP_invalidComparison() {
        exception.expect(IllegalStateException::class.java)
        aEWP_setup("test", object {})
    }

    @Test
    fun aEWP_mismatchedEmitCompare() {
        setupMismatch("a string", null)
    }

    @Test
    fun aEWP_emitCollectionCompareOther() {
        setupMismatch("a string", listOf<Any>())
    }

    private fun setupMismatch(rawInput: Any?, wrongOutput: Any?) {
        val (expectation, input) =
            if (wrongOutput.klass()?.isSubclassOf(Collection::class) == true) nestedData to rawInput.klass()
            else wrongOutput to rawInput
        exception.expectMessage(messageFormat(expectation, input))
        aEWP_setup(rawInput, wrongOutput)
    }

    @Test
    fun aEWP_push() {
        aEWP_setup(Constants.ANY, mapOf(Constants.ANY to true, Constants.ANY to true))
    }
}

private object Constants {
    val firebase = FirebaseDatabase.getInstance().getReference("Testing")
    val mockAR = 2f
    val initPagesOutput: List<Any> = listOf(mapOf(ANY to true, "AR" to mockAR))

    object ANY

    fun setup() {
        val setupLatch = CountDownLatch(1)
        firebase.removeValue().addOnCompleteListener { setupLatch.countDown() }
        setupLatch.await()
    }

}

fun Any?.klass() = this?.let { it::class }