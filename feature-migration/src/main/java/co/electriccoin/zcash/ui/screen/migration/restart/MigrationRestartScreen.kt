package co.electriccoin.zcash.ui.screen.migration.restart

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.screen.common.LceRenderer
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun MigrationRestartScreen(args: MigrationRestartArgs, sourceEntryId: String) {
    val vm = koinViewModel<MigrationRestartVM> { parametersOf(args, sourceEntryId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LceRenderer(state) { s ->
        BackHandler { s.onBack() }
        MigrationRestartView(s)
    }
}
