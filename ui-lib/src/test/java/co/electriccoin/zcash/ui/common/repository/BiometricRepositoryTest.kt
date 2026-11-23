package co.electriccoin.zcash.ui.common.repository

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.biometric.BiometricManager
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricRepositoryTest {
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun unsecuredDevicePolicyRequiresExplicitAbsenceAndIndependentConfirmation() {
        val absent = listOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        )
        absent.forEach { status ->
            assertTrue(permitsUnsecuredDeviceAccess(status, false))
            assertFalse(permitsUnsecuredDeviceAccess(status, true))
            assertFalse(permitsUnsecuredDeviceAccess(status, null))
        }
        listOf(
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            Int.MAX_VALUE
        ).forEach { status ->
            assertFalse(permitsUnsecuredDeviceAccess(status, false))
            assertFalse(permitsUnsecuredDeviceAccess(status, true))
            assertFalse(permitsUnsecuredDeviceAccess(status, null))
        }
    }

    @Test
    fun capabilityFailureDeniesProtectedOperationsOnSecuredDevices() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = mockk<Context>(relaxed = true)
        val keyguard = mockk<KeyguardManager>()
        every { context.getSystemService(Context.KEYGUARD_SERVICE) } returns keyguard
        every { keyguard.isDeviceSecure } returns true
        mockkStatic(Toast::class)
        val toast = mockk<Toast>(relaxed = true)
        every { Toast.makeText(context, any<Int>(), Toast.LENGTH_LONG) } returns toast
        val manager = mockk<BiometricManager>()
        val session = AppAccessSession()
        val repository = BiometricRepositoryImpl(context, manager, session)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        assertEquals(authenticators, repository.allowedAuthenticators)
        val request = BiometricRequest(message = stringRes("Synthetic authentication"))
        assertFailsWith<BiometricsCancelledException> { repository.requestBiometrics(request) }
        verify(exactly = 0) { manager.canAuthenticate(any()) }
        session.unlock()
        listOf(
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            Int.MAX_VALUE
        ).forEach { status ->
            every { manager.canAuthenticate(authenticators) } returns status
            assertFailsWith<BiometricsFailureException> { repository.requestBiometrics(request) }
        }
        every { keyguard.isDeviceSecure } returns false
        val absent = listOf(
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        )
        absent.forEach { status ->
            every { manager.canAuthenticate(authenticators) } returns status
            repository.requestBiometrics(request)
        }
        verify(exactly = 0) { context.startActivity(any()) }
        verify(exactly = 7) { toast.show() }
    }

    @Test
    fun callbackDuringLaunchCompletesTheRequestAndFirstTerminalResultWins() = runTest {
        val fixture = PromptFixture()
        every { fixture.context.startActivity(any()) } answers {
            val id = firstArg<Intent>().callbackId()
            fixture.repository.onBiometricResult(BiometricResult.Success(id))
            fixture.repository.onBiometricResult(BiometricResult.Failure(id))
        }

        withTimeout(1.seconds) { fixture.repository.requestBiometrics(fixture.request) }
    }

    @Test
    fun cancelledAndUnrelatedCallbacksCannotAuthorizeTheNextLaunch() = runTest {
        val fixture = PromptFixture()
        val first = async { fixture.repository.requestBiometrics(fixture.request) }
        runCurrent()
        val cancelled = fixture.launched.single()
        first.cancelAndJoin()

        val retry = async { withTimeout(1.seconds) { fixture.repository.requestBiometrics(fixture.request) } }
        runCurrent()
        val current = fixture.launched.last()
        assertNotEquals(cancelled, current)

        fixture.repository.onBiometricResult(BiometricResult.Success(cancelled))
        fixture.repository.onBiometricResult(BiometricResult.Success("unrelated"))
        runCurrent()
        assertFalse(retry.isCompleted)

        fixture.repository.onBiometricResult(BiometricResult.Success(current))
        retry.await()
    }

    @Test
    fun relockingRejectsTheOutstandingLaunchEvenIfItsSuccessArrivesAfterUnlock() = runTest {
        val fixture = PromptFixture()
        val result = async {
            withTimeout(1.seconds) {
                assertFailsWith<BiometricsCancelledException> {
                    fixture.repository.requestBiometrics(fixture.request)
                }
            }
        }
        runCurrent()
        val expired = fixture.launched.single()

        fixture.session.lock()
        fixture.session.unlock()
        fixture.repository.onBiometricResult(BiometricResult.Success(expired))

        result.await()
    }

    private class PromptFixture {
        val launched = mutableListOf<String>()
        val context = mockk<Context>(relaxed = true) {
            every { startActivity(any()) } answers { launched += firstArg<Intent>().callbackId() }
        }
        val session = AppAccessSession().apply { unlock() }
        val repository = BiometricRepositoryImpl(
            context,
            mockk { every { canAuthenticate(any()) } returns BiometricManager.BIOMETRIC_SUCCESS },
            session,
        )
        val request = BiometricRequest(message = stringRes("Synthetic authentication"))
    }

    private companion object {
        fun Intent.callbackId(): String = assertNotNull(getStringExtra("EXTRA_REQUEST_CODE"))
    }
}
