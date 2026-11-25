package co.electriccoin.zcash.di

import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.AccountDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateDataSource
import co.electriccoin.zcash.ui.common.datasource.ExchangeRateDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.FiatCurrencyDataSource
import co.electriccoin.zcash.ui.common.datasource.FiatCurrencyDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSource
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSource
import co.electriccoin.zcash.ui.common.datasource.MessageAvailabilityDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSourceImpl
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSourceImpl
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val dataSourceModule =
    module {
        single<AccountDataSource> {
            val wallet = get<PersistableWalletProvider>()
            AccountDataSourceImpl(
                synchronizerProvider = get(),
                selectedAccountUUIDProvider = get(),
                persistableWalletProvider = wallet,
                ledgerAccountBindingProvider = get(),
                viewOnlyContent = get(),
                context = get(),
                accountPreferenceReads = wallet.readGeneration,
                accountWalletAvailability = wallet.availabilityRead,
                walletTransport = get(),
            )
        }
        singleOf(::ZashiSpendingKeyDataSourceImpl) bind ZashiSpendingKeyDataSource::class
        singleOf(::ProposalDataSourceImpl) bind ProposalDataSource::class
        singleOf(::RestoreTimestampDataSourceImpl) bind RestoreTimestampDataSource::class
        singleOf(::MessageAvailabilityDataSourceImpl) bind MessageAvailabilityDataSource::class
        singleOf(::WalletSnapshotDataSourceImpl) bind WalletSnapshotDataSource::class
        singleOf(::ExchangeRateDataSourceImpl) bind ExchangeRateDataSource::class
        single<FiatCurrencyDataSource> { FiatCurrencyDataSourceImpl(cmcApiProvider = get()) }
        singleOf(::GiftCardDataSourceImpl) bind GiftCardDataSource::class
    }
