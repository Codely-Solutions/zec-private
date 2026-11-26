package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.NullableBooleanPreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface PersistableWalletProvider {
    val persistableWallet: Flow<PersistableWallet?>
    val availability: Flow<StoredWalletAvailability>
        get() = persistableWallet.map { wallet ->
            if (wallet == null) StoredWalletAvailability.ABSENT else StoredWalletAvailability.PRESENT
        }
    val availabilityRead: Flow<StoredWalletAvailabilityRead>
        get() = availability.map { StoredWalletAvailabilityRead(null, it) }
    val writeVersion: Long get() = 0L
    val readGeneration: StateFlow<Long> get() = initialWalletReadGeneration
    val sdkWalletReads: Flow<WalletSdkRead>
        get() = persistableWallet.map { WalletSdkRead(readGeneration.value, it) }

    fun retryRead() = Unit

    suspend fun store(persistableWallet: PersistableWallet)

    /** Writes only while the persisted snapshot and wallet generation match. */
    suspend fun storeIfCurrent(expected: PersistableWallet, replacement: PersistableWallet, version: Long): Boolean

    suspend fun getPersistableWallet(): PersistableWallet?

    suspend fun requirePersistableWallet(): PersistableWallet = checkNotNull(getPersistableWallet())

    suspend fun getSetupMode(): WalletInitMode? = null
}

private val initialWalletReadGeneration = MutableStateFlow(0L).asStateFlow()

data class WalletSdkRead(val generation: Long, val wallet: PersistableWallet?)

data class StoredWalletAvailabilityRead(val generation: Long?, val availability: StoredWalletAvailability)

enum class StoredWalletAvailability {
    CHECKING,
    PRESENT,
    ABSENT,
    UNAVAILABLE,
}

data class WalletServerSelection(val endpoint: LightWalletEndpoint, val isAutomatic: Boolean?)

interface WalletServerSelectionProvider {
    val serverSelection: Flow<WalletServerSelection?>
    val writeVersion: Long

    suspend fun getServerSelection(): WalletServerSelection?

    suspend fun storeServerSelection(
        selection: WalletServerSelection,
        version: Long,
        expected: WalletServerSelection? = null,
        onCommitted: () -> Unit = {},
    ): Boolean
}

