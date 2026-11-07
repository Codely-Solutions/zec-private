package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Zip321PaymentCountTest {
    @Test
    fun multiplePaymentsAreInvalidForEveryScanFlow() =
        runBlocking {
            assertIs<Zip321ParseUriValidation.Invalid>(
                validator()("zcash:$RECIPIENT?amount=0.001&address.1=$RECIPIENT&amount.1=0.002")
            )
        }

    @Test
    fun onePaymentRemainsValid() =
        runBlocking {
            assertIs<Zip321ParseUriValidation.Valid>(validator()("zcash:$RECIPIENT?amount=0.001"))
        }

    private fun validator(): Zip321ParseUriValidationUseCase {
        val synchronizer = mockk<Synchronizer>()
        coEvery { synchronizer.validateAddress(any()) } returns AddressType.Transparent
        return Zip321ParseUriValidationUseCase(
            mockk { coEvery { getSynchronizer() } returns synchronizer }
        )
    }

    private companion object {
        const val RECIPIENT = "t1Rv4exT7bqhZqi2j7xz8bUHDMxwosrjADU"
    }
}
