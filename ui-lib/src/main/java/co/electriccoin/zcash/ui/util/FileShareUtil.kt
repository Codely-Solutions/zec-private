package co.electriccoin.zcash.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.model.DistributionDimension
import co.electriccoin.zcash.ui.common.model.VersionInfo
import java.io.File

object FileShareUtil {
    const val SHARE_OUTSIDE_THE_APP_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK

    const val SHARE_CONTENT_PERMISSION_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION

    const val ZASHI_INTERNAL_DATA_MIME_TYPE = "application/octet-stream"
    const val ZASHI_QR_CODE_MIME_TYPE = "image/png"

    const val ZASHI_INTERNAL_DATA_AUTHORITY = "com.personal.zecprivate.provider"
    const val ZASHI_INTERNAL_DATA_AUTHORITY_DEBUG = "com.personal.zecprivate.debug.provider"
    const val ZASHI_INTERNAL_DATA_AUTHORITY_INTERNAL = "com.personal.zecprivate.internal.provider"
    const val ZASHI_INTERNAL_DATA_AUTHORITY_INTERNAL_DEBUG = "com.personal.zecprivate.internal.debug.provider"

    const val ZASHI_INTERNAL_DATA_FOSS_AUTHORITY = "com.personal.zecprivate.foss.provider"
    const val ZASHI_INTERNAL_DATA_FOSS_AUTHORITY_DEBUG = "com.personal.zecprivate.foss.debug.provider"

    const val ZASHI_INTERNAL_DATA_AUTHORITY_TESTNET = "com.personal.zecprivate.provider.testnet"
    const val ZASHI_INTERNAL_DATA_AUTHORITY_TESTNET_DEBUG = "com.personal.zecprivate.debug.provider.testnet"
    const val ZASHI_INTERNAL_DATA_AUTHORITY_TESTNET_INTERNAL = "com.personal.zecprivate.internal.provider.testnet"
    const val ZASHI_INTERNAL_DATA_AUTHORITY_TESTNET_INTERNAL_DEBUG =
        "com.personal.zecprivate.internal.debug.provider.testnet"

    const val ZASHI_INTERNAL_DATA_FOSS_AUTHORITY_TESTNET = "com.personal.zecprivate.foss.provider.testnet"
    const val ZASHI_INTERNAL_DATA_FOSS_AUTHORITY_TESTNET_DEBUG = "com.personal.zecprivate.foss.debug.provider.testnet"

    /**
     * Returns a new share internal app data intent with necessary permission granted exclusively to the data file.
     *
     * @param dataFilePath The private data file path we want to share
     *
     * @return Intent for launching an app for sharing
     */
    @Suppress("LongParameterList")
    internal fun newShareContentIntent(
        context: Context,
        dataFilePath: String,
        fileType: String,
        shareText: String? = null,
        sharePickerText: String,
        versionInfo: VersionInfo,
    ): Intent =
        newShareContentIntent(
            context = context,
            file = File(dataFilePath),
            shareText = shareText,
            sharePickerText = sharePickerText,
            versionInfo = versionInfo,
            fileType = fileType,
        )

    internal fun newShareContentIntent(
        context: Context,
        file: File,
        shareText: String? = null,
        sharePickerText: String,
        versionInfo: VersionInfo,
        fileType: String = ZASHI_INTERNAL_DATA_MIME_TYPE,
    ): Intent {
        val fileUri =
            FileProvider.getUriForFile(
                // context =
                context,
                // authority =
                getAuthorityByVersionInfo(versionInfo),
                // file =
                file
            )

        return newShareContentIntent(fileUri, shareText, sharePickerText, fileType)
    }

    internal fun newShareContentIntent(
        fileUri: Uri,
        shareText: String? = null,
        sharePickerText: String,
        fileType: String = ZASHI_INTERNAL_DATA_MIME_TYPE,
    ): Intent {
        val dataIntent: Intent =
            Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, fileUri)
                if (shareText != null) {
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                type = fileType
                clipData = ClipData.newRawUri("Shared file", fileUri)
                addFlags(SHARE_CONTENT_PERMISSION_FLAGS)
            }

        val shareDataIntent =
            Intent
                .createChooser(
                    dataIntent,
                    sharePickerText
                ).apply {
                    addFlags(
                        SHARE_CONTENT_PERMISSION_FLAGS or
                            SHARE_OUTSIDE_THE_APP_FLAGS
                    )
                }

        return shareDataIntent
    }

    private fun getAuthorityByVersionInfo(versionInfo: VersionInfo) =
        if (versionInfo.network == ZcashNetwork.Testnet) {
            when (versionInfo.distribution) {
                DistributionDimension.FOSS -> getFossTestnetAuthority(versionInfo)
                DistributionDimension.INTERNAL -> getInternalTestnetAuthority(versionInfo)
                DistributionDimension.STORE -> getStoreTestnetAuthority(versionInfo)
            }
        } else {
            when (versionInfo.distribution) {
                DistributionDimension.FOSS -> getFossMainnetAuthority(versionInfo)
                DistributionDimension.INTERNAL -> getInternalMainnetAuthority(versionInfo)
                DistributionDimension.STORE -> getStoreMainnetAuthority(versionInfo)
            }
        }

    private fun getStoreMainnetAuthority(versionInfo: VersionInfo) =
        if (versionInfo.isDebuggable) {
            ZASHI_INTERNAL_DATA_AUTHORITY_DEBUG
        } else {
            ZASHI_INTERNAL_DATA_AUTHORITY
        }

    private fun getFossMainnetAuthority(versionInfo: VersionInfo) =
        if (versionInfo.isDebuggable) {
            ZASHI_INTERNAL_DATA_FOSS_AUTHORITY_DEBUG
        } else {
            ZASHI_INTERNAL_DATA_FOSS_AUTHORITY
        }

    private fun getInternalMainnetAuthority(versionInfo: VersionInfo) =
        if (versionInfo.isDebuggable) {
            ZASHI_INTERNAL_DATA_AUTHORITY_INTERNAL_DEBUG
        } else {
            ZASHI_INTERNAL_DATA_AUTHORITY_INTERNAL
        }

    private fun getStoreTestnetAuthority(versionInfo: VersionInfo) =
        if (versionInfo.isDebuggable) {
            ZASHI_INTERNAL_DATA_AUTHORITY_TESTNET_DEBUG
        } else {
            ZASHI_INTERNAL_DATA_AUTHORITY_TESTNET
        }

    private fun getFossTestnetAuthority(versionInfo: VersionInfo) =
        if (versionInfo.isDebuggable) {
            ZASHI_INTERNAL_DATA_FOSS_AUTHORITY_TESTNET_DEBUG
        } else {
            ZASHI_INTERNAL_DATA_FOSS_AUTHORITY_TESTNET
        }

    private fun getInternalTestnetAuthority(versionInfo: VersionInfo) =
        if (versionInfo.isDebuggable) {
            ZASHI_INTERNAL_DATA_AUTHORITY_TESTNET_INTERNAL_DEBUG
        } else {
            ZASHI_INTERNAL_DATA_AUTHORITY_TESTNET_INTERNAL
        }
}
