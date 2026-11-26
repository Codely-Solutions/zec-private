package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface SelectedAccountUUIDProvider {
    val uuid: Flow<AccountUuid?>

    val selectionChanges: Flow<Unit> get() = flowOf(Unit)

    suspend fun getUUID(): AccountUuid?

    suspend fun captureSelection(): SelectedAccountSelection

    fun isCurrent(selection: SelectedAccountSelection): Boolean

    suspend fun setUUID(uuid: AccountUuid)

    suspend fun clearUUID()

    suspend fun clearIfSelected(uuid: AccountUuid)
}

class SelectedAccountSelection internal constructor(val uuid: AccountUuid?, internal val version: Long)

class SelectedAccountUUIDProviderImpl(
    private val encryptedPreferenceProvider: EncryptedPreferenceProvider
) : SelectedAccountUUIDProvider {
    private val default = SelectedAccountUUIDPreferenceDefault()
    private val selectionWrites = Mutex()
    private val selectionVersion = MutableStateFlow(0L)
    override val selectionChanges = selectionVersion.map { Unit }

    override val uuid: Flow<AccountUuid?> =
        flow {
            emitAll(default.observe(encryptedPreferenceProvider()))
        }

    override suspend fun getUUID(): AccountUuid? = default.getValue(encryptedPreferenceProvider())

    override suspend fun captureSelection(): SelectedAccountSelection = selectionWrites.withLock {
        SelectedAccountSelection(getUUID(), selectionVersion.value)
    }

    override fun isCurrent(selection: SelectedAccountSelection): Boolean =
        selectionVersion.value == selection.version

    override suspend fun setUUID(uuid: AccountUuid) = selectionWrites.withLock {
        selectionVersion.update { it + 1 }
        default.putValue(encryptedPreferenceProvider(), uuid)
    }

    override suspend fun clearUUID() = selectionWrites.withLock {
        selectionVersion.update { it + 1 }
        default.putValue(encryptedPreferenceProvider(), null)
    }

    override suspend fun clearIfSelected(uuid: AccountUuid) = selectionWrites.withLock {
        val preferences = encryptedPreferenceProvider()
        if (default.getValue(preferences) == uuid) {
            selectionVersion.update { it + 1 }
            default.putValue(preferences, null)
        }
    }
}

private class SelectedAccountUUIDPreferenceDefault : PreferenceDefault<AccountUuid?> {
    override val key: PreferenceKey = PreferenceKey("selected_account_uuid")

    override suspend fun getValue(preferenceProvider: PreferenceProvider) =
        preferenceProvider.getString(key)?.let { encoded ->
            runCatchingRecoverable { AccountUuid.new(encoded.decode()) }.getOrElse {
                error("Could not read the selected account")
            }
        }

    override suspend fun putValue(
        preferenceProvider: PreferenceProvider,
        newValue: AccountUuid?
    ) = preferenceProvider.putStringWithRollback(key, newValue?.value?.encode())

    @OptIn(ExperimentalEncodingApi::class)
    private fun ByteArray.encode() = Base64.encode(this)

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.decode() = Base64.decode(this)
}
