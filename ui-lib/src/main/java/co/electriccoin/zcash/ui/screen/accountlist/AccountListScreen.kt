package co.electriccoin.zcash.ui.screen.accountlist

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.electriccoin.zcash.ui.design.component.AppAlertDialog
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AccountListScreen(sourceEntryId: String) {
    val viewModel = koinViewModel<AccountListVM> { parametersOf(sourceEntryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectionError by viewModel.selectionError.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    AccountListView(state)
    selectionError?.let { AppAlertDialog(it) }
}

@Serializable
object AccountListArgs
