package co.electriccoin.zcash.ui.screen.exchangerate.optin

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExchangeRateOptInScreen(sourceEntryId: String) {
    val vm = koinViewModel<ExchangeRateOptInVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { state?.onBack() }
    state?.let { ExchangeRateOptInView(state = it) }
}

@Serializable
object ExchangeRateOptInArgs
