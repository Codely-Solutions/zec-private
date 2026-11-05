package co.electriccoin.zcash.ui.configuration

import co.electriccoin.zcash.configuration.model.entry.BooleanConfigurationEntry
import co.electriccoin.zcash.configuration.model.entry.ConfigKey
import co.electriccoin.zcash.configuration.model.entry.StringConfigurationEntry

object ConfigurationEntries {
    val VOTING_CONFIG_URL = StringConfigurationEntry(ConfigKey("voting_config_url"), "")
    val VOTING_SERVER_URL = StringConfigurationEntry(ConfigKey("voting_server_url"), "")
}