class PersistableWalletProviderImpl(
    private val preferenceHolder: EncryptedPreferenceProvider,
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
    resetProgressStore: ResetProgressStore? = null,
    private val standardPreferences: StandardPreferenceProvider? = null,
) : PersistableWalletProvider, WalletServerSelectionProvider {
    private val persistableWalletStorageProvider = PersistableWalletStorageProviderImpl(preferenceHolder)
    override val writeVersion get() = walletWriteAccess.token()

    private val readsSuppressed = resetProgressStore?.isSdkInputSuppressed

    private val readRequests = MutableStateFlow(0L)
    override val readGeneration = readRequests.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val records = combine(readsSuppressed ?: flowOf(false), readRequests, ::Pair)
        .flatMapLatest { (suppressed, generation) ->
            persistableWalletStorageProvider.observeRecords(suppressed).map { generation to it }
        }
        .buffer(0)

    override val availabilityRead = combine(records, readRequests) { (generation, result), _ ->
        val availability = when {
            generation != readRequests.value -> StoredWalletAvailability.CHECKING
            result.isFailure -> StoredWalletAvailability.UNAVAILABLE
            result.getOrNull() != null -> StoredWalletAvailability.PRESENT
            else -> StoredWalletAvailability.ABSENT
        }
        StoredWalletAvailabilityRead(generation, availability)
    }.distinctUntilChanged()

    override val availability = availabilityRead.map { it.availability }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val sdkWalletReads = records.flatMapLatest { (generation, result) ->
        val record = result.getOrNull() ?: return@flatMapLatest flowOf(WalletSdkRead(generation, null))
        val wallet = record.wallet.withNormalizedSeed()
        record.sdkAdmission(standardPreferences).map { admitted ->
            WalletSdkRead(generation, wallet.takeIf { admitted })
        }
    }.buffer(0).distinctUntilChanged()

    override val persistableWallet = records.map { (_, result) ->
        result.getOrNull()?.wallet?.withNormalizedSeed()
    }.distinctUntilChanged()

    override val serverSelection =
        records.map { (_, result) -> result.getOrNull()?.serverSelection() }.distinctUntilChanged()

    override fun retryRead() {
        readRequests.update { it + 1 }
    }

    override suspend fun getServerSelection() = currentRecord()?.serverSelection()

    override suspend fun store(persistableWallet: PersistableWallet) {
        walletWriteAccess.replaceWallet {
            persistableWalletStorageProvider.store(
                WalletRecord(persistableWallet, setupMode = persistableWallet.walletInitMode.pendingMode())
            )
        }
    }

    override suspend fun storeIfCurrent(
        expected: PersistableWallet,
        replacement: PersistableWallet,
        version: Long
    ): Boolean {
        return walletWriteAccess.write(version) {
            val current = persistableWalletStorageProvider.get()
            if (current == null || current.wallet.walletInitMode != expected.walletInitMode ||
                !current.wallet.matchesPersistedSnapshot(expected)
            ) {
                false
            } else {
                persistableWalletStorageProvider.store(current.copy(wallet = replacement))
                true
            }
        } ?: false
    }

    override suspend fun storeServerSelection(
        selection: WalletServerSelection,
        version: Long,
        expected: WalletServerSelection?,
        onCommitted: () -> Unit,
    ): Boolean =
        walletWriteAccess.withSdkRecovery(version) {
            walletWriteAccess.write(version) {
                val current = persistableWalletStorageProvider.get()
                if (current == null || (expected != null && current.serverSelection() != expected)) {
                    false
                } else {
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        persistableWalletStorageProvider.store(
                            current.copy(
                                wallet = current.wallet.copy(endpoint = selection.endpoint),
                                isAutomatic = checkNotNull(selection.isAutomatic)
                            )
                        )
                        onCommitted()
                    }
                    true
                }
            } ?: false
        } ?: false

    internal suspend fun markProvisioned(
        expected: PersistableWallet,
        version: Long,
        stillUsesEngine: () -> Boolean,
        onCommitted: () -> Unit,
    ) {
        walletWriteAccess.withSdkRecovery(version) {
            walletWriteAccess.write(version) {
                val current = persistableWalletStorageProvider.get() ?: return@write
                if (stillUsesEngine() && current.wallet.walletInitMode == expected.walletInitMode &&
                    current.wallet.matchesPersistedSnapshot(expected)
                ) {
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        persistableWalletStorageProvider.store(
                            current.copy(wallet = current.wallet.copy(walletInitMode = WalletInitMode.ExistingWallet))
                        )
                        onCommitted()
                    }
                }
            }
        }
    }

    override suspend fun getPersistableWallet() = currentRecord()?.wallet

    override suspend fun getSetupMode() = currentRecord()?.setupMode

    private suspend fun currentRecord() =
        if (readsSuppressed?.value == true) null else persistableWalletStorageProvider.get()

    private suspend fun WalletRecord.serverSelection() =
        WalletServerSelection(
            endpoint = wallet.endpoint,
            isAutomatic = isAutomatic ?: LEGACY_SERVER_SELECTION_AUTOMATIC.getValue(preferenceHolder())
        )
}

private fun WalletRecord.sdkAdmission(standard: StandardPreferenceProvider?): Flow<Boolean> {
    val hasAcceptedSetup = setupMode != null || wallet.walletInitMode != WalletInitMode.ExistingWallet
    return if (hasAcceptedSetup || standard == null) {
        flowOf(true)
    } else {
        flow { emitAll(StandardPreferenceKeys.ONBOARDING_STATE.observe(standard())) }
            .recoverableReads()
            .map { it.getOrNull()?.let(OnboardingState::fromNumber) == OnboardingState.READY }
    }
}

