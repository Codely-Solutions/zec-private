package co.electriccoin.zcash.app.reliability

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountPurpose
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.tool.DerivationTool
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.zodl.slipstream.SlipstreamSynchronizer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Public unfunded viewing vectors only. No target wallet, seed, signing, or app activity is opened. */
class ReliabilitySdkProbe {
    @Test
    @SmallTest
    fun shieldedOnlyKeyDescribesAndDerivesLocally() = runBlocking {
        sanitized("shielded_only_local") {
            describeAndDerive(NativePublicViewingKeys.MAINNET_WITHOUT_TRANSPARENT, sapling = true)
        }
    }

    @Test
    @SmallTest
    fun orchardOnlyKeyDescribesAndDerivesLocally() = runBlocking {
        sanitized("orchard_only_local") {
            describeAndDerive(NativePublicViewingKeys.MAINNET_ORCHARD_ONLY, sapling = false)
        }
    }

    @Test(timeout = 300_000)
    fun keylessBootstrapImportsAndReopensOverTor() = runBlocking {
        assumeTrue("Requires explicit Tor-only network probe opt-in", arguments.getString("runTorBootstrap") == "true")
        probe("keyless_bootstrap") { context, alias ->
            var synchronizer: CloseableSynchronizer? = null
            try {
                val active = stage("keyless_open") { open(context, alias) }
                synchronizer = active
                val initial = stage("empty_database") { active.getAccounts() }
                check(initial.isEmpty()) { "Fresh isolated database must contain no software account" }
                val imported = stage("public_ufvk_import") {
                    active.importAccountByUfvk(
                        setup(NativePublicViewingKeys.MAINNET_WITHOUT_TRANSPARENT, AccountPurpose.ViewOnly, 2_000_000)
                    )
                }
                check(stage("imported_accounts") { active.getAccounts().size } == 1)
                check(stage("imported_address") { active.getUnifiedAddress(imported).startsWith("u1") })
                active.close()
                synchronizer = null
                val reopened = stage("keyless_reopen") { open(context, alias) }
                synchronizer = reopened
                val restored = stage("persisted_account") { reopened.getAccounts() }
                check(restored.size == 1 && restored.single().accountUuid == imported.accountUuid)
                check(stage("persisted_address") { reopened.getUnifiedAddress(restored.single()).startsWith("u1") })
            } finally {
                synchronizer?.close()
            }
        }
    }

    @Test(timeout = 300_000)
    fun publicAccountsImportAtHistoricalBirthdaysAndReopenOverTor() = runBlocking {
        assumeTrue("Requires explicit Tor-only network probe opt-in", arguments.getString("runTorBootstrap") == "true")
        probe("shared_account_birthdays") { context, alias ->
            var synchronizer: CloseableSynchronizer? = null
            try {
                val active = stage("keyless_open") { open(context, alias) }
                synchronizer = active
                val first = stage("first_public_account") {
                    active.importAccountByUfvk(
                        setup(NativePublicViewingKeys.INDEPENDENT_MAINNET_FULL, AccountPurpose.ViewOnly, 2_100_000)
                    )
                }
                check(stage("distinct_public_key") {
                    active.findAccountSharingKeyComponents(UnifiedFullViewingKey(NativePublicViewingKeys.MAINNET_TRANSPARENT_AND_ORCHARD)) == null
                })
                val hardwarePurpose = AccountPurpose.Spending(seedFingerprint = ByteArray(32), zip32AccountIndex = Zip32AccountIndex.new(0))
                val second = stage("historical_hardware_purpose_import") {
                    active.importAccountByUfvk(
                        setup(NativePublicViewingKeys.MAINNET_TRANSPARENT_AND_ORCHARD, hardwarePurpose, 2_000_000)
                    )
                }
                check(first.accountUuid != second.accountUuid)
                check(stage("two_public_accounts") { active.getAccounts().size } == 2)
                active.close()
                synchronizer = null
                val reopened = stage("shared_keyless_reopen") { open(context, alias) }
                synchronizer = reopened
                val restored = stage("shared_persistence") { reopened.getAccounts() }
                check(restored.size == 2 && restored.map { it.accountUuid }.toSet() == setOf(first.accountUuid, second.accountUuid))
                restored.forEach { account ->
                    check(stage("shared_address") { reopened.getUnifiedAddress(account).startsWith("u1") })
                }
                // This proves SDK import/reopen metadata only; it does not claim a complete historical scan or device signing.
            } finally {
                synchronizer?.close()
            }
        }
    }

