package co.electriccoin.zcash.di

import androidx.biometric.BiometricManager
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.configuration.AndroidConfigurationFactory
import co.electriccoin.zcash.global.invoke
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationRouterImpl
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val coreModule =
    module {
        singleOf(WalletCoordinator::invoke)
        singleOf(::StandardPreferenceProvider)
        singleOf(::EncryptedPreferenceProvider)
        single { BiometricManager.from(get()) }
        singleOf(::AppAccessSession)
        singleOf(::LaunchNavigationRequests)
        singleOf(::KeystoneEnrollmentSession)
        factory { AndroidConfigurationFactory.new() }
        singleOf(::NavigationRouterImpl) bind NavigationRouter::class
    }
