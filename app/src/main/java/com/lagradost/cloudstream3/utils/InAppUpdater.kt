package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.GitInfo.currentCommitHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    private const val GITHUB_USER_NAME = "recloudstream"
    private const val GITHUB_REPO = "cloudstream"
    private const val KYTHOUR_UPDATE_MANIFEST =
        "https://raw.githubusercontent.com/franciscoalro/cloudstream-kythour/master/app-update.json"

    // The published Kythour channel intentionally keeps this package for update compatibility.
    private val PRERELEASE_PACKAGE_NAME: String
        get() = BuildConfig.APPLICATION_ID
    private const val LOG_TAG = "InAppUpdater"

    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        @JsonProperty("type") @SerialName("type") val type: String,
        @JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    private enum class UpdateCheckFailure {
        NETWORK,
        INVALID_RESPONSE,
        SECURITY,
    }

    private data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
        val expectedApk: ExpectedApk? = null,
        val failure: UpdateCheckFailure? = null,
    )

    @Serializable
    private data class KythourUpdateManifest(
        @JsonProperty("versionCode") @SerialName("versionCode") val versionCode: Long,
        @JsonProperty("versionName") @SerialName("versionName") val versionName: String,
        @JsonProperty("apkUrl") @SerialName("apkUrl") val apkUrl: String,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String? = null,
        @JsonProperty("id") @SerialName("id") val id: String? = null,
        @JsonProperty("packageName") @SerialName("packageName") val packageName: String? = null,
        @JsonProperty("sha256") @SerialName("sha256") val sha256: String? = null,
        @JsonProperty("certificateSha256") @SerialName("certificateSha256") val certificateSha256: String? = null,
        @JsonProperty("size") @SerialName("size") val size: Long? = null,
    )

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        return try {
            // The Kythour channel is intentionally checked even in debug builds:
            // this fork is distributed as a separately installable debug package.
            if (!installPrerelease) getKythourUpdate() else getPreReleaseUpdate()
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
            Update(
                false,
                null,
                null,
                null,
                null,
                null,
                failure = when (e) {
                    is IOException -> UpdateCheckFailure.NETWORK
                    is IllegalArgumentException -> UpdateCheckFailure.SECURITY
                    else -> UpdateCheckFailure.INVALID_RESPONSE
                },
            )
        }
    }

    private suspend fun Activity.getKythourUpdate(): Update {
        val manifest = parseJson<KythourUpdateManifest>(app.get(KYTHOUR_UPDATE_MANIFEST).text)
        val currentVersionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        }
        val updateId = manifest.id ?: "kythour-${manifest.versionCode}"
        val expectedPackageName = this@Activity.packageName
        val packageName = requireNotNull(manifest.packageName) { "Missing update package name" }
        require(packageName == expectedPackageName) {
            "Update package does not match installed channel: $packageName"
        }
        val sha256 = requireNotNull(manifest.sha256) { "Missing update SHA-256" }
        val certificateSha256 = requireNotNull(manifest.certificateSha256) {
            "Missing update signing certificate"
        }
        return Update(
            shouldUpdate = manifest.versionCode > currentVersionCode && manifest.apkUrl.isNotBlank(),
            updateURL = manifest.apkUrl,
            updateVersion = manifest.versionName,
            changelog = manifest.changelog,
            updateNodeId = updateId,
            expectedApk = ExpectedApk(
                packageName = packageName,
                versionCode = manifest.versionCode,
                sha256 = sha256,
                certificateSha256 = certificateSha256,
                size = manifest.size,
            ),
        )
    }

    private suspend fun Activity.getReleaseUpdate(): Update {
        val url = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<Array<GithubRelease>>(
            app.get(url, headers = headers).text
        ).toList()

        val versionRegex = Regex("""(.*?((\d+)\.(\d+)\.(\d+))\.apk)""")
        val versionRegexLocal = Regex("""(.*?((\d+)\.(\d+)\.(\d+)).*)""")
        val foundList = response.filter { rel ->
            !rel.prerelease
        }.sortedWith(compareBy { release ->
            release.assets.firstOrNull { it.contentType == "application/vnd.android.package-archive" }?.name?.let { it1 ->
                versionRegex.find(it1)?.groupValues?.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                }
            }
        }).toList()

        val found = foundList.lastOrNull()
        val foundAsset = found?.assets?.getOrNull(0)
        val foundVersion = foundAsset?.name?.let { versionRegex.find(it) }

        if (foundVersion == null) {
            return Update(false, null, null, null, null, null)
        }

        val currentVersion = packageName?.let {
            packageManager.getPackageInfo(it, 0)
        }

        val shouldUpdate = if (foundAsset.browserDownloadUrl.isBlank()) {
            false
        } else {
            currentVersion?.versionName?.let { versionName ->
                versionRegexLocal.find(versionName)?.groupValues?.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                }
            }?.compareTo(
                foundVersion.groupValues.let {
                    it[3].toInt() * 100_000_000 + it[4].toInt() * 10_000 + it[5].toInt()
                })!! < 0
        }

        return Update(
            shouldUpdate,
            foundAsset.browserDownloadUrl,
            foundVersion.groupValues[2],
            found.body,
            found.nodeId,
            null,
        )
    }

    private suspend fun Activity.getPreReleaseUpdate(): Update {
        val tagUrl =
            "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/git/ref/tags/pre-release"
        val releaseUrl = "https://api.github.com/repos/$GITHUB_USER_NAME/$GITHUB_REPO/releases"
        val headers = mapOf("Accept" to "application/vnd.github.v3+json")
        val response = parseJson<Array<GithubRelease>>(
            app.get(releaseUrl, headers = headers).text
        ).toList()

        val found = response.lastOrNull { rel ->
            rel.prerelease || rel.tagName == "pre-release"
        }

        val foundAsset = found?.assets?.filter { it ->
            it.contentType == "application/vnd.android.package-archive"
        }?.getOrNull(0)

        if (foundAsset == null) {
            return Update(false, null, null, null, null, null)
        }

        val tagResponse = parseJson<GithubTag>(app.get(tagUrl, headers = headers).text)
        val updateCommitHash = tagResponse.githubObject.sha.trim().take(7)
        Log.d(LOG_TAG, "Fetched GitHub tag: $updateCommitHash")

        return Update(
            currentCommitHash() != updateCommitHash,
            foundAsset.browserDownloadUrl,
            updateCommitHash,
            found.body,
            found.nodeId,
            null,
        )
    }

    private val updateLock = Mutex()

    private suspend fun Activity.downloadUpdate(url: String, expectedApk: ExpectedApk): Boolean {
        try {
            ApkUpdateVerifier.requireAllowedUrl(url)
            Log.d(LOG_TAG, "Downloading verified update: $url")
            val appUpdateName = "CloudStream"
            val appUpdateSuffix = "apk"

            // Delete all old updates
            this.cacheDir.listFiles()?.filter {
                it.name.startsWith(appUpdateName) && it.extension == appUpdateSuffix
            }?.forEach { deleteFileOnExit(it) }

            val downloadedFile = File.createTempFile(appUpdateName, ".$appUpdateSuffix")
            val sink: BufferedSink = downloadedFile.sink().buffer()

            updateLock.withLock {
                sink.writeAll(app.get(url).body.source())
                sink.close()
                ApkUpdateVerifier.verify(this, downloadedFile, expectedApk)
                openApk(this, Uri.fromFile(downloadedFile))
            }

            return true
        } catch (e: Exception) {
            logError(e)
            return false
        }
    }

    private fun openApk(context: Context, uri: Uri) = safe {
        val path = uri.path ?: return@safe
        val contentUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".provider", File(path)
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            data = contentUri
        }
        context.startActivity(installIntent)
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        // The legacy prerelease installer must never target a different package.
        // Kythour updates are handled by the signed manifest channel below.
        if (packageName == BuildConfig.APPLICATION_ID) {
            Log.i(LOG_TAG, "Skipping legacy prerelease installer for Kythour channel")
            return@ioSafe
        }
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (!runAutoUpdate(checkAutoUpdate = false, installPrerelease = true)) {
            showToast(R.string.prerelease_install_failed)
        }
    }


    /**
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val isManualKythourCheck = !checkAutoUpdate && !installPrerelease
        if (isManualKythourCheck) {
            runOnUiThread { showToast(R.string.checking_for_update) }
        }

        val update = getAppUpdate(installPrerelease)
        if (update.failure != null) {
            // Never report "latest" when the server could not be reached or its signed
            // update metadata could not be parsed. Automatic checks remain silent.
            if (isManualKythourCheck) {
                val message = when (update.failure) {
                    UpdateCheckFailure.NETWORK -> R.string.update_check_network_error
                    UpdateCheckFailure.INVALID_RESPONSE -> R.string.update_check_invalid_response
                    UpdateCheckFailure.SECURITY -> R.string.update_check_security_error
                }
                runOnUiThread { showToast(message, Toast.LENGTH_LONG) }
            }
            return false
        }
        if (!update.shouldUpdate || update.updateURL == null) {
            // A manual check must always give feedback. Automatic checks stay silent so
            // opening the app does not produce an unnecessary notification or toast.
            if (isManualKythourCheck) {
                runOnUiThread { showToast(R.string.no_update_found) }
            }
            return false
        }
        if (!installPrerelease && update.expectedApk == null) {
            Log.e(LOG_TAG, "Kythour update manifest has no verification metadata")
            if (isManualKythourCheck) {
                runOnUiThread {
                    showToast(R.string.update_check_security_error, Toast.LENGTH_LONG)
                }
            }
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        // Automatic checks only notify the user. Downloading must always require an
        // explicit tap on Update, otherwise reopening the app restarts the download.
        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    getString(R.string.new_update_format).format(
                        currentVersion?.versionName, update.updateVersion
                    )
                )

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        // Forcefully start any delayed installations
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        val currentInstaller = settingsManager.getInt(
                            getString(R.string.apk_installer_key), 1
                        )

                        when (currentInstaller) {
                            // New method
                            0 -> {
                                val expected = update.expectedApk ?: return@setPositiveButton
                                val intent = PackageInstallerService.getIntent(
                                    this@runAutoUpdate, update.updateURL, expected
                                )
                                ContextCompat.startForegroundService(
                                    this@runAutoUpdate, intent
                                )
                            }
                            // Legacy
                            1 -> {
                                ioSafe {
                                    val expected = update.expectedApk ?: return@ioSafe
                                    if (!downloadUpdate(update.updateURL, expected)) {
                                        runOnUiThread {
                                            showToast(
                                                R.string.download_failed, Toast.LENGTH_LONG
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
