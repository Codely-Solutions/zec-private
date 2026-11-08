package co.electriccoin.zcash.ui.screen.updates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Advisory HTTPS release metadata; this does not verify an APK signature. */
data class AppUpdateRelease(val tag: String, val versionCode: Int, val size: Long, val digest: String?) {
    val releasePage: String get() = "$REPOSITORY_URL/releases/tag/$tag"
    val downloadUrl: String get() = "$REPOSITORY_URL/releases/download/$tag/$APK_NAME"
}

internal const val REPOSITORY_URL = "https://github.com/Sammy-Lastre/zec-private"
internal const val APK_NAME = "zec-private-latest.apk"
internal const val UPDATE_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
private const val MAX_APK_BYTES = 150 * 1024 * 1024L

internal object UpdatePolicy {
    data class Availability(val release: AppUpdateRelease, val dismissed: Boolean)

    fun availability(release: AppUpdateRelease?, installedCode: Long, dismissedCode: Int): Availability? =
        release?.takeIf { it.versionCode > installedCode }?.let {
            Availability(it, it.versionCode == dismissedCode)
        }

    fun shouldCheck(now: Long, lastCheck: Long?, manual: Boolean): Boolean =
        manual || lastCheck == null || now < lastCheck || now - lastCheck >= UPDATE_INTERVAL_MILLIS

    fun notice(
        release: AppUpdateRelease?,
        installedCode: Long,
        dismissedCode: Int,
        manual: Boolean = false
    ): AppUpdateRelease? = release?.takeIf {
        it.versionCode > installedCode && (manual || it.versionCode != dismissedCode)
    }

    fun parse(body: String): AppUpdateRelease {
        require(body.length <= MAX_METADATA_CHARACTERS)
        val root = Json.parseToJsonElement(body).jsonObject
        require(
            listOf("draft", "prerelease").all {
                val value = root.getValue(it).jsonPrimitive
                !value.isString && !value.boolean
            }
        )
        val tag = root.string("tag_name")
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+-private\\.[0-9]+").matches(tag))
        val codes = root.string("body").lineSequence().filter { it.startsWith("Android versionCode:") }.toList()
        require(codes.size == 1 && Regex("Android versionCode: [0-9]+").matches(codes.single()))
        val code = codes.single().substringAfter(": ").toInt()
        require(code > 0)
        val assets = root.getValue("assets").jsonArray.map { it.jsonObject }.filter { it.string("name") == APK_NAME }
        require(assets.size == 1)
        val asset = assets.single()
        val sizeValue = asset.getValue("size").jsonPrimitive
        require(!sizeValue.isString)
        val size = sizeValue.long
        require(size in 1..MAX_APK_BYTES)
        val digestValue = asset["digest"]
        val digest = if (digestValue == null || digestValue == JsonNull) null else asset.string("digest")
        require(digest == null || Regex("sha256:[0-9a-fA-F]{64}").matches(digest))
        val release = AppUpdateRelease(tag, code, size, digest)
        require(asset.string("browser_download_url") == release.downloadUrl)
        return release
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.let {
        require(it.isString)
        it.content
    }

    private const val MAX_METADATA_CHARACTERS = 1024 * 1024
}
