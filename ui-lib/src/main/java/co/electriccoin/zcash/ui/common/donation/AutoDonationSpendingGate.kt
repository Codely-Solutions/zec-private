package co.electriccoin.zcash.ui.common.donation

import kotlinx.coroutines.sync.Mutex

/** Serializes automatic spending, software transaction review and authorization changes. */
object AutoDonationSpendingGate {
    val mutex = Mutex()
}
