package co.electriccoin.zcash.ui.screen.swap.quote

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Composable
fun SwapQuoteScreen() {
    val vm = koinViewModel<SwapQuoteVM>()
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state == null, onBack = vm::onBack)
    SwapQuoteView(state)
}

@Serializable
data object SwapQuoteArgs
