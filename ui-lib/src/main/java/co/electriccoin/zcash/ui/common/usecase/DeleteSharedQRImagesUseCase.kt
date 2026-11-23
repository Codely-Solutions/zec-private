package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

internal const val VK_QR_FILENAME_PREFIX = "zodl_viewing_key_qr_"

/**
 * Deletes the QR images [ShareImageUseCase] left behind for the receiving app. Runs on its own scope so a caller
 * can fire it from `ViewModel.onCleared`, where `viewModelScope` is already cancelled.
 */
class DeleteSharedQRImagesUseCase(
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    operator fun invoke(filePrefix: String) =
        scope.launch {
            runCatchingRecoverable { delete(filePrefix) }
                .onFailure { Twig.warn(it) { "Could not remove shared QR images" } }
        }

    suspend fun deleteViewingKeyImages() = delete(VK_QR_FILENAME_PREFIX)

    private suspend fun delete(filePrefix: String) = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, CACHE_SUBDIR)
        if (!Files.notExists(directory.toPath())) {
            val owned = checkNotNull(directory.listFiles { file ->
                file.name.startsWith(filePrefix) && file.extension == "png"
            }) { "Could not read the QR share cache" }
            owned.forEach { Files.deleteIfExists(it.toPath()) }
        }
    }
}
