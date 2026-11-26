package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.UneditableZip321MemoException
import co.electriccoin.zcash.ui.common.model.zip321MemoTextOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull
import org.zecdev.zip321.model.PaymentRequest
import java.util.UUID

class PrefillSendUseCase {
    private val lock = Any()
    private var lastRequest = 0L
    private val pending = MutableStateFlow<Map<Target, Draft>>(emptyMap())

    internal fun observe(entryId: String, routeToken: String?) = pending.mapNotNull {
        synchronized(lock) {
            val entry = Target.Entry(entryId)
            val route = routeToken?.let { Target.Route(it) }
            val draft = (pending.value[entry] ?: route?.let { pending.value[it] }) ?: return@synchronized null
            // A new scan supersedes a pending initial draft for the same form.
            pending.value -= listOfNotNull(entry, route).toSet()
            draft.data.takeIf { draft.permits() }
        }
    }

    fun pendingThrough(): Long = synchronized(lock) { lastRequest }

    fun clear() = clearThrough(pendingThrough())

    fun clearThrough(lastOwnedRequest: Long) {
        synchronized(lock) {
            pending.value = pending.value.filterValues { it.id > lastOwnedRequest }
        }
    }

    internal fun requestFromTransactionDetail(value: DetailedTransactionData, permits: () -> Boolean) =
        forNewSend(
            PrefillSendData.All(
                amount = value.transaction.amount,
                address = value.recipient?.address,
                fee = value.transaction.fee,
                // The SDK exposes a memo list without the recipient of each output.
                memos = value.memos?.takeIf { value.transaction.transactionOutputs.size == 1 }
            ),
            permits,
        )

    internal fun forNewSend(data: PrefillSendData, permits: () -> Boolean): String {
        val token = UUID.randomUUID().toString()
        store(Target.Route(token), data, permits)
        return token
    }

    internal fun forExistingSend(entryId: String, data: PrefillSendData, permits: () -> Boolean) {
        store(Target.Entry(entryId), data, permits)
    }

    private fun store(target: Target, data: PrefillSendData, permits: () -> Boolean) {
        synchronized(lock) {
            if (!permits()) return
            val retained = pending.value.filterValues { it.permits() }.toMutableMap()
            retained.remove(target)
            if (retained.size >= MAX_PENDING_DRAFTS) retained.remove(retained.keys.first())
            retained[target] = Draft(++lastRequest, data, permits)
            pending.value = retained
        }
    }

    private data class Draft(val id: Long, val data: PrefillSendData, val permits: () -> Boolean)

    private sealed interface Target {
        data class Entry(val id: String) : Target
        data class Route(val token: String) : Target
    }

    private companion object {
        const val MAX_PENDING_DRAFTS = 8
    }
}

internal fun PaymentRequest.toEditableSendDraft(): PrefillSendData.All {
    val payment = payments.firstOrNull()
    val memo = payment?.memo?.data?.let { it.zip321MemoTextOrNull() ?: throw UneditableZip321MemoException() }
    return PrefillSendData.All(
        amount = payment?.nonNegativeAmount?.toZecValueString()?.toBigDecimal()?.convertZecToZatoshi() ?: Zatoshi(0),
        address = payment?.recipientAddress?.value,
        fee = null,
        memos = memo?.let(::listOf),
    )
}

sealed interface PrefillSendData {
    data class All(
        val amount: Zatoshi,
        val address: String?,
        val fee: Zatoshi?,
        val memos: List<String>?,
    ) : PrefillSendData

    data class FromAddressScan(
        val address: String
    ) : PrefillSendData
}
