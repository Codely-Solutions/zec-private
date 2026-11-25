package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.ShieldTransaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import co.electriccoin.zcash.ui.util.FileShareUtil
import co.electriccoin.zcash.ui.util.FileShareUtil.ZASHI_INTERNAL_DATA_MIME_TYPE
import co.electriccoin.zcash.work.DeleteDiagnosticExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.time.Clock
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
    private val biometricRepository: BiometricRepository,
) {
    internal var clock: Clock = Clock.systemUTC()

    internal var scheduleCleanup: (File) -> Unit = {
        DeleteDiagnosticExportWorker.scheduleTax(context, it).result.get()
    }

    suspend operator fun invoke(
        approvedAccount: WalletAccount,
        sourceEntryId: String? = null,
        isAllowed: () -> Boolean,
    ) {
        if (canHandOff(sourceEntryId, isAllowed) && requestApproval() && canHandOff(sourceEntryId, isAllowed)) {
            exportApprovedAccount(approvedAccount, sourceEntryId, isAllowed)
        }
    }

    private suspend fun requestApproval(): Boolean =
        try {
            biometricRepository.requestBiometrics(
                BiometricRequest(
                    message = stringRes(
                        R.string.authentication_system_ui_subtitle,
                        stringRes(R.string.authentication_use_case_tax_export),
                    ),
                ),
            )
            true
        } catch (_: BiometricsCancelledException) {
            false
        } catch (_: BiometricsFailureException) {
            false
        }

    private suspend fun exportApprovedAccount(
        approvedAccount: WalletAccount,
        sourceEntryId: String?,
        isAllowed: () -> Boolean,
    ) =
        withContext(Dispatchers.IO) {
            if (!isAllowed()) return@withContext
            val previousYear = Year.now(clock).minusYears(1).value

            val directory = File(context.cacheDir, DeleteDiagnosticExportWorker.TAX_DIRECTORY).apply { mkdirs() }
            val staging = Files.createTempDirectory(directory.toPath(), "tax-").toFile()
            val outputFile =
                File(
                    staging,
                    when (approvedAccount) {
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

            var shared = false
            try {
                val entries = getCsvEntries(approvedAccount.sdkAccount.accountUuid, previousYear)
                if (!isAllowed()) return@withContext
                scheduleCleanup(staging)
                currentCoroutineContext().ensureActive()
                if (!isAllowed()) return@withContext
                writeCsvToFile(outputFile = outputFile, data = entries)
                val intent =
                    FileShareUtil.newShareContentIntent(
                        context = context,
                        file = outputFile,
                        shareText = context.getString(R.string.taxExport_taxFile),
                        sharePickerText = context.getString(R.string.taxExport_taxFile),
                        versionInfo = versionInfoProvider(),
                        fileType = ZASHI_INTERNAL_DATA_MIME_TYPE
                    )

                withContext(Dispatchers.Main.immediate) handoff@{
                    if (!canHandOff(sourceEntryId, isAllowed)) return@handoff
                    check(accountDataSource.getSelectedAccount().sdkAccount.accountUuid ==
                        approvedAccount.sdkAccount.accountUuid)
                    if (!canHandOff(sourceEntryId, isAllowed) ||
                        !navigationRouter.isForegroundAtEntry(sourceEntryId)
                    ) return@handoff
                    currentCoroutineContext().ensureActive()
                    context.startActivity(intent)
                    shared = true
                    navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Back }
                }
            } finally {
                if (!shared) {
                    outputFile.delete()
                    staging.delete()
                }
            }

        }

    private fun canHandOff(sourceEntryId: String?, isAllowed: () -> Boolean) =
        navigationRouter.isAtEntry(sourceEntryId) && isAllowed()

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

    private suspend fun getCsvEntries(accountUuid: AccountUuid, previousYear: Int) =
        transactionRepository
            .getTransactions(accountUuid)
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
                        val fee = transaction.fee

                        val sentQuantity = transaction.amount - (fee ?: Zatoshi(0))

                        CsvEntry(
                            date = dateString,
                            receivedQuantity = "",
                            receivedCurrency = "",
                            sentQuantity = sentQuantity.toCsvQuantity(),
                            sentCurrency = CURRENCY_TICKER,
                            feeAmount = (fee ?: Zatoshi(0)).toCsvQuantity(),
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
