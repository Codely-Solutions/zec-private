package co.electriccoin.zcash.ui.common.provider

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.ZcashNetwork
import com.zodl.slipstream.SlipstreamSynchronizer
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal suspend fun eraseWalletlessSdkData(context: Context, network: ZcashNetwork) {
    // Slipstream guards the active wallet and acknowledges its data and submit-plan erasure.
    check(SlipstreamSynchronizer.erase(context, network)) { "Could not erase Slipstream wallet data" }
    Synchronizer.erase(context, network)
    requireClassicWalletFilesErased(context, network)
}

internal fun requireClassicWalletFilesErased(context: Context, network: ZcashNetwork) {
    check(classicWalletFiles(context, network).all { Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }) {
        "Could not confirm classic wallet data erasure"
    }
}

/**
 * A classic erase result only says whether any associated file was removed. Check the main
 * wallet paths from DatabaseCoordinator in SDK 6.0.0 after the SDK performs the deletion.
 */
private fun classicWalletFiles(context: Context, network: ZcashNetwork): List<File> {
    val root = File(context.noBackupFilesDir, "co.electricoin.zcash")
    val prefix = "zcash_sdk_${network.networkName}"
    val legacyPrefix = "ZcashSdk_${network.networkName}"
    val databases = listOf(
        File(root, "${prefix}_data.sqlite3"),
        File(root, "${prefix}_cache.sqlite3"),
        context.getDatabasePath("${legacyPrefix}_Data.db"),
        context.getDatabasePath("${legacyPrefix}_Cache.db")
    )
    return databases.flatMap { db -> listOf("", "-journal", "-wal", "-shm").map { File(db.path + it) } } +
        File(root, "${prefix}_fs_cache")
}
