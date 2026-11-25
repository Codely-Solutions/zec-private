package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.GiftWalletRestartRequiredException
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.GiftCardRepository
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.DeleteLegacySharedExportsUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletReportAccess
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.util.StringResource
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
import java.util.Locale
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
    private val sdkData = mockk<ResetWalletSdkDataUseCase>()
    private val biometrics = mockk<BiometricRepository>(relaxed = true)
    private val contacts = mockk<AddressBookRepository>(relaxed = true)
    private val metadata = mockk<MetadataRepository>(relaxed = true)
    private val gifts = mockk<GiftCardRepository>(relaxed = true)
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
    fun spanishAndEnglishResetConsentBothNameAllWalletsAndAndroidBackups() = runBlocking {
        createViewModel()
        val state = assertNotNull(vm.state.value.content)
        state.buttonState.onClick()
        val confirmedState = awaitState { it.content?.confirmationDialog != null }
        val confirmation = assertNotNull(confirmedState.content?.confirmationDialog)
        val checkbox = state.checkboxState
        val warning = assertIs<StringResource.ByResource>(checkbox.subtitle)
        val context = RuntimeEnvironment.getApplication()

        for ((language, allWallets) in listOf("en" to "all wallets", "es" to "todas las billeteras")) {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localized = context.createConfigurationContext(configuration)
            val text = localized.getString(warning.resource)
            assertTrue(text.contains(allWallets) && text.contains("Android"))
            val advice = localized.getString(R.string.deleteWallet_message2)
            val restore = localized.getString(R.string.deleteWallet_message4)
            val retention = localized.getString(R.string.delete_wallet_text_2)
            assertTrue(retention.contains(if (language == "en") "until cleanup" else "hasta que se limpien"))
            val final = localized.getString(assertIs<StringResource.ByResource>(confirmation.message).resource)
            assertTrue(advice.contains(if (language == "en") "If you know" else "Si conoces"))
            assertTrue(restore.contains("software") && restore.contains("hardware"))
            assertTrue(final.contains(if (language == "en") "restore your wallets" else "restaurar tus billeteras"))
        }
    }

    @Test
    fun confirmingWithTheDefaultCheckboxPreservesLocalFiles() = runBlocking {
        val erase = CompletableDeferred<Boolean>()
        coEvery { sdkData() } coAnswers { check(erase.await()) }
        createViewModel()
        val legacy = File(directory.root, "tax_exports/Zodl_Transaction_History_2025.csv").apply {
            parentFile?.mkdirs()
            writeText("legacy")
        }
        val staged = File(directory.root, "tax_exports/tax-current/Zodl_Transaction_History_2025.csv").apply {
            parentFile?.mkdirs()
            writeText("current")
        }
        val oldPczt = File(directory.root, "pczt_exports/pczt").apply {
            parentFile?.mkdirs()
            writeText("legacy")
        }
        val newPczt = File(directory.root, "pczt_exports/pczt-123/pczt").apply {
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
        assertFalse(oldPczt.exists())
        assertEquals("current", newPczt.readText())
        assertEquals("current", staged.readText())
        coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
        coVerify(exactly = 1) { sdkData() }
        coVerify(exactly = 0) { contacts.delete() }
        coVerify(exactly = 0) { metadata.delete() }
    }

    @Test
    fun cancelDoesNotResetAndPendingRetriesContinueTheConfirmedDeletion() = runBlocking {
        val retryErase = CompletableDeferred<Boolean>()
        var eraseAttempts = 0
        coEvery { sdkData() } coAnswers {
            when (eraseAttempts++) {
                0 -> error("Synthetic erase failure")
                1 -> check(retryErase.await())
                else -> Unit
            }
        }
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
        coVerify(exactly = 0) { sdkData() }

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
        coVerify(exactly = 2) { sdkData() }
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
        coVerify(exactly = 3) { sdkData() }
        coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
        coVerify(exactly = 1) { contacts.delete() }
        coVerify(exactly = 1) { metadata.delete() }
    }

    @Test
    fun pendingRootOwnerReceivesFirstFailureAfterSettingsOwnerIsReplaced(): Unit = runBlocking {
        val enteredErase = CompletableDeferred<Unit>()
        val firstResult = CompletableDeferred<Boolean>()
        var firstAttempt = true
        coEvery { sdkData() } coAnswers {
            if (firstAttempt) {
                firstAttempt = false
                enteredErase.complete(Unit)
                check(firstResult.await())
            }
        }
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
        coEvery { sdkData() } returns Unit
        createViewModel()
        progress.store(ResetStage.ERASE_SDK)
        val marker = File(directory.root, "wallet-reset-progress")
        marker.writeText("incomplete stage")

        assertNotNull(awaitState { it.content?.isPending == true }.content).buttonState.onClick()
        val failure = awaitState { it.error != null && it.content?.buttonState?.isLoading == false }
        val sheet = assertIs<LceError.BottomSheet>(failure.error).state
        assertEquals(stringRes(R.string.wallet_reset_failed), sheet.message)
        coVerify(exactly = 0) { sdkData() }
        assertNotNull(sheet.secondaryAction).onClick()
        val chooser = assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        val draft = assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals("", draft.getStringExtra(Intent.EXTRA_TEXT))
        coVerify(exactly = 0) { diagnostics() }

        marker.writeText(ResetStage.ERASE_SDK.name)
        assertNotNull(awaitState { it.error == null && it.content?.isPending == true }.content).buttonState.onClick()
        awaitState { it.content?.isPending == false && !it.content.buttonState.isLoading }
        coVerify(exactly = 1) { sdkData() }
        coVerify(exactly = 0) { biometrics.requestBiometrics(any()) }
        coVerify(exactly = 0) { contacts.delete() }
        coVerify(exactly = 0) { metadata.delete() }
    }

    @Test
    fun failedGiftWalletClosureRequiresRestartBeforeResetCanContinue() = runBlocking {
        coEvery { gifts.quiesceForWalletReset() } throws GiftWalletRestartRequiredException()
        createViewModel()

        assertNotNull(vm.state.value.content).buttonState.onClick()
        val dialog = assertNotNull(awaitState { it.content?.confirmationDialog != null }.content?.confirmationDialog)
        dialog.primaryAction.onClick()
        val restart = awaitState { it.content?.requiresRestart == true && !it.content.buttonState.isLoading }

        assertNull(restart.error)
        assertEquals(ResetStage.ERASE_SDK, progress.getStage())
        assertNotNull(restart.content).buttonState.onClick()
        coVerify(exactly = 1) { gifts.quiesceForWalletReset() }
        coVerify(exactly = 0) { sdkData() }

        vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        coEvery { gifts.quiesceForWalletReset() } returns Unit
        coEvery { sdkData() } returns Unit
        createViewModel()
        val afterRestart = awaitState { it.content?.isPending == true }
        assertFalse(assertNotNull(afterRestart.content).requiresRestart)
        afterRestart.content.buttonState.onClick()
        awaitState { it.content?.isPending == false }

        assertNull(progress.getStage())
        coVerify(exactly = 1) { sdkData() }
        coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
    }

    private fun createViewModel() {
        val context = mockk<Context> {
            every { noBackupFilesDir } returns directory.root
            every { cacheDir } returns directory.root
        }
        progress = ResetProgressStore(context, DirectorySync {})
        val writeAccess = WalletWriteAccess(progress)
        reportAccess = WalletReportAccess(AppAccessSession().apply { unlock() }, writeAccess, mockk(), mockk())
        assertTrue(reportAccess.captureWallet()())
        val preferences = mockk<PreferenceProvider> { coEvery { clearPreferences() } returns true }
        val reset = ResetZashiUseCase(
            resetWalletSdkData = sdkData,
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
            giftCardRepository = gifts,
            progressStore = progress,
            deleteSharedQRImages = mockk(relaxed = true),
            deleteLegacySharedExports = DeleteLegacySharedExportsUseCase(context),
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