    private val arguments get() = InstrumentationRegistry.getArguments()

    private suspend fun describeAndDerive(key: String, sapling: Boolean) = withTimeout(30_000) {
        val tool = DerivationTool.getInstance()
        val components = checkNotNull(tool.describeUnifiedFullViewingKey(UnifiedFullViewingKey(key), ZcashNetwork.Mainnet))
        check(components.hasOrchard && !components.hasP2pkh && components.hasSapling == sapling)
        check(tool.deriveUnifiedAddress(viewingKey = key, network = ZcashNetwork.Mainnet).startsWith("u1"))
    }

    private fun setup(key: String, purpose: AccountPurpose, birthday: Long) = AccountImportSetup(
        accountName = "Public SDK probe",
        keySource = "reliability_public_fixture",
        ufvk = UnifiedFullViewingKey(key),
        purpose = purpose,
        birthday = BlockHeight.new(birthday)
    )

    private suspend fun open(context: Context, alias: String): CloseableSynchronizer = SlipstreamSynchronizer.new(
        alias = alias,
        birthday = BlockHeight.new(2_000_000),
        context = context,
        lightWalletEndpoint = LightWalletEndpoint(
            arguments.getString("lightwalletHost") ?: "zec.rocks",
            arguments.getString("lightwalletPort")?.toInt() ?: 443,
            true
        ),
        setup = null,
        walletInitMode = WalletInitMode.ExistingWallet,
        zcashNetwork = ZcashNetwork.Mainnet,
        isTorEnabled = true,
        isExchangeRateEnabled = false,
        engineMemoryFraction = 0.25f
    )

    private suspend fun probe(label: String, block: suspend (Context, String) -> Unit) = sanitized(label) {
        val base = InstrumentationRegistry.getInstrumentation().context
        val id = "reliability_" + UUID.randomUUID().toString().replace("-", "")
        val context = ProbeContext(base, id)
        try {
            withTimeout(210_000) { block(context, id) }
        } finally {
            withContext(NonCancellable) {
                withTimeout(60_000) {
                    // Public eraseAlias waits for the asynchronous close before removing this alias only.
                    stage("sdk_alias_cleanup") { SlipstreamSynchronizer.eraseAlias(context, ZcashNetwork.Mainnet, id) }
                    check(context.removeOwnedFiles()) { "Isolated probe cleanup failed" }
                }
            }
        }
    }

    private suspend fun <T> stage(label: String, block: suspend () -> T): T = try {
        withTimeout(90_000) { block() }
    } catch (failure: Throwable) {
        throw AssertionError("SDK probe stage $label failed (${failure.javaClass.simpleName}); details suppressed")
    }

    private suspend fun <T> sanitized(label: String, block: suspend () -> T): T = try {
        block()
    } catch (failure: AssertionError) {
        if (failure.message?.startsWith("SDK probe stage ") == true) throw failure
        throw AssertionError("SDK probe $label assertion failed; details suppressed")
    } catch (failure: Throwable) {
        throw AssertionError("SDK probe $label failed (${failure.javaClass.simpleName}); details suppressed")
    }
}

/** All SDK files and preferences belong to the instrumentation package and a unique disposable root. */
private class ProbeContext(base: Context, private val owner: String) : ContextWrapper(base) {
    private val root = File(base.cacheDir, owner).apply { check(mkdirs()) }
    private val preferences = mutableSetOf<String>()
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory("files")
    override fun getCacheDir(): File = directory("cache")
    override fun getNoBackupFilesDir(): File = directory("no_backup")
    override fun getDir(name: String, mode: Int): File = directory("app_$name")
    override fun getDatabasePath(name: String): File = File(directory("databases"), name)
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val ownedName = owner + "_" + name
        synchronized(preferences) { preferences.add(ownedName) }
        return baseContext.getSharedPreferences(ownedName, mode)
    }
    private fun directory(name: String): File = File(root, name).apply { mkdirs() }
    fun removeOwnedFiles(): Boolean {
        check(root.canonicalFile.parentFile == baseContext.cacheDir.canonicalFile)
        synchronized(preferences) { preferences.forEach { baseContext.deleteSharedPreferences(it) } }
        return root.deleteRecursively() && !root.exists()
    }
}
