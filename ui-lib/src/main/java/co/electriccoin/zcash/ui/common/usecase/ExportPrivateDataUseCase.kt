package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.util.FileShareUtil
import co.electriccoin.zcash.work.DeleteDiagnosticExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ExportPrivateDataUseCase(
    private val context: Context,
    private val biometricRepository: BiometricRepository,
    private val appAccess: AppAccessSession,
    private val walletAccess: WalletWriteAccess,
    private val accountDataSource: AccountDataSource,
) {
    private val export = Mutex()

    internal var scheduleCleanup: (File) -> Unit = {
        DeleteDiagnosticExportWorker.schedule(context, it).result.get()
    }

    suspend operator fun invoke(
        isSourceCurrent: () -> Boolean = { true },
        isChooserAllowed: () -> Boolean = { true },
        sourceFile: suspend () -> File,
    ): Boolean {
        val owner = selectedSpendableAccount()
        if (owner == null || !export.tryLock()) return false
        return try {
            val grant = appAccess.grant.value
            val generation = walletAccess.token()
            val isAllowed = {
                isSourceCurrent() && appAccess.permits(grant) &&
                    walletAccess.permits(generation) && selectedSpendableAccount() == owner
            }
            isAllowed() && run {
                biometricRepository.requestBiometrics(
                    BiometricRequest(
                        message = stringRes(
                            R.string.authentication_system_ui_subtitle,
                            stringRes(R.string.authentication_use_case_export_data)
                        )
                    )
                )
                isAllowed() && shareApprovedExport(sourceFile, isAllowed, isChooserAllowed)
            }
        } catch (_: BiometricsFailureException) {
            false
        } catch (_: BiometricsCancelledException) {
            false
        } finally {
            export.unlock()
        }
    }

    private fun selectedSpendableAccount() = accountDataSource.allAccounts.value
        ?.firstOrNull { it.isSelected }
        ?.takeIf { it.isSpendable }
        ?.sdkAccount?.accountUuid

    private suspend fun shareApprovedExport(
        sourceFile: suspend () -> File,
        isAllowed: () -> Boolean,
        isChooserAllowed: () -> Boolean,
    ): Boolean {
        var staged: File? = null
        var shared = false
        try {
            val intent = withContext(Dispatchers.IO) {
                if (!isAllowed()) return@withContext null
                val source = sourceFile()
                if (!isAllowed()) return@withContext null
                val directory = File(context.cacheDir, DeleteDiagnosticExportWorker.DIRECTORY).apply { mkdirs() }
                val output = File(directory, "${UUID.randomUUID()}_${source.name}")
                staged = output
                scheduleCleanup(output)
                currentCoroutineContext().ensureActive()
                if (!isAllowed()) return@withContext null
                source.copyTo(output, overwrite = true)
                FileShareUtil.newShareContentIntent(
                    context = context,
                    file = output,
                    fileType = FileShareUtil.ZASHI_INTERNAL_DATA_MIME_TYPE,
                    sharePickerText = context.getString(R.string.export_data_export_data_chooser_title),
                    versionInfo = VersionInfo.new(context.applicationContext),
                )
            } ?: return false
            withContext(Dispatchers.Main.immediate) {
                currentCoroutineContext().ensureActive()
                if (isAllowed() && isChooserAllowed()) {
                    context.startActivity(intent)
                    shared = true
                }
            }
            return shared
        } finally {
            if (!shared) withContext(NonCancellable + Dispatchers.IO) { staged?.delete() }
        }
    }
}
