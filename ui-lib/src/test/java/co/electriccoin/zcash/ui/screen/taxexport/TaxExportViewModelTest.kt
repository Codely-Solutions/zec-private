package co.electriccoin.zcash.ui.screen.taxexport

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.DistributionDimension
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.ExportTaxUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.util.FileShareUtil
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.Year
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxExportViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun downloadSharesTheSelectedAccountCsvBeforeReturning() = runTest {
        assertDownloadSharesCsv(Change.NONE)
    }

    @Test
    fun downloadCannotOpenChooserAfterRelock() = runTest { assertDownloadSharesCsv(Change.LOCK) }

    @Test
    fun unlockingAgainDoesNotAuthorizeTheOldDownload() = runTest { assertDownloadSharesCsv(Change.REUNLOCK) }

    @Test
    fun changingAccountsRefusesTheStagedDownload() = runTest { assertDownloadSharesCsv(Change.ACCOUNT) }

    @Test
    fun walletResetRefusesTheStagedDownload() = runTest { assertDownloadSharesCsv(Change.RESET) }

    @Test
    fun revokingTheGrantAfterStagingDeletesTheFileWithoutOpeningTheChooser() = runTest {
        assertDownloadSharesCsv(Change.HANDOFF)
    }

    private enum class Change { NONE, LOCK, REUNLOCK, ACCOUNT, RESET, HANDOFF }

    private suspend fun TestScope.assertDownloadSharesCsv(change: Change) {
        val application = RuntimeEnvironment.getApplication()
        val authority = FileShareUtil.ZASHI_INTERNAL_DATA_FOSS_AUTHORITY_DEBUG
        val year = Year.now().minusYears(1).value
        val name = "Keystone_Transaction_History_$year.csv"
        lateinit var output: File
        val uri = Uri.parse("content://$authority/tax_exports/$name")
        val transaction = mockk<ReceiveTransaction.Success> {
            every { timestamp } returns Instant.parse("$year-06-15T12:00:00Z")
            every { amount } returns Zatoshi(125_000_000)
        }
        val selection = MutableStateFlow<WalletAccount?>(EditAccountFixtures.zodl())
        val accountDataSource = mockk<AccountDataSource> {
            every { selectedAccount } returns selection
            coEvery { getSelectedAccount() } coAnswers { requireNotNull(selection.value) }
        }
        val appAccessSession = AppAccessSession().apply { unlock() }
        val walletWriteAccess = WalletWriteAccess()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val repository = mockk<TransactionRepository> {
            coEvery { getTransactions(any()) } coAnswers {
                readStarted.complete(Unit)
                releaseRead.await()
                listOf(transaction)
            }
        }
        val version = mockk<VersionInfo> {
            every { network } returns ZcashNetwork.Mainnet
            every { distribution } returns DistributionDimension.FOSS
            every { isDebuggable } returns true
        }
        val navigation = mockk<NavigationRouter>()
        val launchedOnBack = CompletableDeferred<Intent?>()
        every { navigation.back() } answers {
            launchedOnBack.complete(shadowOf(application).nextStartedActivity)
        }
        val export = ExportTaxUseCase(
            repository,
            accountDataSource,
            mockk<GetVersionInfoProvider> { every { this@mockk() } returns version },
            application,
            navigation,
            mockk(),
        )
        val scheduled = mutableListOf<File>()
        export.scheduleCleanup = {
            scheduled.add(it)
            if (change == Change.HANDOFF) {
                appAccessSession.lock()
                appAccessSession.unlock()
            }
        }
        val vm = TaxExportViewModel(
            GetSelectedWalletAccountUseCase(accountDataSource), export, navigation, appAccessSession, walletWriteAccess
        )
        val subscription = backgroundScope.launch { vm.state.collect {} }

        // FileProvider matches Android paths using '/', while Robolectric uses the host file system.
        mockkStatic(FileProvider::class)
        try {
            every { FileProvider.getUriForFile(application, authority, any()) } answers {
                output = thirdArg()
                uri
            }
            runCurrent()
            assertEquals(stringRes(R.string.taxExport_desc, stringRes(R.string.accounts_zashi)), vm.state.value.text)
            selection.value = EditAccountFixtures.keystone(isSelected = true)
            runCurrent()
            val screen = vm.state.value
            assertEquals(
                application.getString(R.string.taxExport_desc, application.getString(R.string.accounts_keystone)),
                screen.text.getString(application),
            )
            assertEquals(stringRes(R.string.taxExport_download), screen.exportButton.text)
            assertNull(shadowOf(application).nextStartedActivity)
            screen.exportButton.onClick()

            readStarted.await()
            runCurrent()
            when (change) {
                Change.NONE, Change.HANDOFF -> Unit
                Change.LOCK -> appAccessSession.lock()
                Change.REUNLOCK -> {
                    appAccessSession.lock()
                    appAccessSession.unlock()
                }
                Change.ACCOUNT -> selection.value = EditAccountFixtures.zodl()
                Change.RESET -> walletWriteAccess.beginReset()
            }
            releaseRead.complete(Unit)
            if (change != Change.NONE) {
                vm.state.first { !it.exportButton.isLoading }
                assertNull(shadowOf(application).nextStartedActivity)
                verify(exactly = 0) { navigation.back() }
                assertTrue(File(application.cacheDir, "tax_exports").listFiles().orEmpty().isEmpty())
                if (change == Change.HANDOFF) assertEquals(listOf(output.parentFile), scheduled)
                return
            }
            val chooser = assertNotNull(launchedOnBack.await())
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(
                application.getString(R.string.taxExport_taxFile),
                chooser.getCharSequenceExtra(Intent.EXTRA_TITLE),
            )
            val shared = assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, shared.action)
            assertEquals(FileShareUtil.ZASHI_INTERNAL_DATA_MIME_TYPE, shared.type)
            assertTrue(shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(uri, shared.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            assertEquals(uri, shared.clipData?.getItemAt(0)?.uri)
            assertContains(output.readLines(), "06/15/$year 12:00:00,1.25,ZEC,,,,,")
            assertEquals(name, output.name)
            assertEquals(listOf(output.parentFile), scheduled)
            output.parentFile?.deleteRecursively()
            verify(exactly = 1) { FileProvider.getUriForFile(application, authority, output) }
            verify(exactly = 1) { navigation.back() }
        } finally {
            subscription.cancel()
            vm.viewModelScope.cancel()
            unmockkStatic(FileProvider::class)
        }
    }
}
