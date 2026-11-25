@file:Suppress("ktlint:standard:filename")

package co.electriccoin.zcash.ui.screen.addressbook

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AddressBookScreen(sourceEntryId: String) {
    val viewModel = koinViewModel<AddressBookVM> { parametersOf(sourceEntryId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { state.onBack() }
    AddressBookView(state = state)
}

@Serializable
data object AddressBookArgs
