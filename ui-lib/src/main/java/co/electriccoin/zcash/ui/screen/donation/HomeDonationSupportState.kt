package co.electriccoin.zcash.ui.screen.donation

internal data class HomeDonationSupportState(
    val showInvitation: Boolean = false,
    val showIntroduction: Boolean = false,
    val onDonate: () -> Unit = {},
    val onLater: () -> Unit = {},
    val onNever: () -> Unit = {},
)

