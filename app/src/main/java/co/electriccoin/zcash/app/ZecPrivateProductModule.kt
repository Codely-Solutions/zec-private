package co.electriccoin.zcash.app

import co.electriccoin.zcash.ui.common.datasource.NearSwapDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/** Shipping product selection. Shared UI does not choose a concrete swap provider. */
internal val zecPrivateProductModule =
    module {
        singleOf(::NearSwapDataSource) bind SwapDataSource::class
    }
