package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceDefault
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface SelectedAccountUUIDProvider {
    val uuid: Flow<AccountUuid?>

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
    private val selectionVersion = AtomicLong()

    override val uuid: Flow<AccountUuid?> =
        flow {
            emitAll(default.observe(encryptedPreferenceProvider()))
        }

    override suspend fun getUUID(): AccountUuid? = default.getValue(encryptedPreferenceProvider())

    override suspend fun captureSelection(): SelectedAccountSelection = selectionWrites.withLock {
        SelectedAccountSelection(getUUID(), selectionVersion.get())
    }

    override fun isCurrent(selection: SelectedAccountSelection): Boolean =
        selectionVersion.get() == selection.version

    override suspend fun setUUID(uuid: AccountUuid) = selectionWrites.withLock {
        selectionVersion.incrementAndGet()
        default.putValue(encryptedPreferenceProvider(), uuid)
    }

    override suspend fun clearUUID() = selectionWrites.withLock {
        selectionVersion.incrementAndGet()
        default.putValue(encryptedPreferenceProvider(), null)
    }

    override suspend fun clearIfSelected(uuid: AccountUuid) = selectionWrites.withLock {
        val preferences = encryptedPreferenceProvider()
        if (default.getValue(preferences) == uuid) {
            selectionVersion.incrementAndGet()
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
