package co.electriccoin.zcash.ui.screen.balances.spendable

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ModalBottomSheetState
import co.electriccoin.zcash.ui.design.util.ImageResource
import co.electriccoin.zcash.ui.design.util.StringResource

data class SpendableBalanceState(
    val title: StringResource,
    val message: StringResource,
    val rows: List<SpendableBalanceRowState>,
    val shieldButton: SpendableBalanceShieldButtonState?,
    val positive: ButtonState,
    override val onBack: () -> Unit,
) : ModalBottomSheetState

data class SpendableBalanceRowState(
    val title: StringResource,
    val icon: ImageResource,
    val value: StringResource,
    val info: StringResource? = null
)

data class SpendableBalanceShieldButtonState(
    val amount: Zatoshi,
    val onShieldClick: () -> Unit,
)
