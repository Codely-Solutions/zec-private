package co.electriccoin.zcash.ui.screen.donation

internal data class DonationViewState(
    val amount: String = "",
    val busy: Boolean = false,
    val error: Boolean = false,
    val unsupported: Boolean = false,
    val canReview: Boolean = false,
)
