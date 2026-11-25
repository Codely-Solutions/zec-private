package co.electriccoin.zcash.ui.common.usecase

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportPrivateDataUseCaseTest {
    @Test
    fun unresolvedViewOnlyOrChangedAccountCannotReadTheDatabase() = runTest {
        val access = AppAccessSession().also { it.unlock() }
        val accounts = MutableStateFlow<List<WalletAccount>?>(null)
        val biometrics = mockk<BiometricRepository>()
        val export = ExportPrivateDataUseCase(RuntimeEnvironment.getApplication(), biometrics, access,
            WalletWriteAccess(), accountSource(accounts))

        assertFalse(export { error("No database read before account hydration") })
        accounts.value = listOf(account(1, spendable = false))
        assertFalse(export { error("No database read for a view-only account") })
        coVerify(exactly = 0) { biometrics.requestBiometrics(any()) }

        accounts.value = listOf(account(1))
        coEvery { biometrics.requestBiometrics(any()) } answers { accounts.value = listOf(account(2)) }
        assertFalse(export { error("No database read after the approved account changes") })
        coVerify(exactly = 1) { biometrics.requestBiometrics(any()) }
    }

    @Test
    fun restoredExportRequiresNewApprovalAndRechecksThatGrantAtTheChooser() = runTest {
        val application = RuntimeEnvironment.getApplication()
        val access = AppAccessSession()
        val wallet = WalletWriteAccess()
        val biometrics = mockk<BiometricRepository>()
        val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(account(1)))
        val export = ExportPrivateDataUseCase(application, biometrics, access, wallet, accountSource(accounts))
        val source = File(application.cacheDir, "synthetic.db").apply { writeText("synthetic diagnostics") }
        var staged: File? = null
        export.scheduleCleanup = {
            assertFalse(it.exists())
            staged = it
        }
        assertFalse(export { error("No database read without an app grant") })
        coVerify(exactly = 0) { biometrics.requestBiometrics(any()) }
        access.unlock()
        coEvery { biometrics.requestBiometrics(any()) } throws BiometricsCancelledException()
        assertFalse(export { error("No database read without export-specific approval") })
        assertNull(staged)

        coEvery { biometrics.requestBiometrics(any()) } returns Unit
        mockkStatic(FileProvider::class)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            every { FileProvider.getUriForFile(application, any(), any()) } answers {
                assertEquals(source.readText(), thirdArg<File>().readText())
                access.lock()
                access.unlock()
                Uri.parse("content://synthetic/export")
            }
            assertFalse(export { source })
            assertFalse(checkNotNull(staged).exists())
            assertNull(shadowOf(application).nextStartedActivity)

            every { FileProvider.getUriForFile(application, any(), any()) } answers {
                runBlocking { wallet.beginReset() }
                Uri.parse("content://synthetic/export")
            }
            assertFalse(export { source })
            assertFalse(checkNotNull(staged).exists())
            assertNull(shadowOf(application).nextStartedActivity)
            wallet.finishReset()
            wallet.replaceWallet {}

            every { FileProvider.getUriForFile(application, any(), any()) } answers {
                accounts.value = listOf(account(1, spendable = false))
                Uri.parse("content://synthetic/export")
            }
            assertFalse(export { source })
            assertFalse(checkNotNull(staged).exists())
            assertNull(shadowOf(application).nextStartedActivity)
            accounts.value = listOf(account(1))

            val sourceCurrent = AtomicBoolean(true)
            every { FileProvider.getUriForFile(application, any(), any()) } answers {
                sourceCurrent.set(false)
                Uri.parse("content://synthetic/export")
            }
            assertFalse(export(sourceCurrent::get) { source })
            assertFalse(checkNotNull(staged).exists())
            assertNull(shadowOf(application).nextStartedActivity)

            every { FileProvider.getUriForFile(application, any(), any()) } returns
                Uri.parse("content://synthetic/export")
            sourceCurrent.set(true)
            assertTrue(export(sourceCurrent::get) { source })
            assertEquals(source.readText(), checkNotNull(staged).readText())
            assertEquals(Intent.ACTION_CHOOSER, shadowOf(application).nextStartedActivity.action)
            coVerify(exactly = 6) { biometrics.requestBiometrics(any()) }
        } finally {
            unmockkStatic(FileProvider::class)
            Dispatchers.resetMain()
        }
    }

    private fun accountSource(accounts: MutableStateFlow<List<WalletAccount>?>) =
        mockk<AccountDataSource> { every { allAccounts } returns accounts }

    private fun account(id: Byte, spendable: Boolean = true) =
        mockk<WalletAccount> {
            every { isSelected } returns true
            every { isSpendable } returns spendable
            every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16) { id }))
        }
}
