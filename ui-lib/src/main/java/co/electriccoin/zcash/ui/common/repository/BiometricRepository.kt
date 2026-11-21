package co.electriccoin.zcash.ui.common.repository

import android.app.KeyguardManager
import android.content.Context
import android.widget.Toast
import androidx.biometric.BiometricManager
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.ui.BiometricActivity
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

interface BiometricRepository {
    val allowedAuthenticators: Int

    fun onBiometricResult(result: BiometricResult)

    @Throws(BiometricsFailureException::class, BiometricsCancelledException::class)
    suspend fun requestBiometrics(request: BiometricRequest)
}

data class BiometricRequest(
    val message: StringResource,
    val requestCode: String = UUID.randomUUID().toString(),
)

sealed interface BiometricResult {
    val requestCode: String

    data class Success(
        override val requestCode: String
    ) : BiometricResult

    data class Failure(
        override val requestCode: String
    ) : BiometricResult

    data class Cancelled(
        override val requestCode: String
    ) : BiometricResult
}

class BiometricsFailureException : Exception()

class BiometricsCancelledException : Exception()

class BiometricRepositoryImpl(
    private val context: Context,
    private val biometricManager: BiometricManager,
    private val appAccessSession: AppAccessSession
) : BiometricRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val onBiometricsResult = MutableSharedFlow<BiometricResult>()

    override val allowedAuthenticators: Int
        get() =
            when {
                // Android SDK version == 27
                (AndroidApiVersion.isExactlyO) -> {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                }

                // Android SDK version >= 30
                (AndroidApiVersion.isAtLeastR) -> {
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                }

                // Android SDK version == 28 || 29
                (AndroidApiVersion.isExactlyP || AndroidApiVersion.isExactlyQ) -> {
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                }

                else -> {
                    error("Unsupported Android SDK version")
                }
            }

    override fun onBiometricResult(result: BiometricResult) {
        scope.launch {
            onBiometricsResult.emit(result)
        }
    }

    override suspend fun requestBiometrics(request: BiometricRequest) {
        val grant = appAccessSession.grant.value ?: throw BiometricsCancelledException()
        val capability = biometricManager.canAuthenticate(allowedAuthenticators)
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (permitsUnsecuredDeviceAccess(capability, keyguard?.isDeviceSecure)) {
            if (!appAccessSession.permits(grant)) throw BiometricsCancelledException()
            return
        }
        if (capability != BiometricManager.BIOMETRIC_SUCCESS) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.authentication_unavailable_remedy, Toast.LENGTH_LONG).show()
            }
            throw BiometricsFailureException()
        }

        context.startActivity(
            BiometricActivity.createIntent(
                context = context,
                requestCode = request.requestCode,
                subtitle = request.message.getString(context)
            )
        )
        when (
            onBiometricsResult.filter { it.requestCode == request.requestCode }.first()
        ) {
            is BiometricResult.Cancelled -> {
                throw BiometricsCancelledException()
            }

            is BiometricResult.Failure -> {
                throw BiometricsFailureException()
            }

            is BiometricResult.Success -> {
                if (!appAccessSession.permits(grant)) throw BiometricsCancelledException()
            }
        }
    }
}

/**
 * Devices without a configured screen lock retain the app's credential-free behavior. Only an explicit
 * absence of credentials/hardware and an independent keyguard confirmation permit it. Capability
 * errors, unknown security state and every failure on a secured device require a retry or device repair.
 */
internal fun permitsUnsecuredDeviceAccess(capability: Int, isDeviceSecure: Boolean?): Boolean =
    isDeviceSecure == false &&
        (capability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
            capability == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
