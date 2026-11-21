package co.electriccoin.zcash.ui.screen.restore.tor

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.WalletInitializationConflictException
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.CheckboxState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedArgs
import co.electriccoin.zcash.ui.screen.restore.seed.RestoreSeedVM
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

class RestoreTorVM(
    private val args: RestoreTorArgs,
    private val seedEntry: RestoreSeedVM,
    private val navigationRouter: NavigationRouter,
    private val restoreWallet: RestoreWalletUseCase,
    private val errorStateMapper: ErrorMapperUseCase,
) : ViewModel() {
    private val isChecked = MutableStateFlow(false)
    private val restoreLce = mutableLce<Unit>()

    val state: StateFlow<LceState<RestoreTorState>> =
        combine(isChecked, restoreLce.state) { checked, restore ->
            createState(checked, restore.loading)
        }.withLce(restoreLce) {
            val message =
                if (it.cause is WalletInitializationConflictException) {
                    R.string.wallet_initialization_conflict
                } else {
                    R.string.wallet_initialization_failed
                }
            errorStateMapper.mapToState(it, message = stringRes(message))
        }
            .stateIn(this, LceState(content = createState(isChecked.value, false)))

    private fun createState(isChecked: Boolean, isLoading: Boolean): RestoreTorState =
        RestoreTorState(
            checkbox =
                CheckboxState(
                    title = stringRes(R.string.torSettingsSheet_title),
                    subtitle = stringRes(R.string.torSettingsSheet_desc),
                    isChecked = isChecked,
                    onClick = { if (!restoreLce.state.value.loading) this.isChecked.update { !it } }
                ),
            primary =
                ButtonState(
                    text = stringRes(R.string.root_existingWallet_restore),
                    isLoading = isLoading,
                    onClick = { onRestoreWalletClick(isChecked) },
                ),
            secondary =
                ButtonState(
                    text = stringRes(co.electriccoin.zcash.ui.design.R.string.general_cancel),
                    isEnabled = !isLoading,
                    onClick = ::onBack,
                ),
            onBack = ::onBack
        )

    private fun onBack() {
        if (!restoreLce.state.value.loading) navigationRouter.back()
    }

    private fun onRestoreWalletClick(isChecked: Boolean) {
        if (restoreLce.state.value.loading) return
        restoreLce.execute {
            val seed = seedEntry.restoreSession.resolve(args.sessionId)
            if (seed == null) {
                navigationRouter.backTo(RestoreSeedArgs::class)
            } else {
                restoreWallet(
                    seedPhrase = seed,
                    enableTor = isChecked,
                    birthday = BlockHeight.new(args.blockHeight)
                )
                seedEntry.completeRestore(args.sessionId)
            }
        }
    }
}
