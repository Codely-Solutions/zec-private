package co.electriccoin.zcash.ui.screen.swap.detail

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.di.koinActivityViewModel
import co.electriccoin.zcash.ui.common.appbar.ZashiTopAppBarVM
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SwapDetailScreen(args: SwapDetailArgs, entryId: String) {
    val vm = koinViewModel<SwapDetailVM> { parametersOf(args) }
    val fundingInWallet by vm.fundingInWallet.collectAsStateWithLifecycle(initialValue = false)
    if (fundingInWallet && args.fundingTxId != null) {
        TransactionDetailScreen(TransactionDetailArgs(args.fundingTxId, args.accountKeyId), entryId)
    } else {
        TrackedSwapDetailScreen(vm)
    }
}

@Composable
private fun TrackedSwapDetailScreen(vm: SwapDetailVM) {
    val topAppBarViewModel = koinActivityViewModel<ZashiTopAppBarVM>()
    val appBarState by topAppBarViewModel.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state?.onBack() }
    state?.let { SwapDetailView(it, appBarState) }
}

@Serializable
data class SwapDetailArgs(
    val depositAddress: String,
    val accountKeyId: String? = null,
    val fundingTxId: String? = null,
)
