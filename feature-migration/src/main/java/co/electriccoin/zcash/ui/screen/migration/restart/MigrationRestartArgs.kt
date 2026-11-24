package co.electriccoin.zcash.ui.screen.migration.restart

import kotlinx.serialization.Serializable

@Serializable
data class MigrationRestartArgs(val accountKeyId: String? = null)
