package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.common.voting.VotingHomeHooks
import co.electriccoin.zcash.ui.common.voting.VotingHomeMessageSource
import co.electriccoin.zcash.ui.common.voting.VotingSettingsEntry
import kotlinx.coroutines.flow.flowOf
import org.koin.dsl.module

/** Keeps shared home lifecycle contracts inert without loading the excluded voting feature. */
val excludedVotingModule =
    module {
        single<VotingHomeHooks> {
            object : VotingHomeHooks {
                override suspend fun recoverPendingRouteIfNeeded(): Boolean = false

                override suspend fun resumePendingShareTracking() = Unit
            }
        }
        single<VotingHomeMessageSource> {
            object : VotingHomeMessageSource {
                override fun observeIsCoinholderPollingMessageVisible() = flowOf(false)
            }
        }
        single<VotingSettingsEntry> {
            object : VotingSettingsEntry {
                override val isEnabled = false

                override fun navigateToHowToVote() = Unit

                override fun navigateToCoinholderPolling() = Unit
            }
        }
    }
