package co.electriccoin.zcash.ui.screen.support.model

data class TorInfo(
    val isTorEnabled: Boolean?,
    val isAvailable: Boolean = true,
) {
    fun toSupportString() =
        buildString {
            val value = if (isAvailable) isTorEnabled.toSupportValue() else "Unavailable"
            appendLine("Tor enabled: $value")
        }
}

private fun Boolean?.toSupportValue() =
    when (this) {
        true -> "Yes"
        false -> "No"
        null -> "Not set"
    }
