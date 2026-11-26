package co.electriccoin.zcash.global

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.ui.common.provider.WalletSdkRead
import co.electriccoin.zcash.ui.common.provider.recoverableReads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Delivers a read-verified transport choice before admitting its wallet to the SDK. */
@Suppress("TooManyFunctions") // SDK input and app route admission share the same applied mode.
class WalletSdkTransportGate {
    private val transportRead = MutableStateFlow<WalletSdkTransportRead?>(null)
    private val withdrawal = MutableStateFlow<Withdrawal?>(null)
    private val withdrawn = MutableStateFlow<Withdrawal?>(null)
    private val restart = MutableStateFlow(false)
    private val seedRecovery = MutableStateFlow<Withdrawal?>(null)
    private val withdrawnForRecovery = MutableStateFlow<Withdrawal?>(null)
    private val requiredMode = MutableStateFlow<Boolean?>(null)
    private val admissionChanges = combine(requiredMode, restart, seedRecovery) { _, _, _ -> Unit }
    private val modeLock = Any()
    private val transportDelivery = Mutex()
    @Volatile private var applied: Boolean? = null
    @Volatile private var savingChoice = false
    @Volatile private var deliveredMode = false

    val readiness = transportRead.asStateFlow()
    val restartRequired = restart.asStateFlow()
    internal val inputSuspension = combine(restart, seedRecovery) { restarting, recovery ->
        restarting || recovery != null
    }
    internal val inputSuspended get() = restart.value || seedRecovery.value != null

    /** Admit a newly restored wallet only with its durably stored choice. */
    internal fun requireWalletMode(usesTor: Boolean) = synchronized(modeLock) {
        requiredMode.value = usesTor
    }

    internal fun recordCommittedMode(usesTor: Boolean) = synchronized(modeLock) {
        requiredMode.value = usesTor
        if (!matchesMode(applied, usesTor)) restart.value = true
    }

    internal fun checkTransportAvailable() {
        check(!inputSuspended && !savingChoice) { "Wallet transport is temporarily unavailable" }
    }

    internal suspend fun <T> whileSavingChoice(block: suspend () -> T): T {
        savingChoice = true
        return try {
            block()
        } finally {
            savingChoice = false
        }
    }

    /** Call under the wallet recovery fence. Leave persisted wallet and transport preferences intact. */
    internal suspend fun <T> whileRecoveringSeed(block: suspend () -> T): T {
        check(seedRecovery.compareAndSet(expect = null, update = Withdrawal()))
        return try {
            block()
        } finally {
            seedRecovery.value = null
        }
    }

    internal suspend fun awaitSeedRecoveryWithdrawal() {
        val request = checkNotNull(seedRecovery.value)
        withdrawnForRecovery.first { it === request }
    }

    internal fun requireDirectHttpAllowed() {
        val changing = savingChoice || inputSuspended
        if (changing || applied == true || requiredMode.value == true) {
            throw DirectHttpTransportUnavailable()
        }
    }

    internal fun torForRequest(stored: Boolean?): Boolean {
        checkTransportAvailable()
        val choice = stored == true
        check(matchesMode(applied, choice) && matchesMode(requiredMode.value, choice)) {
            "The Tor setting is changing"
        }
        return choice
    }

    internal fun walletDecisions(wallets: Flow<WalletSdkRead?>, generation: StateFlow<Long>): Flow<WalletSdkRead> =
        combine(wallets, generation, transportRead, withdrawal, admissionChanges) { read, _, _, _, _ ->
            val selected = generation.value
            val verified = transportRead.value == WalletSdkTransportRead(selected, verified = true)
            val wallet = read?.takeIf {
                it.generation == selected && verified && canAdmitWallet()
            }?.wallet
            WalletSdkRead(selected, wallet)
        }

    /** Only the coordinator's own wallet collector acknowledges delivery. */
    internal fun walletInput(decisions: Flow<WalletSdkRead>, generation: StateFlow<Long>): Flow<PersistableWallet?> =
        decisions.transform { decision ->
            val waiting = withdrawal.value
            val recovery = seedRecovery.value
            val wallet = synchronized(modeLock) {
                val current = decision.generation == generation.value &&
                    transportRead.value == WalletSdkTransportRead(decision.generation, verified = true)
                decision.wallet?.takeIf { current && canAdmitWallet() && matchesMode(applied, deliveredMode) }
                    ?.also { applied = deliveredMode }
            }
            emit(wallet)
            if (wallet == null && waiting != null) withdrawn.value = waiting
            if (wallet == null && recovery != null) withdrawnForRecovery.value = recovery
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun transportInput(preference: Flow<Boolean?>, generation: StateFlow<Long>): Flow<Boolean?> = flow {
        var delivered = false
        generation.flatMapLatest { selected ->
            preference.recoverableReads().map { Read(selected, it) }
        }.buffer(0).collect { read ->
            transportDelivery.withLock {
                if (read.generation != generation.value || restart.value) return@withLock
                if (read.preference.isFailure) {
                    transportRead.value = WalletSdkTransportRead(read.generation, verified = false)
                    return@withLock
                }
                val choice = read.preference.getOrThrow()
                val mode = choice == true
                val verified = WalletSdkTransportRead(read.generation, verified = true)
                if (!matchesMode(applied, mode)) {
                    // A preference listener can run before Android reports the write committed.
                    return@withLock
                }
                if (transportRead.value == verified && mode == deliveredMode) {
                    if (!delivered) {
                        emit(choice)
                        delivered = true
                    }
                    return@withLock
                }
                delivered = deliverChoice(choice, verified, generation) || delivered
            }
        }
    }

    private suspend fun FlowCollector<Boolean?>.deliverChoice(
        choice: Boolean?,
        read: WalletSdkTransportRead,
        generation: StateFlow<Long>,
    ): Boolean {
        // The SDK combines its inputs independently. Wait for this withdrawal, even
        // when the read generation has not changed.
        val request = Withdrawal()
        withdrawal.value = request
        return try {
            awaitWalletWithdrawal(request, read.generation, generation)
            val mode = choice == true
            if (read.generation != generation.value || restart.value || !matchesMode(applied, mode)) {
                false
            } else {
                emit(choice)
                if (read.generation == generation.value && !restart.value) {
                    deliveredMode = mode
                    transportRead.value = read
                }
                true
            }
        } finally {
            withdrawal.value = null
        }
    }

    private suspend fun awaitWalletWithdrawal(expected: Withdrawal, read: Long, generation: StateFlow<Long>) {
        combine(generation, withdrawn, restart) { current, actual, restartRequired ->
            current != read || actual === expected || restartRequired
        }.first { it }
    }

    private fun canAdmitWallet() =
        withdrawal.value == null && !inputSuspended && matchesMode(requiredMode.value, deliveredMode)

    private fun matchesMode(expected: Boolean?, actual: Boolean) = expected == null || expected == actual

    private class Withdrawal

    private data class Read(val generation: Long, val preference: Result<Boolean?>)
}

internal class DirectHttpTransportUnavailable : IOException("The wallet connection cannot send a direct request")

data class WalletSdkTransportRead(val generation: Long, val verified: Boolean)
