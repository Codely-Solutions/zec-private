package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringWriter
import java.time.Instant
import java.time.Year
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportTaxUseCaseTest {
    @Test
    fun exportedReceiptsSendsAndFeesKeepEightColumnsAndExactAmounts() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val year = Year.now().minusYears(1).value
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
            coEvery { getTransactions() } returns
                listOf(belowGroupingBoundary, aboveGroupingBoundary, sendWithFee, sendWithoutFee)
        }
        val accounts = mockk<AccountDataSource> {
            coEvery { getSelectedAccount() } returns mockk<ZodlAccount>()
        }
        val versions = mockk<GetVersionInfoProvider>()
        every { versions.invoke() } throws IllegalStateException("External sharing is disabled in this fixture")

        ExportTaxUseCase(transactions, accounts, versions, context, mockk(relaxed = true), mockk())()

        val output = File(context.cacheDir, "tax_exports/Zodl_Transaction_History_$year.csv")
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
