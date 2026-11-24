package co.electriccoin.zcash.ui.common.usecase

import android.content.Context
import android.graphics.Bitmap
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.spackle.getInternalCacheDirSuspend
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.provider.GetVersionInfoProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.util.FileShareUtil
import co.electriccoin.zcash.work.DeleteDiagnosticExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

internal const val CACHE_SUBDIR = DeleteDiagnosticExportWorker.QR_DIRECTORY

class ShareImageUseCase(
    private val context: Context,
    private val versionInfoProvider: GetVersionInfoProvider,
    private val walletWriteAccess: WalletWriteAccess,
) {
    internal var scheduleCleanup: (File) -> Unit = {
        DeleteDiagnosticExportWorker.scheduleQr(context, it).result.get()
    }

    suspend operator fun invoke(
        shareImageBitmap: Bitmap,
        shareText: String? = null,
        sharePickerText: String,
        filePrefix: String = "",
        fileSuffix: String = "",
        isAllowed: () -> Boolean = { true }
    ): Boolean {
        val share: suspend () -> Boolean = {
            shareData(
                shareImageBitmap = shareImageBitmap,
                versionInfo = versionInfoProvider(),
                filePrefix = filePrefix,
                fileSuffix = fileSuffix,
                shareText = shareText,
                sharePickerText = sharePickerText,
                isAllowed = isAllowed,
            )
        }
        return if (filePrefix.startsWith(VK_QR_FILENAME_PREFIX)) {
            walletWriteAccess.write(walletWriteAccess.token(), share) == true
        } else {
            share()
        }
    }

    private suspend fun shareData(
        shareImageBitmap: Bitmap,
        shareText: String?,
        sharePickerText: String,
        versionInfo: VersionInfo,
        filePrefix: String,
        fileSuffix: String,
        isAllowed: () -> Boolean,
    ): Boolean {
        var staged: File? = null
        var launched = false
        try {
            return runCatchingRecoverable {
                val bitmapFile = withContext(Dispatchers.IO) {
                    require(filePrefix.length >= MIN_TEMP_FILE_PREFIX_LENGTH)
                    val cacheDir = context.getInternalCacheDirSuspend(CACHE_SUBDIR)
                    File.createTempFile(filePrefix, fileSuffix, cacheDir).also { file ->
                        staged = file
                        scheduleCleanup(file)
                        file.storeBitmap(shareImageBitmap)
                    }
                }

                val shareIntent =
                    FileShareUtil.newShareContentIntent(
                        context = context,
                        file = bitmapFile,
                        fileType = FileShareUtil.ZASHI_QR_CODE_MIME_TYPE,
                        shareText = shareText,
                        sharePickerText = sharePickerText,
                        versionInfo = versionInfo,
                    )

                withContext(Dispatchers.Main.immediate) {
                    if (isAllowed()) {
                        context.startActivity(shareIntent)
                        launched = true
                    }
                    launched
                }
            }.getOrElse { false }
        } finally {
            if (!launched) {
                staged?.let { file ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatchingRecoverable { Files.deleteIfExists(file.toPath()) }
                            .onFailure { Twig.warn(it) { "Could not remove an unshared QR image" } }
                    }
                }
            }
        }
    }

    private fun File.storeBitmap(bitmap: Bitmap) {
        outputStream().use { output ->
            @Suppress("MagicNumber")
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not encode the QR image"
            }
        }
    }

    private companion object {
        const val MIN_TEMP_FILE_PREFIX_LENGTH = 3
    }
}