private fun PersistableWallet.withNormalizedSeed(): PersistableWallet =
    copy(seedPhrase = seedPhrase.copy(split = seedPhrase.split.map { it.trim() }))

private fun PersistableWalletStorageProviderImpl.observeRecords(suppressed: Boolean): Flow<Result<WalletRecord?>> =
    if (suppressed) {
        flowOf(Result.success(null))
    } else {
        observe().map { Result.success(it) }.catch { failure ->
            if (failure !is Exception || failure is CancellationException) throw failure
            emit(Result.failure(failure))
        }
    }

private fun PersistableWallet.matchesPersistedSnapshot(expected: PersistableWallet): Boolean =
    network == expected.network && seedPhrase == expected.seedPhrase &&
        birthday == expected.birthday && endpoint == expected.endpoint

private val LEGACY_SERVER_SELECTION_AUTOMATIC = NullableBooleanPreferenceDefault(
    PreferenceKey("is_server_selection_automatic")
)

private const val SERVER_SELECTION_AUTOMATIC = "server_selection_automatic"
private const val APP_WALLET_PROVISIONING = "app_wallet_provisioning"
private const val APP_WALLET_SETUP = "app_wallet_setup"

private data class WalletRecord(
    val wallet: PersistableWallet,
    val isAutomatic: Boolean? = null,
    val setupMode: WalletInitMode? = null,
)

private fun WalletInitMode.pendingMode(): WalletInitMode? = takeUnless { it == WalletInitMode.ExistingWallet }

private fun JSONObject.readProvisioningMode(field: String): WalletInitMode? =
    when (opt(field)) {
        null -> null
        "new" -> WalletInitMode.NewWallet
        "restore" -> WalletInitMode.RestoreWallet
        else -> error("Unsupported wallet provisioning mode")
    }

private fun WalletInitMode?.provisioningValue(): String? =
    when (this) {
        WalletInitMode.NewWallet -> "new"
        WalletInitMode.RestoreWallet -> "restore"
        WalletInitMode.ExistingWallet, null -> null
    }

private class PersistableWalletStorageProviderImpl(
    override val preferenceHolder: EncryptedPreferenceProvider,
) : BaseNullableStorageProvider<WalletRecord>() {
    override val default = PersistableWalletPreferenceDefault(PreferenceKey("persistable_wallet"))
}

private class PersistableWalletPreferenceDefault(
    override val key: PreferenceKey
) : PreferenceDefault<WalletRecord?> {
    override suspend fun getValue(preferenceProvider: PreferenceProvider) =
        preferenceProvider.getString(key)?.let { serialized ->
            runCatchingRecoverable {
                val json = JSONObject(serialized)
                val mode = json.readProvisioningMode(APP_WALLET_PROVISIONING) ?: WalletInitMode.ExistingWallet
                WalletRecord(
                    PersistableWallet.from(json).copy(walletInitMode = mode),
                    json.opt(SERVER_SELECTION_AUTOMATIC) as? Boolean,
                    json.readProvisioningMode(APP_WALLET_SETUP)
                )
            }.getOrElse {
                // JSONObject and SDK parse errors can contain the original decrypted record.
                throw StoredWalletDecodeException()
            }
        }

    override suspend fun putValue(
        preferenceProvider: PreferenceProvider,
        newValue: WalletRecord?
    ) {
        val value = newValue?.let { record ->
            record.wallet.toJson().apply {
                put(SERVER_SELECTION_AUTOMATIC, record.isAutomatic)
                put(APP_WALLET_PROVISIONING, record.wallet.walletInitMode.provisioningValue())
                put(APP_WALLET_SETUP, record.setupMode.provisioningValue())
            }.toString()
        }
        preferenceProvider.putStringWithRollback(key, value)
    }
}

internal class StoredWalletDecodeException : IllegalStateException("Could not read the stored wallet record")
