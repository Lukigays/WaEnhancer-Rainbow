package com.wmods.wppenhacer

import android.app.Activity
import com.wmods.wppenhacer.xposed.core.WppCore
import com.wmods.wppenhacer.xposed.core.components.AlertDialogWpp
import com.wmods.wppenhacer.xposed.utils.Utils
import com.wmods.wppenhacer.xposed.utils.YukiLog
import io.noties.markwon.Markwon
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * In-app update checker for this fork. It only follows THIS repository's
 * releases (Lukigays/WaEnhancer-Rainbow), never the upstream project.
 * Tags use the "v1.6.<N>" format produced by the auto-release workflow,
 * where N also matches the versionName and the bumped versionCode, so APK
 * updates install over previous builds.
 */
class UpdateChecker(private val mActivity: Activity) : Runnable {

    companion object {
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/Lukigays/WaEnhancer-Rainbow/releases/latest"
        private const val RELEASES_PAGE =
            "https://github.com/Lukigays/WaEnhancer-Rainbow/releases/latest"
        private val LAST_NUMBER_REGEX = Regex("([0-9]+)(?!.*[0-9])")
        private val APK_ASSET_REGEX = Regex(".*\\.apk$", RegexOption.IGNORE_CASE)

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        // Extracts the version number from any naming scheme:
        // "v1.6.7" / "1.6.7" -> 7, "1.6.6-build.5" -> 5, "1.6.6 (abc123)" -> 6.
        fun parseBuildNumber(text: String?): Int {
            return text?.let { LAST_NUMBER_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        }
    }

    override fun run() {
        try {
            val request = okhttp3.Request.Builder()
                .url(LATEST_RELEASE_API)
                .build()

            val tagName: String
            val changelog: String
            val publishedAt: String
            val apkUrl: String?

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return

                val content = response.body.string()
                val release = JSONObject(content)
                tagName = release.optString("tag_name", "")
                if (tagName.isBlank()) return

                changelog = release.optString("body", "No changelog available.").trim()
                publishedAt = release.optString("published_at", "")
                apkUrl = release.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length())
                        .mapNotNull { assets.optJSONObject(it) }
                        .firstOrNull { APK_ASSET_REGEX.matches(it.optString("name", "")) }
                        ?.optString("browser_download_url")
                }
            }

            val latestBuild = parseBuildNumber(tagName)
            if (latestBuild <= 0) return

            val packageInfo = try {
                mActivity.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0)
            } catch (e: Exception) {
                YukiLog.log(e)
                return
            }

            val currentBuild = parseBuildNumber(packageInfo.versionName)
            val isNewVersion = latestBuild > currentBuild
            val isIgnored = WppCore.getPrivString("ignored_version", "") == tagName

            if (isNewVersion && !isIgnored) {
                mActivity.runOnUiThread {
                    showUpdateDialog(tagName, changelog, publishedAt, apkUrl)
                }
            }
        } catch (e: Exception) {
            YukiLog.log(e)
        }
    }

    private fun showUpdateDialog(tag: String, changelog: String, publishedAt: String, apkUrl: String?) {
        try {
            val markwon = Markwon.create(mActivity)
            val dialog = AlertDialogWpp(mActivity)

            val formattedDate = formatPublishedDate(publishedAt)

            val message = buildString {
                append("📦 **Version:** `").append(tag).append("`\\n")
                if (formattedDate.isNotEmpty()) {
                    append("📅 **Released:** ").append(formattedDate).append("\\n")
                }
                append("\\n### What's New\\n\\n").append(changelog)
            }

            dialog.setTitle("🎉 New Update Available!")
            dialog.setMessage(markwon.toMarkdown(message))
            dialog.setNegativeButton("Ignore") { dialog, _ ->
                WppCore.setPrivString("ignored_version", tag)
                dialog.dismiss()
            }
            dialog.setPositiveButton("Update Now") { dialog, _ ->
                if (!apkUrl.isNullOrEmpty()) {
                    Utils.openLink(mActivity, apkUrl)
                } else {
                    Utils.openLink(mActivity, RELEASES_PAGE)
                }
                dialog.dismiss()
            }
            dialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatPublishedDate(isoDate: String?): String {
        if (isoDate.isNullOrEmpty()) return ""

        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val date = isoFormat.parse(isoDate)
            if (date != null) {
                val displayFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
                displayFormat.format(date)
            } else ""
        } catch (e: Exception) {
            YukiLog.log(e)
            ""
        }
    }
}
