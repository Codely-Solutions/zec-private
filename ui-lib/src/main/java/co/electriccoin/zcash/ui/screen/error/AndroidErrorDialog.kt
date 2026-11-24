package co.electriccoin.zcash.ui.screen.error

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AndroidErrorDialog(route: ErrorDialog) {
    val useCase = koinInject<NavigateToErrorUseCase>()
    val args = useCase.argsFor(route)
    if (args == null) {
        UnavailableErrorView()
        return
    }
    val vm = koinViewModel<ErrorVM> { parametersOf(args, route) }
    val state by vm.state.collectAsStateWithLifecycle()
    DialogView(state)
}

@Serializable
data class ErrorDialog(val id: String) : ErrorRoute
