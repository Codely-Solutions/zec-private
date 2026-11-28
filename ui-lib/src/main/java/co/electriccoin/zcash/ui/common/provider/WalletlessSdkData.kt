package co.electriccoin.zcash.ui.common.provider

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.model.ZcashNetwork
import com.zodl.slipstream.SlipstreamSynchronizer
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal suspend fun eraseInactiveSdkData(context: Context, network: ZcashNetwork) {
    // Slipstream guards the active wallet and acknowledges its data and submit-plan erasure.
    check(SlipstreamSynchronizer.erase(context, network)) { "Could not erase Slipstream wallet data" }
    Synchronizer.erase(context, network)
    val legacy = classicLegacyWalletDatabases(context, network)
    // The classic SDK can leave its old base file when a newer database was present at entry.
    if (legacy.any { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }) {
        Synchronizer.erase(context, network)
    }
    // The classic SDK probes for the base before deleting a database and its sidecars.
    // Under the reset shutdown gate, retire only regular orphan sidecars of these exact names.
    (classicWalletDatabases(context, network) + legacy)
        .filter { Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        .flatMap(File::sidecars)
        .map(File::toPath)
        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .forEach { Files.deleteIfExists(it) }
    check(classicWalletFiles(context, network).all { Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }) {
        "Could not confirm classic wallet data erasure"
    }
}

/** A missing app wallet record does not establish that the native wallet is absent. */
internal fun hasInactiveSdkWalletData(context: Context, network: ZcashNetwork): Boolean {
    val preferences = File(context.dataDir, "shared_prefs")
    val submitPlan = "com.zodl.slipstream.submit_plan_${network.id}_${ZcashSdk.DEFAULT_ALIAS}.xml"
    return (classicWalletFiles(context, network) + File(preferences, submitPlan) + File(preferences, "$submitPlan.bak"))
        .any { !Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
}

/**
 * A classic erase result only says whether any associated file was removed. Check the main
 * wallet paths from DatabaseCoordinator in SDK 6.0.0 after the SDK performs the deletion.
 */
private fun classicWalletFiles(context: Context, network: ZcashNetwork): List<File> =
    (classicWalletDatabases(context, network) + classicLegacyWalletDatabases(context, network))
        .flatMap { listOf(it) + it.sidecars() } +
        File(context.noBackupFilesDir, "co.electricoin.zcash/zcash_sdk_${network.networkName}_fs_cache")

private fun classicWalletDatabases(context: Context, network: ZcashNetwork): List<File> {
    val root = File(context.noBackupFilesDir, "co.electricoin.zcash")
    val prefix = "zcash_sdk_${network.networkName}"
    return listOf(
        File(root, "${prefix}_data.sqlite3"),
        File(root, "${prefix}_cache.sqlite3"),
    )
}

private fun classicLegacyWalletDatabases(context: Context, network: ZcashNetwork): List<File> {
    val legacyPrefix = "ZcashSdk_${network.networkName}"
    return listOf(
        context.getDatabasePath("${legacyPrefix}_Data.db"),
        context.getDatabasePath("${legacyPrefix}_Cache.db")
    )
}

private fun File.sidecars(): List<File> = listOf("-journal", "-wal", "-shm").map { File(path + it) }
