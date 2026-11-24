package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.work.DeleteDiagnosticExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class DeleteLegacySharedExportsUseCase(private val context: Context) {
    suspend fun onStartup() {
        runCatchingRecoverable { invoke() }.onFailure { error ->
            Twig.warn { "Could not remove legacy shared exports (${error.javaClass.simpleName})" }
        }
    }

    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        val cache = context.cacheDir.toPath()
        val taxDirectory = cache.resolve(DeleteDiagnosticExportWorker.TAX_DIRECTORY)
        for (directory in listOf(cache, taxDirectory)) {
            if (Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                Files.newDirectoryStream(directory) { path ->
                    LEGACY_CSV.matches(path.fileName.toString()) && Files.isRegularFile(path, NOFOLLOW_LINKS)
                }.use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            }
        }
        val pcztDirectory = cache.resolve(DeleteDiagnosticExportWorker.PCZT_DIRECTORY)
        val legacyPczt = pcztDirectory.resolve("pczt")
        if (Files.isDirectory(pcztDirectory, NOFOLLOW_LINKS) && Files.isRegularFile(legacyPczt, NOFOLLOW_LINKS)) {
            Files.deleteIfExists(legacyPczt)
        }
    }

    private companion object {
        val LEGACY_CSV = Regex("(Zodl|Keystone|Ledger|View_Only)_Transaction_History_[0-9]{4}\\.csv")
    }
}
