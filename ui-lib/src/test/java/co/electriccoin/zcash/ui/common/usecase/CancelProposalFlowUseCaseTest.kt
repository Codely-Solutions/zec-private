package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.BaseNavigationCommand
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.PendingNavigation
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.donation.DonationArgs
import co.electriccoin.zcash.ui.screen.send.Send
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class CancelProposalFlowUseCaseTest {
    @Test
    fun donationCancellationClearsItsOwnProposalAndRetainsTheSendForm() =
        runTest {
            val proposal =
                RegularTransactionProposal(
                    destination = WalletAddress.Unified.new("recipient"),
                    amount = Zatoshi(1234L),
                    memo = Memo(""),
                    proposal = mockk<Proposal>(),
                    isDonation = true
                )
            val wallet = EditAccountFixtures.zodl()
            val zashiRepository = mockk<ZashiProposalRepository>(relaxed = true) {
                every { transactionProposal } returns MutableStateFlow(proposal)
            }
            val keystoneRepository = mockk<KeystoneProposalRepository>(relaxed = true)
            val ledgerPipeline = mockk<LedgerProposalPipeline>(relaxed = true)
            val clearSend = mockk<ObserveClearSendUseCase>(relaxed = true)
            val router = FakeNavigationRouter()
            val useCase =
                CancelProposalFlowUseCase(
                    zashiProposalRepository = zashiRepository,
                    keystoneProposalRepository = keystoneRepository,
                    ledgerProposalPipeline = ledgerPipeline,
                    navigationRouter = router,
                    observeClearSend = clearSend,
                    accountDataSource = selectedPaymentAccount(wallet),
                    swapRepository = mockk<SwapRepository>(relaxed = true),
                )

            useCase.rejectReviewed(proposal, wallet.sdkAccount.accountUuid, null)

            verify(exactly = 1) { zashiRepository.clear(proposal) }
            verify(exactly = 0) { keystoneRepository.clear(any()) }
            verify(exactly = 0) { ledgerPipeline.clear(any()) }
            verify(exactly = 0) { clearSend.requestClear() }
            assertEquals(1, router.backToCalls.size)
            assertEquals(DonationArgs::class, router.backToCalls.single())
            assertEquals(0, router.backCalls)
        }

    @Test
    fun migrationSweepRejectionPopsToTheActualOrigin() =
        runTest {
            val proposal = MigrationSweepTransactionProposal(mockk<Proposal>())
            val wallet = EditAccountFixtures.keystone(isSelected = true)
            val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true) {
                every { transactionProposal } returns MutableStateFlow(proposal)
            }
            val router = FakeNavigationRouter()
            val useCase =
                CancelProposalFlowUseCase(
                    zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true),
                    keystoneProposalRepository = keystoneProposalRepository,
                    ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true),
                    navigationRouter = router,
                    observeClearSend = mockk<ObserveClearSendUseCase>(relaxed = true),
                    accountDataSource = selectedPaymentAccount(wallet),
                    swapRepository = mockk<SwapRepository>(relaxed = true),
                )

            useCase.rejectKeystone(proposal, wallet.sdkAccount.accountUuid, null)

            verify(exactly = 1) { keystoneProposalRepository.clear(proposal) }
            assertEquals(0, router.backToCalls.size)
            assertEquals(1, router.backCalls)
        }

    @Test
    fun shieldProposalNavigatesBackInsteadOfSend() =
        runTest {
            val proposal = ShieldTransactionProposal(mockk<Proposal>())
            val wallet = EditAccountFixtures.keystone(isSelected = true)
            val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true) {
                every { transactionProposal } returns MutableStateFlow(proposal)
            }
            val router = FakeNavigationRouter()
            val useCase =
                CancelProposalFlowUseCase(
                    zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true),
                    keystoneProposalRepository = keystoneProposalRepository,
                    ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true),
                    navigationRouter = router,
                    observeClearSend = mockk<ObserveClearSendUseCase>(relaxed = true),
                    accountDataSource = selectedPaymentAccount(wallet),
                    swapRepository = mockk<SwapRepository>(relaxed = true),
                )

            useCase.rejectKeystone(proposal, wallet.sdkAccount.accountUuid, null)

            verify(exactly = 1) { keystoneProposalRepository.clear(proposal) }
            assertEquals(0, router.backToCalls.size)
            assertEquals(1, router.backCalls)
        }

    @Test
    fun regularLedgerProposalClearsTheLedgerRepositoryAndNavigatesBackToSend() =
        runTest {
            val proposal =
                RegularTransactionProposal(
                    destination = WalletAddress.Unified.new("recipient"),
                    amount = Zatoshi(1234L),
                    memo = Memo(""),
                    proposal = mockk<Proposal>()
                )
            val wallet = EditAccountFixtures.ledger(isSelected = true)
            val ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true) {
                every { transactionProposal } returns MutableStateFlow(proposal)
            }
            val router = FakeNavigationRouter()
            val useCase =
                CancelProposalFlowUseCase(
                    zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true),
                    keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true),
                    ledgerProposalPipeline = ledgerProposalPipeline,
                    navigationRouter = router,
                    observeClearSend = mockk<ObserveClearSendUseCase>(relaxed = true),
                    accountDataSource = selectedPaymentAccount(wallet),
                    swapRepository = mockk<SwapRepository>(relaxed = true),
                )

            useCase.rejectReviewed(proposal, wallet.sdkAccount.accountUuid, null)

            verify(exactly = 1) { ledgerProposalPipeline.clear(proposal) }
            assertEquals(1, router.backToCalls.size)
            assertEquals(Send::class, router.backToCalls.single())
            assertEquals(0, router.backCalls)
        }

    @Test
    fun keystoneRejectionLeavesAnUnrelatedDraftAndCannotClearAReplacement() {
        val rendered = ShieldTransactionProposal(mockk())
        val replacement = ShieldTransactionProposal(mockk())
        val wallet = EditAccountFixtures.keystone(isSelected = true)
        val proposals = MutableStateFlow<TransactionProposal?>(rendered)
        val keystone = mockk<KeystoneProposalRepository>(relaxed = true) {
            every { transactionProposal } returns proposals
        }
        val zodl = mockk<ZashiProposalRepository>(relaxed = true)
        val ledger = mockk<LedgerProposalPipeline>(relaxed = true)
        val queue = PendingNavigation()
        val router = mockk<NavigationRouter> { every { pendingNavigation } returns queue }
        val accounts = mockk<AccountDataSource> { every { allAccounts } returns MutableStateFlow(listOf(wallet)) }
        val useCase = CancelProposalFlowUseCase(zodl, keystone, ledger, router, mockk(), accounts, mockk())

        useCase.rejectKeystone(rendered, wallet.sdkAccount.accountUuid, "signing")
        proposals.value = replacement
        assertFalse(queue.commands.value.getValue("signing").isStillAllowed())
        verify(exactly = 0) { keystone.clear(any()) }

        useCase.rejectKeystone(replacement, wallet.sdkAccount.accountUuid, "signing")
        assertEquals(NavigationCommand.Back, queue.commands.value.getValue("signing").buildCommand())
        verify(exactly = 1) { keystone.clear(replacement) }
        verify(exactly = 0) { zodl.clear(any()) }
        verify(exactly = 0) { ledger.clear(any()) }
    }

    private class FakeNavigationRouter : NavigationRouter {
        val backToCalls = mutableListOf<KClass<*>>()
        var backCalls = 0

        override fun forward(vararg routes: Any) = Unit

        override fun replace(vararg routes: Any) = Unit

        override fun replaceAll(vararg routes: Any) = Unit

        override fun replaceFrom(route: KClass<*>, vararg routes: Any) = Unit

        override fun back() {
            backCalls++
        }

        override fun backTo(route: KClass<*>) {
            backToCalls += route
        }

        override fun custom(block: (NavBackStackEntry?) -> NavigationCommand?) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<BaseNavigationCommand> = emptyFlow()
    }
}
