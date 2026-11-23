package co.electriccoin.zcash.ui.screen.taxexport

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.DistributionDimension
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.ExportTaxUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Corrected report reproductions: the production VM owns errors from the entire export. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaxExportFailureTest {
    @Test
    fun blockedCacheWriteStaysOnScreenAndCanRetryTheRealExport() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val cache = Files.createTempDirectory(
            RuntimeEnvironment.getApplication().cacheDir.toPath(), "tax-test-"
        ).toFile()
        val directory = File(cache, "tax_exports").apply { writeText("blocks the directory") }
        val context = mockk<Context> {
            every { cacheDir } returns cache
            every { getString(any()) } returns "Export Tax File"
            every { startActivity(any()) } returns Unit
        }
        val accounts = accounts()
        val navigation = mockk<NavigationRouter>(relaxed = true)
        val version = mockk<VersionInfo> {
            every { network } returns ZcashNetwork.Mainnet
            every { distribution } returns DistributionDimension.FOSS
            every { isDebuggable } returns true
        }
        val export = ExportTaxUseCase(
            mockk<TransactionRepository> { coEvery { getTransactions(any()) } returns emptyList() },
            accounts, mockk { every { this@mockk() } returns version }, context, navigation, mockk(),
        )
        val store = ViewModelStore()
        val vm = TaxExportViewModel(GetSelectedWalletAccountUseCase(accounts), export, navigation)
        store.put("tax", vm)
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        mockkStatic(FileProvider::class)
        try {
            every { FileProvider.getUriForFile(any(), any(), any()) } returns Uri.parse("content://synthetic/tax.csv")
            vm.state.value.exportButton.onClick()
            vm.state.first { it.error != null && !it.exportButton.isLoading }
            verify(exactly = 0) { navigation.back() }
            assertTrue(vm.state.value.exportButton.isEnabled)
            assertTrue(directory.delete())
            // A failed chooser must also remain retryable and retire its staging file.
            every { context.startActivity(any()) } throws IOException("synthetic chooser failure")
            vm.state.value.exportButton.onClick()
            vm.state.first { it.error != null && !it.exportButton.isLoading }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            verify(exactly = 0) { navigation.back() }
            every { context.startActivity(any()) } returns Unit
            val shared = CompletableDeferred<Unit>()
            every { navigation.back() } answers { shared.complete(Unit) }
            vm.state.value.exportButton.onClick()
            shared.await()
            vm.state.first { !it.exportButton.isLoading }
            assertNull(vm.state.value.error)
            verify(exactly = 1) { navigation.back() }
            verify(exactly = 2) { context.startActivity(any()) }
        } finally {
            observer.cancel()
            store.clear()
            unmockkStatic(FileProvider::class)
            cache.deleteRecursively()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun actionSerializesRepeatedClicksAndCancellationDoesNotBecomeAnError() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val export = mockk<ExportTaxUseCase>()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        coEvery { export() } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val navigation = mockk<NavigationRouter>(relaxed = true)
        val store = ViewModelStore()
        val vm = TaxExportViewModel(GetSelectedWalletAccountUseCase(accounts()), export, navigation)
        store.put("tax", vm)
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        try {
            val staleClick = vm.state.value.exportButton.onClick
            staleClick()
            entered.await()
            assertTrue(vm.state.value.exportButton.isLoading)
            assertFalse(vm.state.value.exportButton.isEnabled)
            staleClick()
            coVerify(exactly = 1) { export() }
            store.clear()
            cancelled.await()
            assertNull(vm.state.value.error)
            verify(exactly = 0) { navigation.back() }
        } finally {
            observer.cancel()
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun backCancelsTheRealExportAndPreservesAPreviouslySharedFile() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val cache = Files.createTempDirectory(
            RuntimeEnvironment.getApplication().cacheDir.toPath(), "tax-cancel-"
        ).toFile()
        val prior = File(cache, "tax_exports/prior/history.csv").apply {
            parentFile.mkdirs()
            writeText("previously authorized CSV")
        }
        val context = mockk<Context> { every { cacheDir } returns cache }
        val entered = CompletableDeferred<Unit>()
        val transactions = mockk<TransactionRepository> {
            coEvery { getTransactions(any()) } coAnswers { entered.complete(Unit); awaitCancellation() }
        }
        val accounts = accounts()
        val navigation = mockk<NavigationRouter>(relaxed = true)
        val export = ExportTaxUseCase(transactions, accounts, mockk(), context, navigation, mockk())
        val vm = TaxExportViewModel(GetSelectedWalletAccountUseCase(accounts), export, navigation)
        val store = ViewModelStore().apply { put("tax", vm) }
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        try {
            val staleClick = vm.state.value.exportButton.onClick
            staleClick()
            entered.await()
            vm.state.value.onBack()
            vm.state.first { !it.exportButton.isLoading }
            staleClick()
            coVerify(exactly = 1) { transactions.getTransactions(any()) }
            assertTrue(prior.readText() == "previously authorized CSV")
            assertTrue(File(cache, "tax_exports").listFiles().orEmpty().single().name == "prior")
            assertNull(vm.state.value.error)
            verify(exactly = 1) { navigation.back() }
            verify(exactly = 0) { context.startActivity(any()) }
        } finally {
            observer.cancel()
            store.clear()
            cache.deleteRecursively()
            Dispatchers.resetMain()
        }
    }

    private fun accounts() = mockk<AccountDataSource> {
        val account = EditAccountFixtures.zodl()
        every { selectedAccount } returns MutableStateFlow<WalletAccount?>(account)
        coEvery { getSelectedAccount() } returns account
    }
}
