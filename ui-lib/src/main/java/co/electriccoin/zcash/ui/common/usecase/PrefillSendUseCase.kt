package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.UneditableZip321MemoException
import co.electriccoin.zcash.ui.common.model.zip321MemoTextOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.zecdev.zip321.model.PaymentRequest

class PrefillSendUseCase {
    private val bus = Channel<PrefillSendData>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    operator fun invoke() = bus.receiveAsFlow()

    private val lock = Any()
    private var lastRequest = 0L
    private val pending = mutableMapOf<Long, Job>()

    fun pendingThrough(): Long = synchronized(lock) { lastRequest }

    fun clear() = clearThrough(pendingThrough())

    fun clearThrough(lastOwnedRequest: Long) {
        val discarded = synchronized(lock) {
            pending.filterKeys { it <= lastOwnedRequest }.values.toList()
        }
        discarded.forEach { it.cancel() }
    }

    fun requestFromTransactionDetail(value: DetailedTransactionData) =
        request(
            PrefillSendData.All(
                amount = value.transaction.amount,
                address = value.recipient?.address,
                fee = value.transaction.fee,
                memos = value.memos
            )
        )

    fun requestFromZip321(value: PaymentRequest): Job {
        val memo = value.payments.firstOrNull()?.memo?.data?.let {
            it.zip321MemoTextOrNull() ?: throw UneditableZip321MemoException()
        }
        val payment = value.payments.firstOrNull()
        return request(
            PrefillSendData.All(
                amount =
                    payment
                        ?.nonNegativeAmount
                        ?.toZecValueString()
                        ?.toBigDecimal()
                        ?.convertZecToZatoshi() ?: Zatoshi(0),
                address = payment?.recipientAddress?.value,
                fee = null,
                memos = memo?.let { listOf(it) }
            )
        )
    }

    fun request(value: PrefillSendData): Job {
        val job = synchronized(lock) {
            val id = ++lastRequest
            scope.launch(start = CoroutineStart.LAZY) { bus.send(value) }.also { sender ->
                pending[id] = sender
                sender.invokeOnCompletion { synchronized(lock) { pending.remove(id) } }
            }
        }
        job.start()
        return job
    }
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
