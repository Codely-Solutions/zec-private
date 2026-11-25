package co.electriccoin.zcash.ui.screen.migration.progress

import kotlinx.serialization.Serializable

@Serializable
data class MigrationProgressArgs(val accountKeyId: String? = null, val offerRetry: Boolean = false)
