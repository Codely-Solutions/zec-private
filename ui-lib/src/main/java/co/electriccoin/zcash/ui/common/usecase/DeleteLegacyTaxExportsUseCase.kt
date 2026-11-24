package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.work.DeleteDiagnosticExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class DeleteLegacyTaxExportsUseCase(private val context: Context) {
    suspend fun onStartup() {
        runCatchingRecoverable { invoke() }.onFailure { Twig.warn(it) { "Could not remove legacy tax exports" } }
    }

    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        val cache = context.cacheDir.toPath()
        val taxDirectory = File(context.cacheDir, DeleteDiagnosticExportWorker.TAX_DIRECTORY).toPath()
        for (directory in listOf(cache, taxDirectory)) {
            if (Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(directory) { path ->
                    LEGACY_CSV.matches(path.fileName.toString()) && Files.isRegularFile(path, NOFOLLOW_LINKS)
                }.use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    private companion object {
        val LEGACY_CSV = Regex("(Zodl|Keystone|Ledger|View_Only)_Transaction_History_[0-9]{4}\\.csv")
    }
}
