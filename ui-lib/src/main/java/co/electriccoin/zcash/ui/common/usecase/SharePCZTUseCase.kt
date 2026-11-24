package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.util.FileShareUtil
import co.electriccoin.zcash.work.DeleteDiagnosticExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

class SharePCZTUseCase(
    private val context: Context,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val versionInfoProvider: GetVersionInfoProvider,
    private val appAccessSession: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
) {
    internal var scheduleCleanup: (File) -> Unit = {
        DeleteDiagnosticExportWorker.schedulePczt(context, it).result.get()
    }

    suspend operator fun invoke(isAllowed: () -> Boolean = { true }) =
        withContext(Dispatchers.Main.immediate) {
            val grant = appAccessSession.grant.value
            val generation = walletWriteAccess.token()
            val permitted = {
                appAccessSession.permits(grant) && walletWriteAccess.permits(generation) && isAllowed()
            }
            if (!permitted()) return@withContext
            val pczt = keystoneProposalRepository.getProposalPCZT() ?: return@withContext
            var staged: File? = null
            var shared = false
            try {
                runCatchingRecoverable {
                    val file = withContext(Dispatchers.IO) {
                        val cache = File(context.cacheDir, DeleteDiagnosticExportWorker.PCZT_DIRECTORY)
                        cache.mkdirs()
                        val directory = Files.createTempDirectory(cache.toPath(), "pczt-").toFile()
                        staged = directory
                        scheduleCleanup(directory)
                        currentCoroutineContext().ensureActive()
                        File(directory, "pczt").apply { writeBytes(pczt.toByteArray()) }
                    }
                    val intent = FileShareUtil.newShareContentIntent(
                        context = context,
                        file = file,
                        shareText = "PCZT",
                        sharePickerText = "PCZT",
                        versionInfo = versionInfoProvider(),
                    )
                    if (permitted()) {
                        currentCoroutineContext().ensureActive()
                        context.startActivity(intent)
                        shared = true
                    }
                }
            } finally {
                if (!shared) {
                    withContext(NonCancellable + Dispatchers.IO) { staged?.deleteRecursively() }
                }
            }
        }
}
