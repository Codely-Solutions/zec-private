package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.PendingNavigation
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ConfirmedAccountSelection
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldingApproval
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorRoute
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Shielding outcome routing (MOB-1145): shielding has no pending screen, so every non-success
 * result routes to the error screen - including Partial (which must not be silently ignored) and a
 * resubmittable GrpcFailure - while a full success shows no error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShieldFundsUseCaseTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun partialResultRoutesToError() =
        runTest(dispatcher) {
            val result = SubmitResult.Partial(txIds = listOf("a", "b"), statuses = listOf("success", "notAttempted"))
            val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)

            useCase(submitResult = result, navigateToError = navigateToError)(false)
            advanceUntilIdle()

            verify(exactly = 1) { navigateToError(ErrorArgs.ShieldingError(result), any()) }
        }

    @Test
    fun grpcFailureRoutesToError() =
        runTest(dispatcher) {
            val result = SubmitResult.GrpcFailure(txIds = listOf("a"))
            val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)

            useCase(submitResult = result, navigateToError = navigateToError)(false)
            advanceUntilIdle()

            verify(exactly = 1) { navigateToError(ErrorArgs.ShieldingError(result), any()) }
        }

    @Test
    fun successDoesNotRouteToError() =
        runTest(dispatcher) {
            val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)

            useCase(submitResult = SubmitResult.Success(txIds = listOf("a")), navigateToError = navigateToError)(false)
            advanceUntilIdle()

            verify(exactly = 0) { navigateToError(any(), any()) }
        }

    @Test
    fun ledgerAccountCreatesProposalAndPcztThenOpensTheSignSheet() =
        runTest(dispatcher) {
            val ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true)
            val ledgerNavigator = mockk<LedgerNavigator>(relaxed = true)

            ledgerUseCase(
                ledgerProposalPipeline = ledgerProposalPipeline,
                ledgerNavigator = ledgerNavigator
            )(false)
            advanceUntilIdle()

            coVerify(exactly = 1) { ledgerProposalPipeline.createShieldProposal(any()) }
            coVerify(exactly = 1) { ledgerProposalPipeline.createPCZTFromProposal() }
            verify(exactly = 1) { ledgerNavigator.forwardToSign(null, any()) }
            coVerify(exactly = 0) { ledgerProposalPipeline.clear() }
        }

    @Test
    fun aLedgerFailureBeforeCreationPreservesTheExistingProposalAndReportsTheError() =
        runTest(dispatcher) {
            val ledgerProposalPipeline =
                mockk<LedgerProposalPipeline>(relaxed = true) {
                    coEvery { createShieldProposal(any()) } throws RuntimeException("boom")
                }
            val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)

            ledgerUseCase(
                ledgerProposalPipeline = ledgerProposalPipeline,
                navigateToError = navigateToError
            )(false)
            advanceUntilIdle()

            coVerify(exactly = 0) { ledgerProposalPipeline.clear() }
            verify(exactly = 1) { navigateToError(match<ErrorArgs> { it is ErrorArgs.ShieldingGeneralError }, any()) }
        }

    @Test
    fun aCancelledLedgerShieldClearsTheProposalWithoutShowingAnError() =
        runTest(dispatcher) {
            val shield = mockk<ShieldTransactionProposal>()
            val ledgerProposalPipeline =
                mockk<LedgerProposalPipeline>(relaxed = true) {
                    coEvery { createShieldProposal(any()) } returns shield
                    every { transactionProposal } returns MutableStateFlow(shield)
                    coEvery { createPCZTFromProposal() } throws CancellationException("cancelled")
                }
            val ledgerNavigator = mockk<LedgerNavigator>(relaxed = true)
            val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)

            ledgerUseCase(
                ledgerProposalPipeline = ledgerProposalPipeline,
                ledgerNavigator = ledgerNavigator,
                navigateToError = navigateToError
            )(false)
            advanceUntilIdle()

            coVerify(exactly = 1) { ledgerProposalPipeline.clear() }
            verify(exactly = 0) { navigateToError(any(), any()) }
            verify(exactly = 0) { ledgerNavigator.forwardToSign(null, any()) }
        }

    @Test
    fun heldHardwareConfirmationRequiresCurrentBalanceAccountAndWallet() = runTest(dispatcher) {
        val hardware = shieldableLedger()
        val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(hardware))
        val softwareProposals = mockk<ZashiProposalRepository>()
        val ledgerProposals = mockk<LedgerProposalPipeline>()
        val marker = mockk<MessageAvailabilityDataSource>(relaxed = true)
        val wallet = WalletWriteAccess()
        val operations = ShieldFundsUseCase(
            keystoneProposalRepository = mockk(),
            ledgerProposalPipeline = ledgerProposals,
            ledgerNavigator = mockk(),
            zashiProposalRepository = softwareProposals,
            navigationRouter = mockk(),
            accountDataSource = mockk {
                every { allAccounts } returns accounts
                coEvery { confirmSelection(any()) } coAnswers {
                    val uuid = firstArg<WalletAccount>().sdkAccount.accountUuid
                    val matches = { accounts.value?.firstOrNull { it.isSelected }?.sdkAccount?.accountUuid == uuid }
                    ConfirmedAccountSelection(matches, { true })
                }
            },
            navigateToError = mockk(),
            messageAvailabilityDataSource = marker,
            walletWriteAccess = wallet,
            appAccessSession = AppAccessSession().apply { unlock() },
        )
        val confirm = operations.confirm(hardware, closeCurrentScreen = true)
        accounts.value = listOf(hardware.copy(transparentBalance = null))
        confirm()
        advanceUntilIdle()
        accounts.value = listOf(hardware.copy(transparentBalance = Zatoshi(100_000)))
        confirm()
        advanceUntilIdle()

        accounts.value = listOf(EditAccountFixtures.zodl())
        confirm()
        advanceUntilIdle()

        accounts.value = listOf(hardware)
        wallet.beginReset()
        wallet.finishReset()
        wallet.replaceWallet { }
        confirm()
        advanceUntilIdle()

        coVerify(exactly = 0) { marker.onShieldingInitiated() }
        coVerify(exactly = 0) { softwareProposals.createShieldProposal(any()) }
        coVerify(exactly = 0) { ledgerProposals.createShieldProposal(any()) }
    }

    @Test
    fun dismissedShieldDoesNotOpenTheHardwareSigner() = runTest(dispatcher) {
        val hardware = shieldableLedger()
        val entries = MutableStateFlow<ActiveNavigationEntry?>(
            ActiveNavigationEntry("shield-dialog", null, contentEntryId = "home")
        )
        val preparation = CompletableDeferred<ShieldTransactionProposal>()
        val ledger = mockk<LedgerProposalPipeline> {
            coEvery { createShieldProposal(any()) } coAnswers { preparation.await() }
        }
        val signer = mockk<LedgerNavigator>()
        val operation = ShieldFundsUseCase(
            keystoneProposalRepository = mockk(),
            ledgerProposalPipeline = ledger,
            ledgerNavigator = signer,
            zashiProposalRepository = mockk(),
            navigationRouter = mockk { every { activeEntry } returns entries },
            accountDataSource = selectedAccount(hardware),
            navigateToError = mockk(),
            messageAvailabilityDataSource = mockk(relaxed = true),
            walletWriteAccess = WalletWriteAccess(),
            appAccessSession = AppAccessSession().apply { unlock() },
        )
        operation.confirm(hardware, closeCurrentScreen = true, sourceEntryId = "shield-dialog")()
        runCurrent()
        coVerify(exactly = 1) { ledger.createShieldProposal(any()) }
        entries.value = ActiveNavigationEntry("external-scanner", null)
        runCurrent()
        preparation.complete(mockk())
        advanceUntilIdle()

        coVerify(exactly = 0) { ledger.createPCZTFromProposal() }
        verify(exactly = 0) { signer.forwardToSign(any(), any()) }
    }

    @Test
    fun softwareShieldApprovalCannotEnterSubmissionAfterRelockAndUnlock() = runTest(dispatcher) {
        val software = shieldableSoftware()
        val access = AppAccessSession().apply { unlock() }
        val reviewed = CompletableDeferred<ShieldingApproval>()
        val preparingKey = CompletableDeferred<Unit>()
        var submissions = 0
        val shield = mockk<ShieldTransactionProposal>()
        val repository = mockk<ZashiProposalRepository> {
            coEvery { createShieldProposal(any()) } coAnswers {
                reviewed.complete(firstArg<ShieldingApproval>())
                shield
            }
            coEvery { submit(shield) } coAnswers {
                preparingKey.await()
                reviewed.await().requireCurrent()
                submissions++
                SubmitResult.Success(listOf("synthetic-shield"))
            }
        }
        val errors = mockk<NavigateToErrorUseCase>(relaxed = true)
        val useCase = ShieldFundsUseCase(
            keystoneProposalRepository = mockk(),
            ledgerProposalPipeline = mockk(),
            ledgerNavigator = mockk(),
            zashiProposalRepository = repository,
            navigationRouter = mockk(),
            accountDataSource = selectedAccount(software),
            navigateToError = errors,
            messageAvailabilityDataSource = mockk(relaxed = true),
            walletWriteAccess = WalletWriteAccess(),
            appAccessSession = access,
        )
        useCase.confirm(software, closeCurrentScreen = false)()
        runCurrent()
        coVerify(exactly = 1) { repository.submit(shield) }

        access.lock()
        access.unlock()
        preparingKey.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, submissions)
        verify(exactly = 0) { errors(any(), any()) }
    }

    @Test
    fun oldSoftwareFailureDoesNotOpenAnErrorAfterAnAccountSwitch() = runTest(dispatcher) {
        val software = shieldableSoftware()
        val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(software))
        val result = CompletableDeferred<SubmitResult>()
        val shield = mockk<ShieldTransactionProposal>()
        val repository = mockk<ZashiProposalRepository> {
            coEvery { createShieldProposal(any()) } returns shield
            coEvery { submit(shield) } coAnswers { result.await() }
        }
        val errors = mockk<NavigateToErrorUseCase>(relaxed = true)
        val operation = ShieldFundsUseCase(
            keystoneProposalRepository = mockk(),
            ledgerProposalPipeline = mockk(),
            ledgerNavigator = mockk(),
            zashiProposalRepository = repository,
            navigationRouter = mockk(),
            accountDataSource = mockk {
                every { allAccounts } returns accounts
                coEvery { confirmSelection(any()) } coAnswers {
                    val uuid = firstArg<WalletAccount>().sdkAccount.accountUuid
                    val matches = { accounts.value?.firstOrNull { it.isSelected }?.sdkAccount?.accountUuid == uuid }
                    ConfirmedAccountSelection(matches, { true })
                }
            },
            navigateToError = errors,
            messageAvailabilityDataSource = mockk(relaxed = true),
            walletWriteAccess = WalletWriteAccess(),
            appAccessSession = AppAccessSession().apply { unlock() },
        )
        operation.confirm(software, closeCurrentScreen = false)()
        runCurrent()
        coVerify(exactly = 1) { repository.submit(shield) }
        accounts.value = listOf(EditAccountFixtures.ledger(isSelected = true))
        result.complete(SubmitResult.GrpcFailure(listOf("synthetic-old-transaction")))
        advanceUntilIdle()

        verify(exactly = 0) { errors(any(), any()) }
    }

    @Test
    fun detachedSoftwareShieldPresentsKnownTransactionAfterSameAccountSynchronizerReplacement() =
        runTest(dispatcher) {
            val software = shieldableSoftware()
            val entries = MutableStateFlow<ActiveNavigationEntry?>(
                ActiveNavigationEntry("shield-dialog", null, contentEntryId = "home")
            )
            val pending = PendingNavigation()
            val navigation = mockk<NavigationRouter> {
                every { activeEntry } returns entries
                every { pendingNavigation } returns pending
            }
            val error = NavigateToErrorUseCase(navigation)
            val approved = CompletableDeferred<ShieldingApproval>()
            val nativeCreationEnded = CompletableDeferred<Unit>()
            val shield = mockk<ShieldTransactionProposal>()
            val outcome = SubmitResult.GrpcFailure(
                txIds = listOf("synthetic-created-transaction"),
                reason = SubmitResult.GrpcFailure.Reason.SDK_STATE_UNCERTAIN,
            )
            val repository = mockk<ZashiProposalRepository> {
                coEvery { createShieldProposal(any()) } coAnswers {
                    approved.complete(firstArg())
                    shield
                }
                coEvery { submit(shield) } coAnswers {
                    approved.await().requireCurrent()
                    nativeCreationEnded.await()
                    assertFalse(approved.await().isCurrent())
                    outcome
                }
            }
            var originalSynchronizer = true
            var originalSelection = true
            val useCase = ShieldFundsUseCase(
                keystoneProposalRepository = mockk(),
                ledgerProposalPipeline = mockk(),
                ledgerNavigator = mockk(),
                zashiProposalRepository = repository,
                navigationRouter = navigation,
                accountDataSource = mockk {
                    every { allAccounts } returns MutableStateFlow(listOf(software))
                    coEvery { confirmSelection(software) } returns ConfirmedAccountSelection(
                        { originalSelection }, { originalSynchronizer }
                    )
                },
                navigateToError = error,
                messageAvailabilityDataSource = mockk(relaxed = true),
                walletWriteAccess = WalletWriteAccess(),
                appAccessSession = AppAccessSession().apply { unlock() },
            )

            useCase.confirm(software, closeCurrentScreen = true, sourceEntryId = "shield-dialog")()
            runCurrent()
            assertEquals(NavigationCommand.Back, pending.commands.value["shield-dialog"]?.buildCommand?.invoke())
            entries.value = ActiveNavigationEntry("home", null)
            originalSynchronizer = false
            nativeCreationEnded.complete(Unit)
            runCurrent()

            val item = assertNotNull(pending.commands.value["home"])
            val command = item.buildCommand() as NavigationCommand.Forward
            assertEquals(ErrorArgs.ShieldingError(outcome), error.argsFor(command.routes.single() as ErrorRoute))
            originalSelection = false
            assertFalse(item.isStillAllowed())
        }

    private fun useCase(
        submitResult: SubmitResult,
        navigateToError: NavigateToErrorUseCase
    ): (Boolean) -> Unit {
        val account = shieldableSoftware()
        val useCase = ShieldFundsUseCase(
            keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true),
            ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true),
            ledgerNavigator = mockk<LedgerNavigator>(relaxed = true),
            zashiProposalRepository =
                mockk<ZashiProposalRepository>(relaxed = true) {
                    val shield = mockk<ShieldTransactionProposal>()
                    coEvery { createShieldProposal(any()) } returns shield
                    coEvery { submit(shield) } returns submitResult
                },
            navigationRouter = mockk<NavigationRouter>(relaxed = true),
            accountDataSource = selectedAccount(account),
            navigateToError = navigateToError,
            messageAvailabilityDataSource = mockk<MessageAvailabilityDataSource>(relaxed = true),
            walletWriteAccess = WalletWriteAccess(),
            appAccessSession = AppAccessSession().apply { unlock() },
        )
        return { close -> useCase.confirm(account, close)() }
    }

    private fun ledgerUseCase(
        ledgerProposalPipeline: LedgerProposalPipeline,
        ledgerNavigator: LedgerNavigator = mockk(relaxed = true),
        navigateToError: NavigateToErrorUseCase = mockk(relaxed = true),
    ): (Boolean) -> Unit {
        val account = shieldableLedger()
        val useCase = ShieldFundsUseCase(
            keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true),
            ledgerProposalPipeline = ledgerProposalPipeline,
            ledgerNavigator = ledgerNavigator,
            zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true),
            navigationRouter = mockk<NavigationRouter>(relaxed = true),
            accountDataSource = selectedAccount(account),
            navigateToError = navigateToError,
            messageAvailabilityDataSource = mockk<MessageAvailabilityDataSource>(relaxed = true),
            walletWriteAccess = WalletWriteAccess(),
            appAccessSession = AppAccessSession().apply { unlock() },
        )
        return { close -> useCase.confirm(account, close)() }
    }

    private fun shieldableSoftware() = EditAccountFixtures.zodl().copy(transparentBalance = Zatoshi(200_000))

    private fun shieldableLedger() =
        EditAccountFixtures.ledger(isSelected = true).copy(transparentBalance = Zatoshi(200_000))

    private fun selectedAccount(account: WalletAccount) = mockk<AccountDataSource> {
        every { allAccounts } returns MutableStateFlow(listOf(account))
        coEvery { confirmSelection(account) } returns ConfirmedAccountSelection({ true }, { true })
    }
}
