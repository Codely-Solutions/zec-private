package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.model.UneditableZip321MemoException
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zip321ParseUriValidationUseCase.Zip321ParseUriValidation
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.fixture.Zip321MemoFixture
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.error.ErrorVM
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import co.electriccoin.zcash.ui.screen.scan.ScanArgs
import co.electriccoin.zcash.ui.screen.scan.ScanFlow
import co.electriccoin.zcash.ui.screen.send.Send
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Zip321MemoScanTest {
    private val account = EditAccountFixtures.zodl()
    private val sdkProposal = mockk<Proposal>()
    private val fixture = Zip321MemoFixture
    private val synchronizer = mockk<Synchronizer> {
        coEvery { validateAddress(fixture.RECIPIENT) } returns AddressType.Shielded
        coEvery { proposeFulfillingPaymentUri(account.sdkAccount, any()) } returns sdkProposal
    }
    private val provider = mockk<SynchronizerProvider> { coEvery { getSynchronizer() } returns synchronizer }
    private val accounts = mockk<AccountDataSource> { coEvery { getSelectedAccount() } returns account }
    private val repository = ZashiProposalRepositoryImpl(
        accountDataSource = accounts,
        proposalDataSource = ProposalDataSourceImpl(provider, mockk(), mockk()),
        zashiSpendingKeyDataSource = mockk(),
        processSwapTransaction = mockk(),
    )
    private val router = mockk<NavigationRouter>(relaxed = true)
    private val prefill = PrefillSendUseCase()
    private val error = NavigateToErrorUseCase(router)
    private val scan = OnZip321ScannedUseCase(mockk(relaxed = true), mockk(relaxed = true), repository, accounts, router, prefill, error)

    @Test
    fun homeScanOpensDirectReviewForANonTextMemo() = runBlocking {
        val request = parse(fixture.binary)
        scan(request, ScanArgs(ScanFlow.HOMEPAGE))

        assertSame(sdkProposal, assertNotNull(repository.transactionProposal.value).proposal)
        coVerify(exactly = 1) { synchronizer.proposeFulfillingPaymentUri(account.sdkAccount, request.zip321Uri) }
        verify(exactly = 1) { router.replace(ReviewTransactionArgs) }
        verify(exactly = 0) { router.replace(Send(), ReviewTransactionArgs) }
        assertNoDraft()
    }

    @Test
    fun failedBinaryAndInvalidUtf8ProposalsNeverBecomeEditableDrafts() = runBlocking {
        coEvery { synchronizer.proposeFulfillingPaymentUri(account.sdkAccount, any()) } throws IllegalStateException()
        for ((flow, bytes) in listOf(ScanFlow.HOMEPAGE to fixture.binary, ScanFlow.SEND to fixture.invalidUtf8)) {
            scan(parse(bytes), ScanArgs(flow))
            assertUneditableError()
            assertNull(repository.transactionProposal.value)
        }

        verify(exactly = 2) { router.navigate(ErrorDialog) }
        verify(exactly = 0) { router.replace(Send()) }
        verify(exactly = 0) { router.back() }
        assertNoDraft()
    }

    @Test
    fun scanWithReviewDisabledPreservesNonTextMemoByRefusingAddressOnlyFallback() = runBlocking {
        scan(parse(fixture.binary), ScanArgs(ScanFlow.SEND, isScanZip321Enabled = false))
        coVerify(exactly = 0) { synchronizer.proposeFulfillingPaymentUri(any(), any()) }
        assertUneditableError()
        verify(exactly = 0) { router.back() }
        assertNoDraft()
    }

    @Test
    fun directPrefillRefusesNonTextAndKeepsStrictPaddedText() = runBlocking {
        for (bytes in listOf(fixture.binary, fixture.invalidUtf8)) {
            val request = parse(bytes)
            assertFailsWith<UneditableZip321MemoException> { prefill.requestFromZip321(request.payment) }
        }
        assertNoDraft()

        val text = parse(fixture.TEXT.encodeToByteArray().copyOf(512))
        val job = prefill.requestFromZip321(text.payment)
        assertEquals(expectedTextDraft(), withTimeout(5_000) { prefill().first() })
        job.join()
    }

    @Test
    fun textProposalFailureStillPrefillsAnEditableDraft() = runBlocking {
        coEvery { synchronizer.proposeFulfillingPaymentUri(account.sdkAccount, any()) } throws IllegalStateException()
        scan(parse(fixture.TEXT.encodeToByteArray()), ScanArgs(ScanFlow.HOMEPAGE))
        assertEquals(expectedTextDraft(), withTimeout(5_000) { prefill().first() })
        verify(exactly = 1) { router.replace(Send()) }
        verify(exactly = 0) { router.navigate(ErrorDialog) }
    }

    private suspend fun parse(bytes: ByteArray) = assertIs<Zip321ParseUriValidation.Valid>(
        Zip321ParseUriValidationUseCase(provider)(fixture.uri(bytes))
    )

    private suspend fun assertNoDraft() {
        assertNull(withTimeoutOrNull(250) { prefill().first() })
    }

    private fun assertUneditableError() {
        val args = assertIs<ErrorArgs.General>(error.requireCurrentArgs())
        assertIs<UneditableZip321MemoException>(args.exception)
        val state = ErrorVM(args, error, router, mockk(), mockk()).state.value
        assertEquals(stringRes(R.string.zip321_non_text_memo_edit_error), state.message)
        assertNull(state.negative)
    }

    private fun expectedTextDraft() = PrefillSendData.All(Zatoshi(100_000), fixture.RECIPIENT, null, listOf(fixture.TEXT))
}
