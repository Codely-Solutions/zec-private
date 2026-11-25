package co.electriccoin.zcash.ui.screen.taxexport

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AndroidTaxExport(sourceEntryId: String) {
    val viewModel = koinViewModel<TaxExportViewModel> { parametersOf(sourceEntryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    TaxExportView(
        state = state,
    )
}
