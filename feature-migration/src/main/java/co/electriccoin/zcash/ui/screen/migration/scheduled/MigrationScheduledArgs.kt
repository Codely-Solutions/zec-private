package co.electriccoin.zcash.ui.screen.migration.scheduled

import kotlinx.serialization.Serializable

@Serializable
data class MigrationScheduledArgs(val accountKeyId: String)
