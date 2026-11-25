package co.electriccoin.zcash.ui.common.usecase

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.DistributionDimension
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.sentTransactionAmount
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportTaxUseCaseTest {
    @Test
    fun exportsExactReceiptsExternalPaymentsAndInternalFeesForTheSameUtcYear() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val year = Year.now(ZoneOffset.UTC).minusYears(1).value
        val selected = mockk<ZodlAccount>()
        val accounts = mockk<AccountDataSource> { coEvery { getSelectedAccount() } returns selected }
        val history = mockk<TransactionRepository> {
            coEvery { getTransactions() } returns transactions(year)
        }
        val versions = mockk<GetVersionInfoProvider>()
        every { versions.invoke() } returns mockk {
            every { network } returns ZcashNetwork.Mainnet
            every { distribution } returns DistributionDimension.FOSS
            every { isDebuggable } returns true
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        lateinit var file: File

        mockkStatic(FileProvider::class)
        try {
            every { FileProvider.getUriForFile(context, any(), any()) } answers {
                file = thirdArg()
                Uri.parse("content://synthetic/tax.csv")
            }
            ExportTaxUseCase(history, accounts, versions, context, router, mockk())()
        } finally {
            unmockkStatic(FileProvider::class)
        }

        assertEquals(Intent.ACTION_CHOOSER, shadowOf(context).nextStartedActivity.action)
        assertEquals("Zodl_Transaction_History_$year.csv", file.name)
        val date = "01/01/$year 00:00:00"
        val rows = file.readLines().map { it.split(',') }
        assertEquals(
            listOf(
                listOf(
                    "Date", "Received Quantity", "Received Currency", "Sent Quantity", "Sent Currency",
                    "Fee Amount", "Fee Currency", "Tag",
                ),
                listOf(date, "1000.00000001", "ZEC", "", "", "", "", ""),
                listOf(date, "", "", "1000.00000002", "ZEC", "1000.00000001", "ZEC", ""),
                listOf(date, "", "", "", "", "0.00001", "ZEC", ""),
                listOf(date, "", "", "", "", "0.00002", "ZEC", ""),
            ),
            rows,
        )
    }

    @Test
    fun csvEscapesCommasQuotesAndBothLineEndings() {
        val output = StringWriter()
        output.buffered().use {
            it.writeCsvRow(listOf("plain", "with,comma", "with\"quote", "with\rcarriage", "with\nnewline", ""))
        }

        assertEquals(
            "plain,\"with,comma\",\"with\"\"quote\",\"with\rcarriage\",\"with\nnewline\",\r\n",
            output.toString(),
        )
    }

    private fun transactions(year: Int): List<Transaction> {
        val date = Instant.parse("$year-01-01T00:00:00Z")
        fun receive(at: Instant) = mockk<ReceiveTransaction.Success> {
            every { timestamp } returns at
            every { amount } returns Zatoshi(100_000_000_001L)
        }
        fun send(overview: TransactionOverview, fee: Zatoshi?) = SendTransaction.Success(
            id = TransactionId.new(ByteArray(32)),
            amount = sentTransactionAmount(overview),
            timestamp = date,
            memoCount = 0,
            fee = fee,
            transactionOutputs = emptyList(),
            overview = overview,
            recipient = null,
        )
        val external = send(
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
        val migration = send(
            mockk {
                every { netValue } returns Zatoshi(1_000)
                every { poolCrossingValue } returns Zatoshi(500_000)
                every { sentNoteCount } returns 0
                every { receivedNoteCount } returns 0
                every { totalReceived } returns Zatoshi(500_000)
            },
            null,
        )
        val historical = send(
            mockk {
                every { netValue } returns Zatoshi(2_000)
                every { poolCrossingValue } returns null
                every { sentNoteCount } returns 0
                every { receivedNoteCount } returns 0
                every { totalReceived } returns Zatoshi(600_000)
            },
            Zatoshi(2_000),
        )
        return listOf(receive(date.minusSeconds(1)), receive(date), external, migration, historical)
    }
}
