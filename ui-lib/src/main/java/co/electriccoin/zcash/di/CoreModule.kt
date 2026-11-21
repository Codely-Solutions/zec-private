package co.electriccoin.zcash.di

import androidx.biometric.BiometricManager
import cash.z.ecc.android.sdk.WalletCoordinator
import co.electriccoin.zcash.configuration.AndroidConfigurationFactory
import co.electriccoin.zcash.global.invoke
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationRouterImpl
import co.electriccoin.zcash.ui.common.model.AppAccessSession
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
        factory { AndroidConfigurationFactory.new() }
        singleOf(::NavigationRouterImpl) bind NavigationRouter::class
    }
