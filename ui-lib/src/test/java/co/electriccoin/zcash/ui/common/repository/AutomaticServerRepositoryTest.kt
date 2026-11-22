package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.provider.WalletServerSelectionProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Repository-level resolution of Automatic vs Manual server selection (MOB-1144), including the
 * migration rule: a null preference (wallets from before the setting existed) is Manual only when the
 * wallet points at a custom, non-bundled endpoint, and Automatic otherwise.
 *
 * Also covers the app-side half of the server switch hysteresis (MOB-1832): the guards and the arguments
 * handed to the SDK's `evaluateServerSwitch`, which owns the thresholds themselves, plus the resilience of
 * the foreground lane itself.
 *
 * Note: `init()` launches the lane on an internal `Dispatchers.IO` scope, so the tests drive
 * `observeSwitchCandidates` - the lane `init()` launches - on the test scope instead. Wiring `init()` to
 * the real foreground signal is exercised by the `ChooseServerSelectionTest` instrumentation test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticServerRepositoryTest {
    private val default = endpoint("zec.rocks")
    private val known = listOf(default, endpoint("na.zec.rocks"))

    private val serverSelectionProvider = mockk<WalletServerSelectionProvider> {
        every { serverSelection } returns MutableStateFlow(null)
        every { writeVersion } returns 7L
        coEvery { storeServerSelection(any(), any(), any()) } returns true
    }
    private val persistedWallet = MutableStateFlow<PersistableWallet?>(null)
    private val persistableWalletProvider =
        mockk<PersistableWalletProvider>(relaxed = true) { every { persistableWallet } returns persistedWallet }
    private val lightWalletEndpointProvider =
        mockk<LightWalletEndpointProvider>(relaxed = true) { every { getEndpoints() } returns known }
    private val localBalances = MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null)
    private val synchronizer = mockk<Synchronizer>(relaxed = true) { every { walletBalances } returns localBalances }
    private val synchronizerProvider =
        mockk<SynchronizerProvider>(relaxed = true).also {
            coEvery { it.getSynchronizerOrNull() } returns synchronizer
            every { it.synchronizer } returns MutableStateFlow<Synchronizer?>(synchronizer)
        }

    /**
     * A relaxed mock hands back a non-null value for `.value`, which the repository would read as
     * "a transaction is in flight", so both proposal repositories are stubbed with explicit empty state.
     */
    private val zashiProposalRepository =
        mockk<ZashiProposalRepository>(relaxed = true) {
            every { transactionProposal } returns MutableStateFlow<TransactionProposal?>(null)
            every { submitState } returns MutableStateFlow<SubmitProposalState?>(null)
        }
    private val keystoneProposalRepository =
        mockk<KeystoneProposalRepository>(relaxed = true) {
            every { transactionProposal } returns MutableStateFlow<TransactionProposal?>(null)
            every { submitState } returns MutableStateFlow<SubmitProposalState?>(null)
        }

    private val ledgerProposalPipeline =
        mockk<LedgerProposalPipeline>(relaxed = true) {
            every { transactionProposal } returns MutableStateFlow<TransactionProposal?>(null)
            every { submitState } returns MutableStateFlow<SubmitProposalState?>(null)
        }

    private val timeSource = TestTimeSource()

    private val repository =
        AutomaticServerRepositoryImpl(
            zashiProposalRepository = zashiProposalRepository,
            keystoneProposalRepository = keystoneProposalRepository,
            ledgerProposalPipeline = ledgerProposalPipeline,
            applicationStateProvider = mockk(relaxed = true),
            synchronizerProvider = synchronizerProvider,
            persistableWalletProvider = persistableWalletProvider,
            lightWalletEndpointProvider = lightWalletEndpointProvider,
            walletServerSelectionProvider = serverSelectionProvider,
            evaluationInterval = EvaluationInterval(MINIMUM_EVALUATION_INTERVAL, timeSource)
        )

    @Test
    fun explicitAutomaticPreferenceIsAutomatic() =
        runTest {
            selectServer(true)
            assertEquals(true, repository.isServerAutomatic())
        }

    @Test
    fun explicitManualPreferenceIsManual() =
        runTest {
            selectServer(false)
            assertEquals(false, repository.isServerAutomatic())
        }

    @Test
    fun nullPreferenceWithCustomEndpointIsManual() =
        runTest {
            selectServer(null, endpoint("custom.example.com"))

            assertEquals(false, repository.isServerAutomatic())
        }

    @Test
    fun nullPreferenceWithBundledEndpointIsAutomatic() =
        runTest {
            selectServer(null, known.last())

            assertEquals(true, repository.isServerAutomatic())
        }

    @Test
    fun nullPreferenceWithoutWalletIsAutomatic() =
        runTest {
            coEvery { serverSelectionProvider.getServerSelection() } returns null

            assertEquals(true, repository.isServerAutomatic())
        }

    @Test
    fun automaticIsNeverCustom() =
        runTest {
            selectServer(true)
            assertEquals(false, repository.isServerCustom())
        }

    @Test
    fun manualWithBundledEndpointIsNotCustom() =
        runTest {
            selectServer(false, known.last())

            assertEquals(false, repository.isServerCustom())
        }

    @Test
    fun manualWithNonBundledEndpointIsCustom() =
        runTest {
            selectServer(false, endpoint("custom.example.com"))

            assertEquals(true, repository.isServerCustom())
        }

    @Test
    fun manualWithoutWalletIsNotCustom() =
        runTest {
            coEvery { serverSelectionProvider.getServerSelection() } returns null

            assertEquals(false, repository.isServerCustom())
        }

    @Test
    fun manualSelectionSkipsTheSdkBenchmark() =
        runTest {
            selectServer(false)

            assertNull(repository.evaluateServerSwitch()?.endpoint)
            coVerify(exactly = 0) { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) }
        }

    @Test
    fun inTransactionStateSkipsTheSdkBenchmark() =
        runTest {
            selectServer(true)
            every { zashiProposalRepository.submitState } returns MutableStateFlow(SubmitProposalState.Submitting)

            assertNull(repository.evaluateServerSwitch()?.endpoint)
            coVerify(exactly = 0) { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) }
        }

    @Test
    fun aPendingLedgerProposalSkipsTheSdkBenchmark() =
        runTest {
            selectServer(true)
            every { ledgerProposalPipeline.transactionProposal } returns
                MutableStateFlow<TransactionProposal?>(mockk<TransactionProposal>())

            assertNull(repository.evaluateServerSwitch()?.endpoint)
            coVerify(exactly = 0) { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) }
        }

    @Test
    fun aPendingLedgerSubmitStateSkipsTheSdkBenchmark() =
        runTest {
            selectServer(true)
            every { ledgerProposalPipeline.submitState } returns MutableStateFlow(SubmitProposalState.Submitting)

            assertNull(repository.evaluateServerSwitch()?.endpoint)
            coVerify(exactly = 0) { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) }
        }

    @Test
    fun sdkDecisionToStayIsPassedThrough() =
        runTest {
            selectServer(true, known.first())
            coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } returns null

            assertNull(repository.evaluateServerSwitch()?.endpoint)
        }

    @Test
    fun sdkDecisionToSwitchIsPassedThrough() =
        runTest {
            selectServer(true, known.first())
            coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } returns known.last()

            assertEquals(known.last(), repository.evaluateServerSwitch()?.endpoint)
        }

    @Test
    fun sdkIsCalledWithTheCurrentEndpointAndEveryKnownCandidate() =
        runTest {
            selectServer(true, known.first())
            coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } returns null

            repository.evaluateServerSwitch()

            coVerify(exactly = 1) {
                synchronizer.evaluateServerSwitch(
                    current = known.first(),
                    candidates = known,
                    fetchThreshold = 5.seconds,
                    blocksToFetch = 1
                )
            }
        }

    /**
     * The wait is bounded, so this only pins that a snapshot which has not arrived yet still holds the
     * switch back - [switchIsAppliedAnywayWhenTheLocalBalanceSnapshotNeverArrives] pins the other end.
     */
    @Test
    fun switchCandidateWaitsForTheLocalBalanceSnapshot() =
        runTest {
            stubSwitchDecision(balances = null)

            assertNull(withTimeoutOrNull(1.seconds) { repository.resolveSwitchCandidate()?.endpoint })
        }

    @Test
    fun switchCandidateIsReleasedByTheLocalBalanceSnapshot() =
        runTest {
            stubSwitchDecision(balances = emptyMap())

            assertEquals(known.last(), repository.resolveSwitchCandidate()?.endpoint)
        }

    @Test
    fun switchIsAppliedAnywayWhenTheLocalBalanceSnapshotNeverArrives() =
        runTest {
            stubSwitchDecision(balances = null)

            assertEquals(known.last(), repository.resolveSwitchCandidate()?.endpoint)
        }

    @Test
    fun anAppliedSwitchIsConfirmedToTheSdk() =
        runTest {
            stubSwitchDecision(balances = emptyMap())

            val foregroundEdges = MutableSharedFlow<Unit>()
            repository.observeSwitchCandidates(foregroundEdges).launchIn(backgroundScope)
            runCurrent()

            foregroundEdges.emit(Unit)
            runCurrent()

            coVerify(exactly = 1) {
                serverSelectionProvider.storeServerSelection(
                    WalletServerSelection(known.last(), true), 7L, WalletServerSelection(known.first(), true)
                )
            }
            coVerify(exactly = 1) { synchronizer.confirmServerSwitch(known.last()) }
        }

    @Test
    fun aRefusedConditionalWriteIsNotConfirmedToTheSdk() =
        runTest {
            stubSwitchDecision(balances = emptyMap())
            coEvery { serverSelectionProvider.storeServerSelection(any(), any(), any()) } returns false
            val foregroundEdges = MutableSharedFlow<Unit>()
            repository.observeSwitchCandidates(foregroundEdges).launchIn(backgroundScope)
            runCurrent()

            foregroundEdges.emit(Unit)
            runCurrent()

            coVerify(exactly = 1) {
                serverSelectionProvider.storeServerSelection(
                    WalletServerSelection(known.last(), true), 7L, WalletServerSelection(known.first(), true)
                )
            }
            coVerify(exactly = 0) { synchronizer.confirmServerSwitch(any()) }
        }

    @Test
    fun aDeclinedSwitchIsNotConfirmedToTheSdk() =
        runTest {
            stubSwitchDecision(balances = emptyMap())
            coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } returns
                endpoint("no.longer.offered")

            val foregroundEdges = MutableSharedFlow<Unit>()
            repository.observeSwitchCandidates(foregroundEdges).launchIn(backgroundScope)
            runCurrent()

            foregroundEdges.emit(Unit)
            runCurrent()

            coVerify(exactly = 0) { serverSelectionProvider.storeServerSelection(any(), any(), any()) }
            coVerify(exactly = 0) { synchronizer.confirmServerSwitch(any()) }
        }

    @Test
    fun aCancelledEvaluationAppliesNothing() =
        runTest {
            stubSwitchDecision(balances = emptyMap())
            coEvery {
                synchronizer.evaluateServerSwitch(any(), any(), any(), any())
            } coAnswers { awaitCancellation() }

            val foregroundEdges = MutableSharedFlow<Unit>()
            val lane = repository.observeSwitchCandidates(foregroundEdges).launchIn(backgroundScope)
            runCurrent()

            foregroundEdges.emit(Unit)
            runCurrent()
            lane.cancelAndJoin()

            coVerify(exactly = 0) { serverSelectionProvider.storeServerSelection(any(), any(), any()) }
            coVerify(exactly = 0) { synchronizer.confirmServerSwitch(any()) }
        }

    @Test
    fun aFailedEvaluationStillCountsAgainstTheEvaluationInterval() =
        runTest {
            stubSwitchDecision(balances = emptyMap())
            coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } throws
                IllegalStateException("benchmark failed")

            val foregroundEdges = MutableSharedFlow<Unit>()
            repository.observeSwitchCandidates(foregroundEdges).launchIn(backgroundScope)
            runCurrent()

            foregroundEdges.emit(Unit)
            runCurrent()
            foregroundEdges.emit(Unit)
            runCurrent()

            coVerify(exactly = 1) { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) }
        }

    @Test
    fun theLaneSurvivesAFailedEvaluationAndKeepsServingTheNextEdge() =
        runTest {
            stubSwitchDecision(balances = emptyMap())
            coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } throws
                IllegalStateException("benchmark failed")

            val foregroundEdges = MutableSharedFlow<Unit>()
            repository.observeSwitchCandidates(foregroundEdges).launchIn(backgroundScope)
            runCurrent()

            foregroundEdges.emit(Unit)
            runCurrent()

            coVerify(exactly = 0) { serverSelectionProvider.storeServerSelection(any(), any(), any()) }

            coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } returns known.last()
            timeSource += MINIMUM_EVALUATION_INTERVAL
            foregroundEdges.emit(Unit)
            runCurrent()

            coVerify(exactly = 1) {
                serverSelectionProvider.storeServerSelection(
                    WalletServerSelection(known.last(), true), 7L, WalletServerSelection(known.first(), true)
                )
            }
        }

    @Test
    fun theLaneSurvivesAFailureToApplyTheSwitch() =
        runTest {
            stubSwitchDecision(balances = emptyMap())
            coEvery { serverSelectionProvider.storeServerSelection(any(), any(), any()) } throws IllegalStateException("prefs failed")

            val foregroundEdges = MutableSharedFlow<Unit>()
            repository.observeSwitchCandidates(foregroundEdges).launchIn(backgroundScope)
            runCurrent()

            foregroundEdges.emit(Unit)
            runCurrent()

            coVerify(exactly = 1) {
                serverSelectionProvider.storeServerSelection(
                    WalletServerSelection(known.last(), true), 7L, WalletServerSelection(known.first(), true)
                )
            }
        }

    @Test
    fun aSecondForegroundEdgeInsideTheEvaluationIntervalIsNotEvaluated() =
        runTest {
            stubSwitchDecision(balances = emptyMap())
            coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } returns null

            val foregroundEdges = MutableSharedFlow<Unit>()
            repository.observeSwitchCandidates(foregroundEdges).launchIn(backgroundScope)
            runCurrent()

            foregroundEdges.emit(Unit)
            runCurrent()
            foregroundEdges.emit(Unit)
            runCurrent()

            coVerify(exactly = 1) { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) }
        }

    private fun stubSwitchDecision(balances: Map<AccountUuid, AccountBalance>?) {
        val wallet = wallet(known.first())
        selectServer(true)
        persistedWallet.value = wallet
        localBalances.value = balances
        coEvery { synchronizer.evaluateServerSwitch(any(), any(), any(), any()) } returns known.last()
    }

    private fun selectServer(isAutomatic: Boolean?, endpoint: LightWalletEndpoint = known.first()) {
        coEvery { serverSelectionProvider.getServerSelection() } returns WalletServerSelection(endpoint, isAutomatic)
    }

    private fun wallet(walletEndpoint: LightWalletEndpoint) =
        mockk<PersistableWallet> { every { endpoint } returns walletEndpoint }

    private fun endpoint(host: String) =
        LightWalletEndpoint(
            host = host,
            port = 443,
            isSecure = true
        )
}
