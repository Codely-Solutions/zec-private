package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.exception.TransactionEncoderException
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The proposal failure mapping the app relies on: since MOB-1723 the SDK reports an
 * insufficient-funds proposal failure and a PCZT-unsupported (TEX) proposal as dedicated types, so
 * this layer maps purely by type - no error-message matching anywhere.
 */
class ProposalDataSourceTest {
    private val account =
        mockk<ZodlAccount> {
            every { sdkAccount } returns mockk(relaxed = true)
            every { isSpendable } returns true
        }

    @Test
    fun multipleZip321PaymentsAreRejectedBeforeSdkProposal() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            val uri = "zcash:$ZIP321_RECIPIENT?amount=0.001&address.1=$ZIP321_RECIPIENT&amount.1=0.002"

            val thrown =
                assertFailsWith<UnsupportedZip321PaymentCountException> {
                    dataSource(synchronizer).createZip321Proposal(account, uri)
                }

            assertEquals("ZIP321 requests must contain exactly one payment", thrown.message)
            coVerify(exactly = 0) { synchronizer.validateAddress(any()) }
            coVerify(exactly = 0) { synchronizer.proposeFulfillingPaymentUri(any(), any()) }
        }

    @Test
    fun singleZip321PaymentReachesSdkWithTheOriginalUri() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            val uri = "zcash:$ZIP321_RECIPIENT?amount=0.001"
            val sdkException = IllegalStateException("proposal sentinel")
            val sdkAccount = account.sdkAccount
            coEvery { synchronizer.validateAddress(ZIP321_RECIPIENT) } returns AddressType.Transparent
            coEvery { synchronizer.proposeFulfillingPaymentUri(sdkAccount, uri) } throws sdkException

            val thrown =
                assertFailsWith<TransactionProposalNotCreatedException> {
                    dataSource(synchronizer).createZip321Proposal(account, uri)
                }

            assertSame(sdkException, thrown.cause)
            coVerify(exactly = 1) { synchronizer.proposeFulfillingPaymentUri(sdkAccount, uri) }
        }

    @Test
    fun sdkInsufficientFundsBecomesTheAppInsufficientFundsException() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            val sdkException = TransactionEncoderException.InsufficientFundsException(RuntimeException("raw"))
            coEvery { synchronizer.proposeTransfer(any(), any(), any(), any()) } throws sdkException

            val thrown =
                assertFailsWith<InsufficientFundsException> {
                    dataSource(synchronizer).createProposal(account, send())
                }

            assertTrue(thrown.causeChain().any { it === sdkException })
        }

    @Test
    fun anUnmatchedProposalFailureBecomesTransactionProposalNotCreated() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            val sdkException = TransactionEncoderException.ProposalFromParametersException(RuntimeException("raw"))
            coEvery { synchronizer.proposeTransfer(any(), any(), any(), any()) } throws sdkException

            val thrown =
                assertFailsWith<TransactionProposalNotCreatedException> {
                    dataSource(synchronizer).createProposal(account, send())
                }

            assertSame(sdkException, thrown.cause)
        }

    @Test
    fun anArbitraryFailureBecomesTransactionProposalNotCreated() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            val raw = RuntimeException("anything")
            coEvery { synchronizer.proposeTransfer(any(), any(), any(), any()) } throws raw

            val thrown =
                assertFailsWith<TransactionProposalNotCreatedException> {
                    dataSource(synchronizer).createProposal(account, send())
                }

            assertSame(raw, thrown.cause)
        }

    @Test
    fun anAlreadyMappedFailureIsRethrownWithoutDoubleWrapping() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            val alreadyMapped = TransactionProposalNotCreatedException(IllegalArgumentException("Invalid ZIP321 URI"))
            coEvery { synchronizer.proposeTransfer(any(), any(), any(), any()) } throws alreadyMapped

            val thrown =
                assertFailsWith<TransactionProposalNotCreatedException> {
                    dataSource(synchronizer).createProposal(account, send())
                }

            assertSame(alreadyMapped, thrown)
        }

    /**
     * The multi-step rejection must reach the caller untouched: only
     * [co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository] knows whether the
     * proposal pays a TEX address and may therefore remap it to [TexUnsupportedOnKSException].
     */
    @Test
    fun aMultiStepProposalFailurePropagatesUntouched() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            val sdkException = PcztException.MultiStepProposalUnsupportedException()
            coEvery { synchronizer.createPcztFromProposal(any(), any()) } throws sdkException

            val thrown =
                assertFailsWith<PcztException.MultiStepProposalUnsupportedException> {
                    dataSource(synchronizer).createPcztFromProposal(account, mockk<Proposal>())
                }

            assertTrue(thrown.causeChain().any { it === sdkException })
        }

    @Test
    fun anyOtherPcztFailurePropagatesUntouched() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            val raw = RuntimeException("PCZT creation failed")
            coEvery { synchronizer.createPcztFromProposal(any(), any()) } throws raw

            val thrown =
                assertFailsWith<RuntimeException> {
                    dataSource(synchronizer).createPcztFromProposal(account, mockk<Proposal>())
                }

            assertTrue(thrown.causeChain().any { it === raw })
        }

    /**
     * The exception a coroutine hands back can be a stack-trace-recovered copy rather than the very
     * instance that was thrown, so identity is asserted against the whole cause chain.
     */
    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun dataSource(synchronizer: Synchronizer): ProposalDataSource =
        ProposalDataSourceImpl(
            synchronizerProvider = mockk { coEvery { getSynchronizer() } returns synchronizer },
            lightWalletEndpointProvider = mockk(),
            isServerSelectionAutomaticProvider = mockk(),
            persistableWalletProvider = mockk()
        )

    private suspend fun send(): ZecSend =
        ZecSend(
            destination = WalletAddress.Unified.new(RECIPIENT),
            amount = Zatoshi(AMOUNT),
            memo = Memo(""),
            proposal = null
        )

    private companion object {
        const val ZIP321_RECIPIENT = "t1Rv4exT7bqhZqi2j7xz8bUHDMxwosrjADU"
        const val RECIPIENT = "recipient"
        const val AMOUNT = 100_000L
    }
}
