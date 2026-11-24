package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import android.net.Uri
import androidx.core.content.FileProvider
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.DistributionDimension
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.StringWriter
import java.time.Instant
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportTaxUseCaseTest {
    @Test
    fun exportedReceiptsSendsAndFeesKeepEightColumnsAndExactAmounts() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val year = Year.now().minusYears(1).value
        val taxCache = File(context.cacheDir, "tax_exports").apply { mkdirs() }
        val legacy = listOf(context.cacheDir, taxCache).flatMap { directory ->
            listOf("Zodl", "Ledger", "Keystone", "View_Only").map { prefix ->
                File(directory, "${prefix}_Transaction_History_$year.csv").apply { writeText("old export") }
            }
        }
        val similarlyNamedDirectory = File(context.cacheDir, "Zodl_Transaction_History_2000.csv").apply { mkdir() }
        val unrelated = File(taxCache, "Unrelated_Transaction_History_$year.csv").apply { writeText("unrelated") }
        val transactionTime = Instant.parse("$year-06-15T12:00:00Z")
        val belowGroupingBoundary = mockk<ReceiveTransaction.Pending> {
            every { timestamp } returns transactionTime
            every { amount } returns Zatoshi(99_999_999_999L)
        }
        val aboveGroupingBoundary = mockk<ReceiveTransaction.Success> {
            every { timestamp } returns transactionTime
            every { amount } returns Zatoshi(100_000_000_001L)
        }
        val sendWithFee = mockk<SendTransaction.Success> {
            every { timestamp } returns transactionTime
            every { amount } returns Zatoshi(200_000_000_003L)
            every { fee } returns Zatoshi(100_000_000_001L)
        }
        val sendWithoutFee = mockk<SendTransaction.Pending> {
            every { timestamp } returns transactionTime
            every { amount } returns Zatoshi(1)
            every { fee } returns null
        }
        val transactions = mockk<TransactionRepository> {
            coEvery { getTransactions(any()) } returns
                listOf(belowGroupingBoundary, aboveGroupingBoundary, sendWithFee, sendWithoutFee)
        }
        val accounts = mockk<AccountDataSource> {
            coEvery { getSelectedAccount() } returns EditAccountFixtures.zodl()
        }
        val versions = mockk<GetVersionInfoProvider>()
        every { versions.invoke() } returns mockk {
            every { network } returns ZcashNetwork.Mainnet
            every { distribution } returns DistributionDimension.FOSS
            every { isDebuggable } returns true
        }
        lateinit var output: File
        var registered = false
        mockkStatic(FileProvider::class)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            every { FileProvider.getUriForFile(context, any(), any()) } answers {
                output = thirdArg()
                Uri.parse("content://synthetic/tax.csv")
            }
            ExportTaxUseCase(transactions, accounts, versions, context, mockk(relaxed = true), mockk())
                .apply {
                    scheduleCleanup = { staging ->
                        assertTrue(staging.listFiles().orEmpty().isEmpty())
                        registered = true
                    }
                }(accounts.getSelectedAccount()) { true }
        } finally {
            unmockkStatic(FileProvider::class)
            Dispatchers.resetMain()
        }

        assertTrue(registered)
        assertEquals(android.content.Intent.ACTION_CHOOSER, shadowOf(context).nextStartedActivity.action)
        DeleteLegacyTaxExportsUseCase(context).onStartup()
        assertTrue(legacy.none { it.exists() })
        assertTrue(similarlyNamedDirectory.isDirectory)
        assertEquals("unrelated", unrelated.readText())
        assertEquals(taxCache, output.parentFile?.parentFile)
        val rows = output.readLines().map { it.split(',') }
        val date = "06/15/$year 12:00:00"
        assertEquals(
            listOf(
                listOf(
                    "Date", "Received Quantity", "Received Currency", "Sent Quantity", "Sent Currency",
                    "Fee Amount", "Fee Currency", "Tag",
                ),
                listOf(date, "999.99999999", "ZEC", "", "", "", "", ""),
                listOf(date, "1000.00000001", "ZEC", "", "", "", "", ""),
                listOf(date, "", "", "1000.00000002", "ZEC", "1000.00000001", "ZEC", ""),
                listOf(date, "", "", "0.00000001", "ZEC", "0", "ZEC", ""),
            ),
            rows,
        )
    }

    @Test
    fun failedRegistrationLeavesNoStagingDirectoryOrChooser() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val account = EditAccountFixtures.zodl()
        val transactions = mockk<TransactionRepository> {
            coEvery { getTransactions(any()) } returns emptyList()
        }
        lateinit var staging: File
        val useCase = ExportTaxUseCase(transactions, mockk(), mockk(), context, mockk(), mockk())
        useCase.scheduleCleanup = {
            staging = it
            assertTrue(it.listFiles().orEmpty().isEmpty())
            error("synthetic scheduler failure")
        }

        assertFailsWith<IllegalStateException> { useCase(account) { true } }
        assertFalse(staging.exists())
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun csvRowsEscapeDelimitersQuotesAndLineBreaks() {
        val output = StringWriter()
        output.buffered().use {
            it.writeCsvRow(listOf("plain", "with,comma", "with\"quote", "with\rcarriage", "with\nnewline", ""))
            it.writeCsvRow(listOf("second row"))
        }

        assertEquals(
            "plain,\"with,comma\",\"with\"\"quote\",\"with\rcarriage\",\"with\nnewline\",\r\nsecond row\r\n",
            output.toString(),
        )
    }
}
