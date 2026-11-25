package co.electriccoin.zcash.ui.screen.exchangerate.optin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.exchangerate.settings.ExchangeRatePreferenceScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExchangeRateOptInScreen(sourceEntryId: String) {
    val vm = koinViewModel<ExchangeRateOptInVM> { parametersOf(sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    ExchangeRatePreferenceScreen(state, vm::onBack, vm::retryRead) { content ->
        ExchangeRateOptInView(state = content)
    }
}

@Serializable
object ExchangeRateOptInArgs
