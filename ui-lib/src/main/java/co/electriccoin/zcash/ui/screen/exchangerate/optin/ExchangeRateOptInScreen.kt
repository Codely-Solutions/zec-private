package co.electriccoin.zcash.ui.screen.exchangerate.optin

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExchangeRateOptInScreen(sourceEntryId: String) {
    val vm = koinViewModel<ExchangeRateOptInVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { vm.onBack() }
    LceRenderer(state = state) { content ->
        ExchangeRateOptInView(state = content)
    }
}

@Serializable
object ExchangeRateOptInArgs
