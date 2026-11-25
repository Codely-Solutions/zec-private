package co.electriccoin.zcash.ui.screen.reviewtransaction

import kotlinx.serialization.Serializable

@Serializable
data class ReviewTransactionArgs(val reviewId: String? = null)
