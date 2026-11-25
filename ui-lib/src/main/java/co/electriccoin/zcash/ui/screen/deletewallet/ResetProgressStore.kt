package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class ResetStage {
    DELETE_LOCAL_FILES,
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
    private val sdkErased = MutableStateFlow(
        pending.value && runCatchingRecoverable { readStage() }.getOrNull() == ResetStage.CLEAR_PREFERENCES
    )
    private val sdkInputSuppressed = MutableStateFlow(pending.value)
    private val migrationInput = Mutex()

    val isPending = pending.asStateFlow()
    val isSdkInputSuppressed = sdkInputSuppressed.asStateFlow()

    internal fun suppressSdkInput() {
        check(pending.value) { "SDK input suppression requires a pending wallet reset" }
        sdkInputSuppressed.value = true
    }

    internal fun observeMigrationSyncBlock(observe: () -> Flow<Boolean>): Flow<Boolean> = flow {
        migrationInput.withLock {
            if (sdkInputSuppressed.value) emit(false) else emitAll(observe())
        }
    }

    internal suspend fun <T> withMigrationInputStopped(block: suspend () -> T): T =
        migrationInput.withLock {
            check(sdkInputSuppressed.value) { "Wait for SDK input suppression before erasing" }
            block()
        }

    suspend fun getStage(): ResetStage? =
        withContext(Dispatchers.IO) {
            if (sdkErased.value) ResetStage.CLEAR_PREFERENCES else readStage() ?: lastStage
        }

    suspend fun store(stage: ResetStage?) {
        if (stage == ResetStage.CLEAR_PREFERENCES) {
            sdkErased.value = true
            sdkInputSuppressed.value = true
        }
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
            if (stage == null) {
                sdkErased.value = false
                sdkInputSuppressed.value = false
            }
            pending.value = stage != null
        }
    }

    private fun readStage(): ResetStage? =
        if (Files.notExists(file.toPath())) null else ResetStage.valueOf(file.readText())
}
