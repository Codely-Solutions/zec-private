package co.electriccoin.zcash.ui.screen.restore.tor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.repository.WalletInitializationConflictException
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.screen.restore.date.RestoreDateArgs
import co.electriccoin.zcash.ui.screen.restore.date.RestoreDateVM
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreEstimationArgs
import co.electriccoin.zcash.ui.screen.restore.estimation.RestoreEstimationVM
import co.electriccoin.zcash.ui.screen.restore.height.RestoreHeight
import co.electriccoin.zcash.ui.screen.restore.height.RestoreHeightVM
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedVM
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.toKotlinInstant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreTorVMTest {
    private val context = RuntimeEnvironment.getApplication()
    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private val repository = mockk<WalletRepository>()
    private val viewModels = mutableListOf<ViewModel>()
    private val birthday = 2_500_123L
    private val words = Mnemonics.MnemonicCode(ByteArray(32)).words.map { it.concatToString() }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun restoreWithAnExpiredSessionReturnsToSeedWithoutInitializing() = runVmTest {
        val vm = tor(enteredSeed(), RestoreTorArgs(sessionId = "expired-session", blockHeight = birthday))

        assertNotNull(vm.state.value.content).primary.onClick()
        runCurrent()

        verify(exactly = 1) { navigation.backTo(RestoreSeedArgs::class) }
        verify(exactly = 0) { repository.restoreWallet(any(), any(), any(), any()) }
        assertNull(vm.state.first { !it.isLoading }.error)
    }

    @Test
    fun restoreKeepsTheSeedAfterConflictAndClearsItOnlyAfterSuccessfulRetry() = runVmTest {
        val seed = enteredSeed()
        val args = enterBirthday(seed)
        val phrase = assertNotNull(seed.restoreSession.resolve(args.sessionId))
        val firstAttempt = CompletableDeferred<Unit>()
        val retry = CompletableDeferred<Unit>()
        val network = ZcashNetwork.fromResources(context)
        every { repository.restoreWallet(network, phrase, BlockHeight.new(birthday), true) } returnsMany
            listOf(firstAttempt, retry)
        val vm = tor(seed, args)

        val initial = assertNotNull(vm.state.value.content)
        assertFalse(initial.checkbox.isChecked)
        initial.checkbox.onClick()
        val enabled = assertNotNull(vm.state.first { it.content?.checkbox?.isChecked == true }.content)
        assertEquals(stringRes(R.string.torSettingsSheet_title), enabled.checkbox.title)
        assertEquals(stringRes(R.string.root_existingWallet_restore), enabled.primary.text)
        enabled.primary.onClick()

        val loading = assertNotNull(vm.state.first { it.content?.primary?.isLoading == true }.content)
        assertFalse(loading.secondary.isEnabled)
        loading.onBack()
        loading.secondary.onClick()
        loading.checkbox.onClick()
        enabled.primary.onClick()
        runCurrent()

        verify(exactly = 0) { navigation.back() }
        verify(exactly = 1) { repository.restoreWallet(network, phrase, BlockHeight.new(birthday), true) }
        assertTrue(assertNotNull(vm.state.value.content).checkbox.isChecked)
        assertTrue(seed.restoreSession.resolve(args.sessionId) === phrase)

        firstAttempt.completeExceptionally(WalletInitializationConflictException())
        val failed = vm.state.first { it.error != null && it.content?.primary?.isLoading == false }
        val errorSheet = assertIs<LceError.BottomSheet>(failed.error).state
        assertEquals(stringRes(R.string.wallet_initialization_conflict), errorSheet.message)
        assertTrue(seed.restoreSession.resolve(args.sessionId) === phrase)
        val retained = assertNotNull(seed.state.first { it?.nextButton != null })
        assertTrue(retained.seed.values.all { it.innerState.value.isNotBlank() })

        errorSheet.primaryAction.onClick()
        vm.state.first { it.error == null && it.content?.primary?.isLoading == true }
        assertTrue(seed.restoreSession.resolve(args.sessionId) === phrase)
        retry.complete(Unit)

        vm.state.first { !it.isLoading && it.error == null && it.content?.primary?.isLoading == false }
        assertNull(seed.restoreSession.resolve(args.sessionId))
        val cleared = assertNotNull(seed.state.first { state ->
            state != null && state.seed.values.all { it.innerState.value.isEmpty() }
        })
        assertNull(cleared.nextButton)
        verify(exactly = 2) { repository.restoreWallet(network, phrase, BlockHeight.new(birthday), true) }
    }

    @Test
    fun restoreFromMonthRetriesEstimationAndUsesTheDisplayedHeightAndTorChoice() = runVmTest {
        val forwarded = recordForwardedRoutes()
        val seed = enteredSeed()
        assertNotNull(seed.state.first { it != null }?.pasteButton).onClick()
        assertNotNull(seed.state.first { it?.nextButton != null }?.nextButton).onClick()
        runCurrent()
        val seedRoute = assertIs<RestoreHeight>(forwarded.single())
        val phrase = assertNotNull(seed.restoreSession.resolve(seedRoute.sessionId))
        val height = RestoreHeightVM(seedRoute, navigation).also { viewModels += it }
        assertNotNull(height.state.value.secondaryButton).onClick()
        val dateRoute = assertIs<RestoreDateArgs>(forwarded.last())
        assertEquals(seedRoute.sessionId, dateRoute.sessionId)

        val month = YearMonth.of(2024, 8)
        val expectedDate = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toKotlinInstant()
        val estimatedHeight = BlockHeight.new(birthday)
        val retry = CompletableDeferred<BlockHeight>()
        mockkObject(SdkSynchronizer.Companion)
        try {
            coEvery {
                SdkSynchronizer.estimateBirthdayHeight(context, expectedDate, VersionInfo.NETWORK)
            } throws IllegalStateException("Synthetic estimation failure") coAndThen { retry.await() }
            val date = RestoreDateVM(dateRoute, navigation, context, ErrorMapperUseCase(mockk(), mockk()))
                .also { viewModels += it }
            assertNotNull(date.state.value.content).onYearMonthChange(month)
            val picked = assertNotNull(date.state.first { it.content?.selection == month }.content)
            picked.primaryButton.onClick()

            val failed = date.state.first { it.error != null }
            assertEquals(month, failed.content?.selection)
            assertEquals(listOf(seedRoute, dateRoute), forwarded)
            assertSame(phrase, seed.restoreSession.resolve(seedRoute.sessionId))
            assertIs<LceError.BottomSheet>(failed.error).state.primaryAction.onClick()
            date.state.first { it.error == null && it.content?.primaryButton?.isLoading == true }
            assertEquals(listOf(seedRoute, dateRoute), forwarded)
            retry.complete(estimatedHeight)
            runCurrent()

            val estimateRoute = assertIs<RestoreEstimationArgs>(forwarded.last())
            assertEquals(seedRoute.sessionId, estimateRoute.sessionId)
            val estimate = RestoreEstimationVM(estimateRoute, navigation, mockk()).also { viewModels += it }
            assertEquals(stringResByNumber(birthday, 0), estimate.state.value.blockHeightText)
            estimate.state.value.primaryButton.onClick()
            val torRoute = assertIs<RestoreTorArgs>(forwarded.last())
            assertEquals(RestoreTorArgs(seedRoute.sessionId, birthday), torRoute)

            val network = ZcashNetwork.fromResources(context)
            val completedRestore = CompletableDeferred(Unit)
            every { repository.restoreWallet(network, phrase, estimatedHeight, true) } returns completedRestore
            val tor = tor(seed, torRoute)
            val unchecked = assertNotNull(tor.state.value.content)
            assertFalse(unchecked.checkbox.isChecked)
            unchecked.checkbox.onClick()
            val checked = assertNotNull(tor.state.first { it.content?.checkbox?.isChecked == true }.content)
            checked.primary.onClick()
            runCurrent()

            verify(exactly = 1) { repository.restoreWallet(network, phrase, estimatedHeight, true) }
            assertNull(seed.restoreSession.resolve(seedRoute.sessionId))
            coVerify(exactly = 2) {
                SdkSynchronizer.estimateBirthdayHeight(context, expectedDate, VersionInfo.NETWORK)
            }
        } finally {
            retry.cancel()
            unmockkObject(SdkSynchronizer.Companion)
        }
    }

    @Test
    fun leavingMonthPickerCancelsTheInFlightEstimate() = runVmTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        mockkObject(SdkSynchronizer.Companion)
        try {
            coEvery { SdkSynchronizer.estimateBirthdayHeight(context, any(), VersionInfo.NETWORK) } coAnswers {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val date = RestoreDateVM(
                RestoreDateArgs("opaque-test-session"),
                navigation,
                context,
                ErrorMapperUseCase(mockk(), mockk()),
            ).also { viewModels += it }
            val state = assertNotNull(date.state.value.content)
            state.primaryButton.onClick()
            started.await()

            state.onBack()
            date.viewModelScope.coroutineContext[Job]?.cancelAndJoin()

            assertTrue(cancelled.isCompleted)
            verify(exactly = 1) { navigation.back() }
            verify(exactly = 0) { navigation.forward(*anyVararg()) }
        } finally {
            unmockkObject(SdkSynchronizer.Companion)
        }
    }

    private fun enteredSeed() = RestoreSeedVM(
        navigationRouter = navigation,
        validateSeed = ValidateSeedUseCase(),
        readClipboardText = mockk { every { this@mockk() } returns words.joinToString(" ") },
        clearClipboard = mockk(relaxed = true),
    ).also { viewModels += it }

    private suspend fun TestScope.enterBirthday(seed: RestoreSeedVM): RestoreTorArgs {
        val forwarded = recordForwardedRoutes()
        assertNotNull(seed.state.first { it != null }?.pasteButton).onClick()
        assertNotNull(seed.state.first { it?.nextButton != null }?.nextButton).onClick()
        runCurrent()
        val seedRoute = assertIs<RestoreHeight>(forwarded.single())
        val height = RestoreHeightVM(seedRoute, navigation).also { viewModels += it }
        height.state.value.blockHeight.onValueChange(NumberTextFieldInnerState.fromAmount(birthday.toBigDecimal()))
        height.state.first { it.primaryButton.isEnabled }.primaryButton.onClick()
        assertEquals(2, forwarded.size)
        val torRoute = assertIs<RestoreTorArgs>(forwarded.last())
        assertEquals(seedRoute.sessionId, torRoute.sessionId)
        assertEquals(birthday, torRoute.blockHeight)
        return torRoute
    }

    private fun recordForwardedRoutes() = mutableListOf<Any>().also { forwarded ->
        every { navigation.forward(*anyVararg()) } answers {
            (args[0] as Array<*>).filterNotNull().forEach { forwarded.add(it) }
        }
    }

    private fun tor(seed: RestoreSeedVM, args: RestoreTorArgs) = RestoreTorVM(
        args = args,
        seedEntry = seed,
        navigationRouter = navigation,
        restoreWallet = RestoreWalletUseCase(repository, context),
        errorStateMapper = ErrorMapperUseCase(mockk(), mockk()),
    ).also { viewModels += it }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            val jobs = viewModels.mapNotNull { it.viewModelScope.coroutineContext[Job] }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
        }
    }
}
