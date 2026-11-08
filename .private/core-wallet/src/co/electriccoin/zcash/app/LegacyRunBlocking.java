package co.electriccoin.zcash.app;

import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

/** Test-only bridge to the public Java ABI retained by both coroutine runtimes. */
public final class LegacyRunBlocking {
    private LegacyRunBlocking() {}

    public static void run(CoreWalletStage stage) throws InterruptedException {
        BuildersKt.<Unit>runBlocking(
                EmptyCoroutineContext.INSTANCE,
                (scope, continuation) -> stage.run(scope, continuation));
    }
}
