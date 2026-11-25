package co.electriccoin.zcash.ui.screen.migration.setup

import cash.z.ecc.android.sdk.MigrationAvailability
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.migration.MigrationGateInfo
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.AutomaticServerRepository
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationGateInfoUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationPrivacyOrReviewDestinationUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.migration.battery.MigrationBatteryArgs
import co.electriccoin.zcash.ui.screen.migration.howitworks.MigrationHowItWorksArgs
import co.electriccoin.zcash.ui.screen.migration.howitworks.MigrationHowItWorksVM
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The setup screen waits for the SDK's gate, and follows it while the wallet has no chain tip yet. */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationSetupVMGateTest {
    private val scheduler = TestCoroutineScheduler()
    private val spendable =
        mockk<WalletAccount>(relaxed = true) {
            every { isSpendable } returns true
            every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16)))
        }
    private val open = MigrationGateInfo.OPEN
    private val unsynced =
        MigrationGateInfo(
            MigrationAvailability.PausedUntilNetworkUpgrade(2_000L),
            estimatedActivation = null,
            isWalletTipUnknown = true,
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun MigrationSetupVM.currentGate() = state.value.content?.gate

    private fun errors() = ErrorMapperUseCase(
        reportAccess = mockk(relaxed = true),
        sendEmail = mockk(relaxed = true),
        viewOnlyContent = mockk(relaxed = true),
    )

    private fun vm(
        navigation: NavigationRouter = mockk(relaxed = true),
        destination: GetMigrationPrivacyOrReviewDestinationUseCase = mockk(relaxed = true),
        gateInfo: suspend () -> MigrationGateInfo,
    ) =
        MigrationSetupVM(
            getSelectedWalletAccount =
                mockk<GetSelectedWalletAccountUseCase> {
                    coEvery { this@mockk() } returns spendable
                    every { observe() } returns MutableStateFlow(spendable)
                },
            getOrchardBalance =
                mockk<GetOrchardBalanceUseCase> {
                    coEvery { this@mockk() } returns Zatoshi(100_000_000L)
                },
            exchangeRateRepository =
                mockk<ExchangeRateRepository>(relaxed = true) {
                    every { state } returns MutableStateFlow(ExchangeRateState.OptIn)
                },
            navigationRouter = navigation,
            errorStateMapper = errors(),
            getMigrationPrivacyOrReviewDestination = destination,
            getMigrationGateInfo =
                mockk<GetMigrationGateInfoUseCase> {
                    coEvery { this@mockk() } coAnswers { gateInfo() }
                },
            migrationUpgradeNotice = mockk(relaxed = true),
        )

    @Test
    fun bothModesKeepTheirScreenAndRetryAfterUnavailableTor() = runTest(UnconfinedTestDispatcher(scheduler)) {
        var torAvailable = false
        val tor = mockk<IsTorEnabledStorageProvider> {
            coEvery { get() } coAnswers {
                check(torAvailable) { "Synthetic unavailable Tor setting" }
                true
            }
        }
        val selected = mockk<GetSelectedWalletAccountUseCase> {
            coEvery { this@mockk() } returns spendable
        }
        val servers = mockk<AutomaticServerRepository> {
            coEvery { isServerCustom() } returns false
        }
        val destination = GetMigrationPrivacyOrReviewDestinationUseCase(selected, tor, servers)

        val immediateNavigation = mockk<NavigationRouter>(relaxed = true)
        val setup = vm(immediateNavigation, destination) { open }
        backgroundScope.launch { setup.state.collect { } }
        assertNotNull(setup.state.value.content).onModeChange(MigrationMode.IMMEDIATE)
        assertNotNull(setup.state.value.content).onConfirm()
        assertNotNull(setup.state.value.error)
        verify(exactly = 0) { immediateNavigation.forward(any<MigrationReviewArgs>()) }

        val automaticNavigation = mockk<NavigationRouter>(relaxed = true)
        val automatic = MigrationHowItWorksVM(automaticNavigation, errors(), destination)
        backgroundScope.launch { automatic.state.collect { } }
        assertNotNull(automatic.state.value.content).onContinue()
        assertNotNull(automatic.state.value.error)
        verify(exactly = 0) { automaticNavigation.forward(any<MigrationBatteryArgs>()) }

        torAvailable = true
        assertNotNull(setup.state.value.content).onConfirm()
        assertNotNull(automatic.state.value.content).onContinue()
        assertNull(setup.state.value.error)
        assertNull(automatic.state.value.error)
        verify(exactly = 1) { immediateNavigation.forward(any<MigrationReviewArgs>()) }
        verify(exactly = 1) { automaticNavigation.forward(any<MigrationBatteryArgs>()) }
    }

    @Test
    fun delayedImmediateConfirmationHoldsModeAndNextUntilItsResult() = runTest(UnconfinedTestDispatcher(scheduler)) {
        val result = CompletableDeferred<MigrationReviewArgs>()
        val destination = mockk<GetMigrationPrivacyOrReviewDestinationUseCase> {
            coEvery { this@mockk(MigrationMode.IMMEDIATE) } coAnswers { result.await() }
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val setup = vm(router, destination) { open }
        backgroundScope.launch { setup.state.collect { } }

        val visible = assertNotNull(setup.state.value.content)
        visible.onModeChange(MigrationMode.IMMEDIATE)
        assertNotNull(setup.state.value.content).onConfirm()
        val confirming = assertNotNull(setup.state.value.content)
        assertTrue(confirming.isConfirming)
        confirming.onModeChange(MigrationMode.AUTOMATIC)
        confirming.onConfirm()
        assertEquals(MigrationMode.IMMEDIATE, setup.state.value.content?.mode)
        verify(exactly = 0) { router.forward(MigrationHowItWorksArgs) }

        val accepted = MigrationReviewArgs(MigrationMode.IMMEDIATE, "synthetic-owner")
        result.complete(accepted)
        verify(exactly = 1) { router.forward(accepted) }
        assertEquals(false, setup.state.value.content?.isConfirming)
    }

    @Test
    fun chainTipArrivalKeepsTheConfirmedImmediateChoiceThroughThePendingResult() =
        runTest(UnconfinedTestDispatcher(scheduler)) {
            val result = CompletableDeferred<MigrationReviewArgs>()
            val destination = mockk<GetMigrationPrivacyOrReviewDestinationUseCase> {
                coEvery { this@mockk(MigrationMode.IMMEDIATE) } coAnswers { result.await() }
            }
            val router = mockk<NavigationRouter>(relaxed = true)
            val answers = ArrayDeque(listOf(unsynced, open))
            val setup = vm(router, destination) { if (answers.size > 1) answers.removeFirst() else answers.first() }
            backgroundScope.launch { setup.state.collect { } }
            val beforeTip = assertNotNull(setup.state.value.content)
            assertEquals(MigrationMode.IMMEDIATE, beforeTip.mode)
            beforeTip.onConfirm()

            advanceTimeBy(3_001L)
            val confirming = assertNotNull(setup.state.value.content)
            assertEquals(MigrationSetupGate.Open, confirming.gate)
            assertEquals(MigrationMode.IMMEDIATE, confirming.mode)
            assertTrue(confirming.isConfirming)
            val accepted = MigrationReviewArgs(MigrationMode.IMMEDIATE, "synthetic-owner")
            result.complete(accepted)
            verify(exactly = 1) { router.forward(accepted) }
            verify(exactly = 0) { router.forward(MigrationHowItWorksArgs) }
        }

    @Test
    fun aTapFromTheOldChoiceCannotConfirmADifferentModeAfterTheGateOpens() =
        runTest(UnconfinedTestDispatcher(scheduler)) {
            val router = mockk<NavigationRouter>(relaxed = true)
            val answers = ArrayDeque(listOf(unsynced, open))
            val setup = vm(router) { if (answers.size > 1) answers.removeFirst() else answers.first() }
            backgroundScope.launch { setup.state.collect { } }
            val beforeTip = assertNotNull(setup.state.value.content)
            advanceTimeBy(3_001L)
            assertEquals(MigrationMode.AUTOMATIC, setup.state.value.content?.mode)

            beforeTip.onConfirm()

            verify(exactly = 0) { router.forward(any()) }
        }

    @Test
    fun theScreenIsLoadingUntilTheGateHasAnswered() =
        runTest(UnconfinedTestDispatcher(scheduler)) {
            val answer = CompletableDeferred<MigrationGateInfo>()
            val vm = vm { answer.await() }
            backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.state.collect { } }

            assertNull(vm.state.value.content)
            assertTrue(vm.state.value.isLoading)

            answer.complete(open)

            assertNotNull(vm.state.value.content)
            assertEquals(MigrationSetupGate.Open, vm.currentGate())
            assertEquals(false, vm.state.value.isLoading)
        }

    @Test
    fun anUnsyncedWalletIsAskedAgainUntilItHasAChainTip() =
        runTest(UnconfinedTestDispatcher(scheduler)) {
            val answers = ArrayDeque(listOf(unsynced, unsynced, open))
            val vm = vm { if (answers.size > 1) answers.removeFirst() else answers.first() }
            backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { vm.state.collect { } }

            assertIs<MigrationSetupGate.ScheduledPaused>(vm.currentGate())

            advanceTimeBy(7_000L)

            assertEquals(MigrationSetupGate.Open, vm.currentGate())
        }
}
