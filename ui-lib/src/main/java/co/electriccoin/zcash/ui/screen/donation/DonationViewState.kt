package co.electriccoin.zcash.ui.screen.donation

internal data class DonationViewState(
    val automationEnabled: Boolean = false,
    val percent: String = "0.25",
    val amount: String = "",
    val busy: Boolean = false,
    val error: Boolean = false,
    val unsupported: Boolean = false,
    val canReview: Boolean = false,
    val canSaveAuto: Boolean = false,
    val automationSaved: Boolean = false,
    val autoOutcome: String? = null,
)

