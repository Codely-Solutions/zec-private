package co.electriccoin.zcash.ui.screen.connecthw.estimation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.EstimatedBlockHeightView
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import co.electriccoin.zcash.ui.screen.connecthw.EnrollmentFinalizationView
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun HWFirstTransactionEstimationScreen(args: HWEstimationArgs) {
    val vm = koinViewModel<HWEstimationVM> { parametersOf(args) }
    val state by vm.state.collectAsStateWithLifecycle()
    val finalization by vm.finalization.collectAsStateWithLifecycle()
    LceRenderer(state) {
        BackHandler { it.onBack() }
        val committed = finalization
        if (committed == null) {
            EstimatedBlockHeightView(it)
        } else {
            EnrollmentFinalizationView(committed, it.onBack)
        }
    }
}
