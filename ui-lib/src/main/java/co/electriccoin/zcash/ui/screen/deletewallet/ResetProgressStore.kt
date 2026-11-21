package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class ResetStage {
    ERASE_SDK,
    CLEAR_PREFERENCES,
}

/**
 * Records authorized work before SDK erasure, then records its confirmed result before clearing
 * preferences. The marker lives outside the stores it protects and contains neither keys nor account IDs.
 */
class ResetProgressStore(context: Context, private val directorySync: DirectorySync) {
    private val file = File(context.noBackupFilesDir, "wallet-reset-progress")
    private val pending = MutableStateFlow(!Files.notExists(file.toPath()))

    private var lastStage: ResetStage? = null

    val isPending = pending.asStateFlow()

    suspend fun getStage(): ResetStage? =
        withContext(Dispatchers.IO) {
            if (Files.notExists(file.toPath())) lastStage else ResetStage.valueOf(file.readText())
        }

    suspend fun store(stage: ResetStage?) {
        withContext(Dispatchers.IO) {
            val directory = checkNotNull(file.parentFile)
            if (stage == null) {
                Files.deleteIfExists(file.toPath())
            } else {
                val temporary = File(directory, file.name + ".tmp")
                FileOutputStream(temporary).use { stream ->
                    stream.write(stage.name.encodeToByteArray())
                    stream.fd.sync()
                }
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
                lastStage = stage
                pending.value = true
            }
            directorySync.sync(directory)
            lastStage = stage
            pending.value = stage != null
        }
    }
}
