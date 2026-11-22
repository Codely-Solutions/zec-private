package co.electriccoin.zcash.ui.screen.restore.tor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.repository.WalletInitializationConflictException
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateSeedUseCase
import co.electriccoin.zcash.ui.design.component.NumberTextFieldInnerState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.restore.height.RestoreHeight
import co.electriccoin.zcash.ui.screen.restore.height.RestoreHeightVM
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedVM
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun enteredSeed() = RestoreSeedVM(
        navigationRouter = navigation,
        validateSeed = ValidateSeedUseCase(),
        readClipboardText = mockk { every { this@mockk() } returns words.joinToString(" ") },
        clearClipboard = mockk(relaxed = true),
    ).also { viewModels += it }

    private suspend fun TestScope.enterBirthday(seed: RestoreSeedVM): RestoreTorArgs {
        val forwarded = mutableListOf<Any>()
        every { navigation.forward(*anyVararg()) } answers {
            (args[0] as Array<*>).filterNotNull().forEach { forwarded.add(it) }
        }
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
