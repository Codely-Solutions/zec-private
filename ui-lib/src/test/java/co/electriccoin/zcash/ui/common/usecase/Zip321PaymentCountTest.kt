package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.fixture.Zip321MemoFixture
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Zip321PaymentCountTest {
    @Test
    fun multiplePaymentsAreInvalidForEveryScanFlow() =
        runBlocking {
            assertIs<Zip321ParseUriValidation.Invalid>(
                validator()("zcash:$RECIPIENT?amount=0.001&address.1=$RECIPIENT&amount.1=0.002")
            )
            Unit
        }

    @Test
    fun onePaymentRemainsValid() =
        runBlocking {
            assertIs<Zip321ParseUriValidation.Valid>(validator()("zcash:$RECIPIENT?amount=0.001"))
            Unit
        }

    @Test
    fun parsedOpaqueMemoRetainsOriginalUriAndBytes() = runBlocking {
        val fixture = Zip321MemoFixture
        val validate = validator(AddressType.Shielded)
        for (bytes in listOf(fixture.binary, fixture.invalidUtf8)) {
            val uri = fixture.uri(bytes)
            val valid = assertIs<Zip321ParseUriValidation.Valid>(validate(uri))
            assertEquals(uri, valid.zip321Uri)
            assertContentEquals(bytes, assertNotNull(valid.payment.payments.single().memo).data)
        }
    }

    @Test
    fun builderAndParserLogMetadataWithoutPaymentContent() = runBlocking {
        val fixture = Zip321MemoFixture
        ShadowLog.clear()
        val uri = Zip321BuildUriUseCase()(fixture.RECIPIENT, "0.001".toBigDecimal(), fixture.TEXT)
        assertIs<Zip321ParseUriValidation.Valid>(validator(AddressType.Shielded)(uri))
        val messages = ShadowLog.getLogs().map { it.msg }
        assertTrue(messages.any { "Built ZIP321 request" in it })
        assertTrue(messages.any { "ZIP321 parsing completed" in it })
        for (sensitive in listOf(uri, fixture.RECIPIENT, fixture.TEXT, fixture.encoded(fixture.TEXT.encodeToByteArray()))) {
            assertFalse(messages.any { sensitive in it })
        }
    }

    private fun validator(addressType: AddressType = AddressType.Transparent): Zip321ParseUriValidationUseCase {
        val synchronizer = mockk<Synchronizer>()
        coEvery { synchronizer.validateAddress(any()) } returns addressType
        return Zip321ParseUriValidationUseCase(
            mockk { coEvery { getSynchronizer() } returns synchronizer }
        )
    }

    private companion object {
        const val RECIPIENT = "t1Rv4exT7bqhZqi2j7xz8bUHDMxwosrjADU"
    }
}
