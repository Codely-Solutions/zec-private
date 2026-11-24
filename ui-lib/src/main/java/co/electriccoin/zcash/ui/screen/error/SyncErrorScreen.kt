@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.error

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncErrorScreen(route: SyncErrorArgs) {
    val useCase = koinInject<NavigateToErrorUseCase>()
    val args = useCase.argsFor(route) as? ErrorArgs.SyncError
    if (args == null) {
        UnavailableErrorView()
        return
    }
    val vm = koinViewModel<SyncErrorVM> { parametersOf(args, route) }
    val state by vm.state.collectAsStateWithLifecycle()
    SyncErrorView(state = state)
}

@Serializable
data class SyncErrorArgs(val id: String) : ErrorRoute
