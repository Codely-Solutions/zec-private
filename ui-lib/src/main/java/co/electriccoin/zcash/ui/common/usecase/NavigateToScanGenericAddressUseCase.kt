package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.screen.scan.ScanGenericAddressArgs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

class NavigateToScanGenericAddressUseCase(
    private val navigationRouter: NavigationRouter
) {
    private val pipeline = MutableSharedFlow<ScanAddressPipelineResult>()
    private val ownedRequests = MutableStateFlow<Map<String, Channel<ScanAddressPipelineResult.Scanned>>>(emptyMap())

    internal fun observeOwnership(args: ScanGenericAddressArgs) = ownedRequests.map { args.requestId in it }
    internal fun owns(args: ScanGenericAddressArgs) = args.requestId in ownedRequests.value

    suspend operator fun invoke(): ScanResult? {
        val args = ScanGenericAddressArgs()
        val result = pipeline.onSubscription { navigationRouter.forward(args) }
            .first { it.args.requestId == args.requestId }
        return when (result) {
            is ScanAddressPipelineResult.Cancelled -> null
            is ScanAddressPipelineResult.Scanned -> ScanResult(
                address = result.address,
                amount = result.amount,
                rawInput = result.rawInput
            )
        }
    }

    internal suspend fun scanFrom(
        sourceEntryId: String,
        onAccepted: (ScanResult) -> NavigationCommand = { NavigationCommand.Back },
    ): ScanResult? {
        val args = ScanGenericAddressArgs()
        val scans = Channel<ScanAddressPipelineResult.Scanned>(capacity = 1)
        ownedRequests.update { it + (args.requestId to scans) }
        return try {
            val active = navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Forward(listOf(args)) }
            val scanner = navigationRouter.activeEntry.value
            if (active && scanner?.genericScanRequestId == args.requestId) {
                acceptFromScanner(scanner.id, scans, onAccepted)
            } else {
                null
            }
        } finally {
            ownedRequests.update { it - args.requestId }
            scans.close()
            scans.tryReceive().getOrNull()?.acknowledge?.complete(false)
        }
    }

    private suspend fun acceptFromScanner(
        sourceEntryId: String,
        scans: Channel<ScanAddressPipelineResult.Scanned>,
        onAccepted: (ScanResult) -> NavigationCommand,
    ): ScanResult? = coroutineScope {
        val left = async { navigationRouter.activeEntry.first { it?.id != sourceEntryId } }
        try {
            var accepted: ScanResult? = null
            while (accepted == null && navigationRouter.isAtEntry(sourceEntryId)) {
                val event = select<ScanAddressPipelineResult.Scanned?> {
                    scans.onReceive { it }
                    left.onAwait { null }
                } ?: break
                val result = ScanResult(event.address, event.amount, event.rawInput)
                val delivered = event.sourceEntryId == sourceEntryId &&
                    navigationRouter.commitFrom(sourceEntryId) { onAccepted(result) }
                event.acknowledge?.complete(delivered)
                accepted = result.takeIf { delivered }
            }
            accepted
        } finally {
            left.cancel()
        }
    }

    suspend fun onScanCancelled(args: ScanGenericAddressArgs, sourceEntryId: String? = null) {
        if (sourceEntryId != null) {
            navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Back }
            return
        }
        pipeline.emit(ScanAddressPipelineResult.Cancelled(args))
        navigationRouter.back()
    }

    /**
     * Resolves the pending scan as cancelled without navigating, for a scan that opened a different flow instead
     * (e.g. a gift card link), which takes care of navigation itself.
     */
    suspend fun onScanRedirected(args: ScanGenericAddressArgs) {
        pipeline.emit(ScanAddressPipelineResult.Cancelled(args))
    }

    suspend fun onScanned(
        address: String,
        amount: BigDecimal?,
        args: ScanGenericAddressArgs,
        rawInput: String? = null,
        sourceEntryId: String? = null,
    ): Boolean {
        if (sourceEntryId == null) {
            pipeline.emit(ScanAddressPipelineResult.Scanned(address, amount, args, rawInput))
            return true
        }
        val request = ownedRequests.value[args.requestId]
        val acknowledged = CompletableDeferred<Boolean>()
        val scanned = ScanAddressPipelineResult.Scanned(address, amount, args, rawInput, sourceEntryId, acknowledged)
        return navigationRouter.isAtEntry(sourceEntryId) && request?.trySend(scanned)?.isSuccess == true &&
            withTimeoutOrNull(3.seconds) { acknowledged.await() } == true
    }
}

private sealed interface ScanAddressPipelineResult {
    val args: ScanGenericAddressArgs

    data class Cancelled(
        override val args: ScanGenericAddressArgs
    ) : ScanAddressPipelineResult

    data class Scanned(
        val address: String,
        val amount: BigDecimal?,
        override val args: ScanGenericAddressArgs,
        val rawInput: String? = null,
        val sourceEntryId: String? = null,
        val acknowledge: CompletableDeferred<Boolean>? = null,
    ) : ScanAddressPipelineResult
}

data class ScanResult(
    val address: String,
    val amount: BigDecimal?,
    val rawInput: String? = null
)
