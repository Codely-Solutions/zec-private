package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.util.FileShareUtil
import co.electriccoin.zcash.ui.util.FileShareUtil.ZASHI_INTERNAL_DATA_MIME_TYPE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.time.Year
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ExportTaxUseCase(
    private val transactionRepository: TransactionRepository,
    private val accountDataSource: AccountDataSource,
    private val versionInfoProvider: GetVersionInfoProvider,
    private val context: Context,
    private val navigationRouter: NavigationRouter,
    private val viewOnlyContent: ViewOnlyContent,
) {
    suspend operator fun invoke() =
        withContext(Dispatchers.IO) {
            val previousYear = Year.now(ZoneOffset.UTC).minusYears(1).value

            val outputFile =
                File(
                    File(context.cacheDir, "tax_exports").apply { mkdirs() },
                    when (accountDataSource.getSelectedAccount()) {
                        is KeystoneAccount -> {
                            "Keystone_Transaction_History_$previousYear.csv"
                        }

                        is LedgerAccount -> {
                            "Ledger_Transaction_History_$previousYear.csv"
                        }

                        is ViewOnlyAccount -> {
                            "${viewOnlyContent.taxExportFileNamePrefix}_Transaction_History_$previousYear.csv"
                        }

                        is ZodlAccount -> {
                            "Zodl_Transaction_History_$previousYear.csv"
                        }
                    }
                )

            writeCsvToFile(
                outputFile = outputFile,
                data = getCsvEntries(previousYear)
            )

            runCatching {
                val intent =
                    FileShareUtil.newShareContentIntent(
                        context = context,
                        file = outputFile,
                        shareText = context.getString(R.string.taxExport_taxFile),
                        sharePickerText = context.getString(R.string.taxExport_taxFile),
                        versionInfo = versionInfoProvider(),
                        fileType = ZASHI_INTERNAL_DATA_MIME_TYPE
                    )

                context.startActivity(intent)
            }

            navigationRouter.back()
        }

    private fun writeCsvToFile(
        outputFile: File,
        data: List<CsvEntry>
    ) {
        outputFile.outputStream().bufferedWriter().use { writer ->
            writer.writeCsvRow(
                listOf(
                    context.getString(R.string.tax_export_date),
                    context.getString(R.string.tax_export_received_quantity),
                    context.getString(R.string.tax_export_received_currency),
                    context.getString(R.string.tax_export_sent_quantity),
                    context.getString(R.string.tax_export_sent_currency),
                    context.getString(R.string.tax_export_fee_amount),
                    context.getString(R.string.tax_export_fee_currency),
                    context.getString(R.string.tax_export_tag)
                )
            )

            data.forEach {
                writer.writeCsvRow(
                    listOf(
                        it.date,
                        it.receivedQuantity,
                        it.receivedCurrency,
                        it.sentQuantity,
                        it.sentCurrency,
                        it.feeAmount,
                        it.feeCurrency,
                        it.tag,
                    )
                )
            }
        }
    }

    private suspend fun getCsvEntries(previousYear: Int) =
        transactionRepository
            .getTransactions()
            .mapNotNull { transaction ->
                val date = transaction.timestamp?.atZone(ZoneOffset.UTC) ?: return@mapNotNull null
                if (date.year != previousYear) return@mapNotNull null

                val dateString =
                    date.let {
                        val formatter =
                            DateTimeFormatter
                                .ofPattern("MM/dd/yyyy HH:mm:ss")
                                .withZone(ZoneOffset.UTC)

                        formatter.format(date)
                    } ?: return@mapNotNull null

                when (transaction) {
                    is SendTransaction.Success,
                    is SendTransaction.Pending -> {
                        val overview = transaction.overview
                        val debit = overview.netValue
                        // The SDK reports a known wallet-internal pool crossing's net debit as its fee.
                        val fee = if (overview.poolCrossingValue != null) debit else transaction.fee ?: Zatoshi(0)
                        val sentQuantity = debit.takeIf { it > fee }?.minus(fee)

                        CsvEntry(
                            date = dateString,
                            receivedQuantity = "",
                            receivedCurrency = "",
                            sentQuantity = sentQuantity?.toCsvQuantity().orEmpty(),
                            sentCurrency = CURRENCY_TICKER.takeIf { sentQuantity != null }.orEmpty(),
                            feeAmount = fee.toCsvQuantity(),
                            feeCurrency = CURRENCY_TICKER,
                            tag = ""
                        )
                    }

                    is SendTransaction.Failed -> {
                        null
                    }

                    is ReceiveTransaction.Success,
                    is ReceiveTransaction.Pending -> {
                        CsvEntry(
                            date = dateString,
                            receivedQuantity = transaction.amount.toCsvQuantity(),
                            receivedCurrency = CURRENCY_TICKER,
                            sentQuantity = "",
                            sentCurrency = "",
                            feeAmount = "",
                            feeCurrency = "",
                            tag = ""
                        )
                    }

                    is ReceiveTransaction.Failed -> {
                        null
                    }

                    is ShieldTransaction.Success,
                    is ShieldTransaction.Pending,
                    is ShieldTransaction.Failed -> {
                        null
                    }
                }
            }
}

private data class CsvEntry(
    val date: String,
    val receivedQuantity: String,
    val receivedCurrency: String,
    val sentQuantity: String,
    val sentCurrency: String,
    val feeAmount: String,
    val feeCurrency: String,
    val tag: String,
)

private fun Zatoshi.toCsvQuantity(): String =
    convertZatoshiToZec(scale = ZEC_DECIMAL_PLACES).stripTrailingZeros().toPlainString()

internal fun BufferedWriter.writeCsvRow(fields: List<String>) {
    write(fields.joinToString(",") { field ->
        if (field.any { it in ",\"\r\n" }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    })
    write("\r\n")
}

private const val ZEC_DECIMAL_PLACES = 8
