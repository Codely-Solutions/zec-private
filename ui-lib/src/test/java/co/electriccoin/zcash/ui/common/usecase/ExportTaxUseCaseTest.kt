package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip318Kind
import android.net.Uri
import androidx.core.content.FileProvider
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.DistributionDimension
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.sentTransactionAmount
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
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
import java.time.ZoneOffset
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
    fun downloadFromARestoredScreenStillRequiresExportAuthenticationBeforeReadingHistory() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val restoredEntry = "restored-tax-export"
        val active = MutableStateFlow(ActiveNavigationEntry(restoredEntry, null))
        val router = mockk<NavigationRouter> { every { activeEntry } returns active }
        val transactions = mockk<TransactionRepository>()
        val biometrics = mockk<BiometricRepository>()
        val export = ExportTaxUseCase(transactions, mockk(), mockk(), context, router, mockk(), biometrics)

        coEvery { biometrics.requestBiometrics(any()) } throws BiometricsCancelledException()
        export(EditAccountFixtures.zodl(), restoredEntry) { true }

        coEvery { biometrics.requestBiometrics(any()) } answers {
            active.value = ActiveNavigationEntry("scanner", null)
        }
        export(EditAccountFixtures.zodl(), restoredEntry) { true }

        coVerify(exactly = 2) { biometrics.requestBiometrics(any()) }
        coVerify(exactly = 0) { transactions.getTransactions(any()) }
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun exportedReceiptsSendsAndFeesKeepEightColumnsAndExactAmounts() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val year = Year.now(ZoneOffset.UTC).minusYears(1).value
        val taxCache = File(context.cacheDir, "tax_exports").apply { mkdirs() }
        val legacy = listOf(context.cacheDir, taxCache).flatMap { directory ->
            listOf("Zodl", "Ledger", "Keystone", "View_Only").map { prefix ->
                File(directory, "${prefix}_Transaction_History_$year.csv").apply { writeText("old export") }
            }
        }
        val similarlyNamedDirectory = File(context.cacheDir, "Zodl_Transaction_History_2000.csv").apply { mkdir() }
        val unrelated = File(taxCache, "Unrelated_Transaction_History_$year.csv").apply { writeText("unrelated") }
        val pcztCache = File(context.cacheDir, "pczt_exports").apply { mkdirs() }
        val oldPczt = File(pcztCache, "pczt").apply { writeText("legacy") }
        val newPczt = File(pcztCache, "pczt-123/pczt").apply {
            parentFile?.mkdirs()
            writeText("current")
        }
        val qrCache = File(context.cacheDir, "zodl_qr_images").apply { mkdirs() }
        val oldQr = File(qrCache, "zip_321_request_qr_123.png").apply { writeText("legacy QR") }
        val oldVkQr = File(qrCache, "zodl_viewing_key_qr_12345678-1234-1234-1234-123456789abc_123.png")
            .apply { writeText("legacy viewing-key QR") }
        val newQr = File(qrCache, "zip_321_request_qr_handoff-123.png").apply { writeText("active QR") }
        val unrelatedQr = File(qrCache, "unrelated-123.png").apply { writeText("unrelated") }
        val transactionTime = Instant.parse("$year-06-15T12:00:00Z")
        val belowGroupingBoundary = mockk<ReceiveTransaction.Pending> {
            every { timestamp } returns transactionTime
            every { amount } returns Zatoshi(99_999_999_999L)
        }
        val aboveGroupingBoundary = mockk<ReceiveTransaction.Success> {
            every { timestamp } returns transactionTime
            every { amount } returns Zatoshi(100_000_000_001L)
        }
        val outgoing = outgoingTransactions(transactionTime)
        val transactions = mockk<TransactionRepository> {
            coEvery { getTransactions(any()) } returns
                listOf(belowGroupingBoundary, aboveGroupingBoundary) + outgoing
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
            ExportTaxUseCase(
                transactions, accounts, versions, context, mockk(relaxed = true), mockk(), mockk(relaxed = true),
            )
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
        DeleteLegacySharedExportsUseCase(context).onStartup()
        assertTrue(legacy.none { it.exists() })
        assertFalse(oldPczt.exists())
        assertEquals("current", newPczt.readText())
        assertFalse(oldQr.exists())
        assertFalse(oldVkQr.exists())
        assertEquals("active QR", newQr.readText())
        assertEquals("unrelated", unrelatedQr.readText())
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
                listOf(date, "", "", "", "", "0.00001", "ZEC", ""),
                listOf(date, "", "", "", "", "0.00002", "ZEC", ""),
            ),
            rows,
        )
    }

    private fun outgoingTransactions(transactionTime: Instant): List<SendTransaction> {
        fun sent(overview: TransactionOverview, fee: Zatoshi?) = SendTransaction.Success(
            id = TransactionId.new(ByteArray(32)),
            amount = sentTransactionAmount(overview),
            timestamp = transactionTime,
            memoCount = 0,
            fee = fee,
            transactionOutputs = emptyList(),
            overview = overview,
            recipient = null,
        )
        val ordinaryTransfer = sent(
            mockk {
                every { netValue } returns Zatoshi(200_000_000_003L)
                every { poolCrossingValue } returns null
                every { sentNoteCount } returns 1
                every { raw } returns null
                every { minedHeight } returns BlockHeight.new(100L)
                every { zip318Kind } returns Zip318Kind.TRANSFER
            },
            Zatoshi(100_000_000_001L),
        )
        val sendWithoutFee = mockk<SendTransaction.Pending> {
            every { timestamp } returns transactionTime
            every { overview } returns mockk {
                every { netValue } returns Zatoshi(1)
                every { poolCrossingValue } returns null
            }
            every { fee } returns null
        }
        val migration = sent(
            mockk {
                every { netValue } returns Zatoshi(1_000)
                every { poolCrossingValue } returns Zatoshi(500_000)
            },
            null,
        )
        val historicalSelfTransfer = sent(
            mockk {
                every { netValue } returns Zatoshi(2_000)
                every { poolCrossingValue } returns null
                every { sentNoteCount } returns 0
                every { receivedNoteCount } returns 0
                every { totalReceived } returns Zatoshi(600_000)
            },
            Zatoshi(2_000),
        )
        return listOf(ordinaryTransfer, sendWithoutFee, migration, historicalSelfTransfer)
    }

    @Test
    fun failedRegistrationLeavesNoStagingDirectoryOrChooser() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val account = EditAccountFixtures.zodl()
        val transactions = mockk<TransactionRepository> {
            coEvery { getTransactions(any()) } returns emptyList()
        }
        lateinit var staging: File
        val useCase = ExportTaxUseCase(transactions, mockk(), mockk(), context, mockk(), mockk(), mockk(relaxed = true))
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
    fun stagedTaxReportCannotOpenTheChooserOverAnotherScreenOrInTheBackground() = runTest {
        assertStagedTaxChooserRefused(ActiveNavigationEntry("scanner", null))
        assertStagedTaxChooserRefused(ActiveNavigationEntry("tax", null, hostResumed = false))
    }

    private suspend fun TestScope.assertStagedTaxChooserRefused(afterStaging: ActiveNavigationEntry) {
        val context = RuntimeEnvironment.getApplication()
        val account = EditAccountFixtures.zodl()
        val transactions = mockk<TransactionRepository> {
            coEvery { getTransactions(any()) } returns emptyList()
        }
        val version = mockk<VersionInfo> {
            every { network } returns ZcashNetwork.Mainnet
            every { distribution } returns DistributionDimension.FOSS
            every { isDebuggable } returns true
        }
        val versions = mockk<GetVersionInfoProvider> { every { this@mockk() } returns version }
        val entry = MutableStateFlow<ActiveNavigationEntry?>(ActiveNavigationEntry("tax", null))
        val router = mockk<NavigationRouter>(relaxed = true) {
            every { activeEntry } returns entry
        }
        lateinit var staged: File
        mockkStatic(FileProvider::class)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            every { FileProvider.getUriForFile(context, any(), any()) } answers {
                staged = thirdArg()
                assertTrue(staged.isFile)
                entry.value = afterStaging
                Uri.parse("content://synthetic/tax.csv")
            }
            val accounts = mockk<AccountDataSource> { coEvery { getSelectedAccount() } returns account }
            ExportTaxUseCase(transactions, accounts, versions, context, router, mockk(), mockk(relaxed = true))
                .apply { scheduleCleanup = {} }(account, sourceEntryId = "tax") { true }
        } finally {
            Dispatchers.resetMain()
            unmockkStatic(FileProvider::class)
        }

        assertNull(shadowOf(context).nextStartedActivity)
        assertFalse(staged.exists())
        assertFalse(staged.parentFile?.exists() ?: false)
        verify(exactly = 0) { router.back() }
        verify(exactly = 0) { router.custom(any()) }
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
