package co.electriccoin.zcash.ui.common.datasource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class OptionalTransparentReceiverTest {
    @Test
    fun anAvailableAddressDoesNotNeedAnotherKeyDecode() = runTest {
        assertEquals("synthetic-address", resolveTransparentReceiver(true, { "synthetic-address" }, { error("unused") }))
    }

    @Test
    fun anAbsentViewOnlyReceiverIsOmittedWhenConfirmedByTheSdk() = runTest {
        assertNull(resolveTransparentReceiver(true, { error("no receiver") }, { false }))
    }

    @Test
    fun anOperationalFailureForAnExistingReceiverIsNotHidden() = runTest {
        val failure = IllegalStateException("synthetic")
        val result = assertFailsWith<IllegalStateException> {
            resolveTransparentReceiver(true, { throw failure }, { true })
        }
        assertSame(failure, result)
    }

    @Test
    fun spendableAccountsNeverOmitAnUnexpectedMissingAddress() = runTest {
        assertFailsWith<IllegalStateException> {
            resolveTransparentReceiver(false, { error("synthetic") }, { error("unused") })
        }
    }

    @Test
    fun cancellationNeverBecomesAnAbsentReceiver() = runTest {
        assertFailsWith<CancellationException> {
            resolveTransparentReceiver(true, { throw CancellationException() }, { error("unused") })
        }
    }
}
