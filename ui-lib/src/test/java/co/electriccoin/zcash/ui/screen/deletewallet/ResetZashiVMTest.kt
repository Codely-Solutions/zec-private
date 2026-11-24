package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import android.content.Intent
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteLegacyTaxExportsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletReportAccess
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import co.electriccoin.zcash.ui.design.R as UIR

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResetZashiVMTest {
    @get:Rule val directory = TemporaryFolder()

    private val navigation = mockk<NavigationRouter>(relaxed = true)
    private val coordinator = mockk<WalletCoordinator>()
    private val biometrics = mockk<BiometricRepository>(relaxed = true)
    private val contacts = mockk<AddressBookRepository>(relaxed = true)
    private val metadata = mockk<MetadataRepository>(relaxed = true)
    private val diagnostics = mockk<GetSupportUseCase>()
    private lateinit var reportAccess: WalletReportAccess
    private lateinit var progress: ResetProgressStore
    private lateinit var newViewModel: () -> ResetZashiVM
    private lateinit var vm: ResetZashiVM

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = runBlocking {
        if (::vm.isInitialized) vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        Dispatchers.resetMain()
    }

    @Test
    fun confirmingWithTheDefaultCheckboxPreservesLocalFiles() = runBlocking {
        val erase = CompletableDeferred<Boolean>()
        every { coordinator.deleteSdkDataFlow() } returns flow { emit(erase.await()) }
        createViewModel()
        val legacy = File(directory.root, "tax_exports/Zodl_Transaction_History_2025.csv").apply {
            parentFile?.mkdirs()
            writeText("legacy")
        }
        val staged = File(directory.root, "tax_exports/tax-current/Zodl_Transaction_History_2025.csv").apply {
            parentFile?.mkdirs()
            writeText("current")
        }

        val initial = assertNotNull(vm.state.value.content)
        assertTrue(initial.checkboxState.isChecked)
        initial.buttonState.onClick()
        val sheet = assertNotNull(awaitState { it.content?.confirmationDialog != null }.content?.confirmationDialog)
        try {
            sheet.primaryAction.onClick()
            awaitState { it.content?.let { state -> state.isPending && state.buttonState.isLoading } == true }
        } finally {
            erase.complete(true)
        }

        awaitState { it.content?.isPending == false && !it.content.buttonState.isLoading }
        assertNull(progress.getStage())
        assertFalse(legacy.exists())
        assertEquals("current", staged.readText())
        coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
        verify(exactly = 1) { coordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { contacts.delete() }
        coVerify(exactly = 0) { metadata.delete() }
    }

    @Test
    fun cancelDoesNotResetAndPendingRetriesContinueTheConfirmedDeletion() = runBlocking {
        val retryErase = CompletableDeferred<Boolean>()
        every { coordinator.deleteSdkDataFlow() } returnsMany
            listOf(flowOf(false), flow { emit(retryErase.await()) }, flowOf(true))
        createViewModel()

        val initial = assertNotNull(vm.state.value.content)
        initial.checkboxState.onClick()
        val unchecked = assertNotNull(awaitState { it.content?.checkboxState?.isChecked == false }.content)
        assertEquals(stringRes(UIR.string.general_confirm), unchecked.buttonState.text)
        unchecked.buttonState.onClick()
        val sheet = assertNotNull(awaitState { it.content?.confirmationDialog != null }.content?.confirmationDialog)
        assertEquals(stringRes(R.string.deleteWallet_sheet_title), sheet.title)
        assertEquals(ButtonStyle.DESTRUCTIVE2, sheet.primaryAction.style)
        val cancel = assertNotNull(sheet.secondaryAction)
        assertEquals(stringRes(UIR.string.general_cancel), cancel.text)
        cancel.onClick()
        val cancelled = assertNotNull(awaitState { it.content?.confirmationDialog == null }.content)
        assertFalse(cancelled.checkboxState.isChecked)
        assertFalse(cancelled.isPending)
        assertNull(progress.getStage())
        coVerify(exactly = 0) { biometrics.requestBiometrics(any()) }
        verify(exactly = 0) { coordinator.deleteSdkDataFlow() }

        cancelled.buttonState.onClick()
        assertNotNull(awaitState { it.content?.confirmationDialog != null }.content?.confirmationDialog)
            .primaryAction.onClick()
        val failed = awaitState { it.error != null && it.content?.isPending == true }
        val failureSheet = assertIs<LceError.BottomSheet>(failed.error).state
        assertEquals(stringRes(R.string.wallet_reset_failed), failureSheet.message)
        val pending = assertNotNull(failed.content)
        assertNull(pending.confirmationDialog)
        assertEquals(stringRes(R.string.wallet_initialization_retry), pending.buttonState.text)
        assertEquals(ResetStage.ERASE_SDK, progress.getStage())
        coVerify(exactly = 1) { contacts.delete() }
        coVerify(exactly = 1) { metadata.delete() }
        pending.onBack()
        verify(exactly = 0) { navigation.back() }

        try {
            failureSheet.primaryAction.onClick()
            awaitState { it.error == null && it.content?.buttonState?.isLoading == true }
        } finally {
            retryErase.complete(false)
        }
        val retried = awaitState { it.error != null }
        verify(exactly = 2) { coordinator.deleteSdkDataFlow() }
        assertFalse(reportAccess.captureWallet()())
        assertNotNull(assertIs<LceError.BottomSheet>(retried.error).state.secondaryAction).onClick()
        val application = RuntimeEnvironment.getApplication()
        val chooser = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val draft = assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals("", draft.getStringExtra(Intent.EXTRA_TEXT))
        coVerify(exactly = 0) { diagnostics() }
        val dismissed = assertNotNull(awaitState { it.error == null && it.content?.isPending == true }.content)
        dismissed.buttonState.onClick()

        val completed = awaitState { it.content?.isPending == false && !it.content.buttonState.isLoading }
        assertNull(completed.content?.confirmationDialog)
        assertNull(completed.error)
        assertNull(progress.getStage())
        verify(exactly = 3) { coordinator.deleteSdkDataFlow() }
        coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
        coVerify(exactly = 1) { contacts.delete() }
        coVerify(exactly = 1) { metadata.delete() }
    }

    @Test
    fun pendingRootOwnerReceivesFirstFailureAfterSettingsOwnerIsReplaced(): Unit = runBlocking {
        val enteredErase = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<Boolean>()
        every { coordinator.deleteSdkDataFlow() } returnsMany listOf(
            flow {
                enteredErase.complete(Unit)
                emit(firstResult.await())
            },
            flowOf(true),
        )
        createViewModel()
        val settingsOwner = vm
        assertNotNull(vm.state.value.content).buttonState.onClick()
        val confirmed = awaitState { it.content?.confirmationDialog != null }
        val confirmation = assertNotNull(confirmed.content?.confirmationDialog)
        try {
            confirmation.primaryAction.onClick()
            withTimeout(10.seconds) { enteredErase.await() }
            vm = newViewModel()
            assertTrue(assertNotNull(vm.state.value.content).isPending)
            settingsOwner.viewModelScope.coroutineContext[Job]?.cancel()
        } finally {
            firstResult.complete(false)
            settingsOwner.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }

        val failure = awaitState { it.error != null && it.content?.isPending == true }
        val sheet = assertIs<LceError.BottomSheet>(failure.error).state
        assertEquals(stringRes(R.string.wallet_reset_failed), sheet.message)
        assertNotNull(sheet.secondaryAction).onClick()
        val chooser = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        coVerify(exactly = 0) { diagnostics() }
        val pending = assertNotNull(awaitState { it.error == null && it.content?.isPending == true }.content)
        pending.buttonState.onClick()
        awaitState { it.content?.isPending == false && !it.content.buttonState.isLoading }
        coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
    }

    @Test
    fun pendingStageReadFailureOffersPrivateSupportAndRetriesTheOriginalStage(): Unit = runBlocking {
        every { coordinator.deleteSdkDataFlow() } returns flowOf(true)
        createViewModel()
        progress.store(ResetStage.ERASE_SDK)
        val marker = File(directory.root, "wallet-reset-progress")
        marker.writeText("incomplete stage")

        assertNotNull(awaitState { it.content?.isPending == true }.content).buttonState.onClick()
        val failure = awaitState { it.error != null && it.content?.buttonState?.isLoading == false }
        val sheet = assertIs<LceError.BottomSheet>(failure.error).state
        assertEquals(stringRes(R.string.wallet_reset_failed), sheet.message)
        verify(exactly = 0) { coordinator.deleteSdkDataFlow() }
        assertNotNull(sheet.secondaryAction).onClick()
        val chooser = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        val draft = assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals("", draft.getStringExtra(Intent.EXTRA_TEXT))
        coVerify(exactly = 0) { diagnostics() }

        marker.writeText(ResetStage.ERASE_SDK.name)
        assertNotNull(awaitState { it.error == null && it.content?.isPending == true }.content).buttonState.onClick()
        awaitState { it.content?.isPending == false && !it.content.buttonState.isLoading }
        verify(exactly = 1) { coordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { biometrics.requestBiometrics(any()) }
        coVerify(exactly = 0) { contacts.delete() }
        coVerify(exactly = 0) { metadata.delete() }
    }

    private fun createViewModel() {
        val context = mockk<Context> {
            every { noBackupFilesDir } returns directory.root
            every { cacheDir } returns directory.root
        }
        progress = ResetProgressStore(context, DirectorySync {})
        val writeAccess = WalletWriteAccess(progress)
        reportAccess = WalletReportAccess(AppAccessSession().apply { unlock() }, writeAccess, mockk())
        assertTrue(reportAccess.captureWallet()())
        val preferences = mockk<PreferenceProvider> { coEvery { clearPreferences() } returns true }
        val reset = ResetZashiUseCase(
            walletCoordinator = coordinator,
            synchronizerProvider = mockk {
                coEvery { getSynchronizer() } returns mockk<CloseableSynchronizer>(relaxed = true)
            },
            standardPreferenceProvider = mockk<StandardPreferenceProvider> {
                coEvery { this@mockk.invoke() } returns preferences
            },
            encryptedPreferenceProvider = mockk<EncryptedPreferenceProvider> {
                coEvery { this@mockk.invoke() } returns preferences
                coEvery { purgeQuarantine() } returns true
            },
            homeMessageCacheRepository = mockk(relaxed = true),
            biometricRepository = biometrics,
            addressBookRepository = contacts,
            metadataRepository = metadata,
            migrationAppHooks = mockk(relaxed = true),
            giftCardRepository = mockk(relaxed = true),
            progressStore = progress,
            deleteSharedQRImages = mockk(relaxed = true),
            deleteLegacyTaxExports = DeleteLegacyTaxExportsUseCase(context),
            walletWriteAccess = writeAccess,
        )
        val sendEmail = SendEmailUseCase(RuntimeEnvironment.getApplication(), diagnostics, mockk())
        val errorMapper = ErrorMapperUseCase(sendEmail, mockk(), reportAccess)
        newViewModel = { ResetZashiVM(navigation, reset, errorMapper) }
        vm = newViewModel()
    }

    private suspend fun awaitState(predicate: (LceState<ResetZashiState>) -> Boolean) =
        withTimeout(10.seconds) { vm.state.first(predicate) }
}
