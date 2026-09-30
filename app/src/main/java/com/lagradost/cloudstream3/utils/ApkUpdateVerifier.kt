package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** Metadata authenticated by the pinned application signing certificate after download. */
data class ExpectedApk(
    val packageName: String,
    val versionCode: Long,
    val sha256: String,
    val certificateSha256: String,
    val size: Long? = null,
)

object ApkUpdateVerifier {
    private val allowedHosts = setOf("github.com", "objects.githubusercontent.com")

    fun requireAllowedUrl(url: String) {
        val uri = java.net.URI(url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Update URL must use HTTPS" }
        require(uri.host?.lowercase() in allowedHosts) { "Untrusted update host" }
    }

    fun verify(context: Context, apk: File, expected: ExpectedApk) {
        require(expected.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid APK SHA-256" }
        require(expected.certificateSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "Invalid signing certificate SHA-256"
        }
        expected.size?.let { require(it > 0 && apk.length() == it) { "Unexpected APK size" } }

        val actualHash = apk.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().toHexString()
        }
        require(actualHash.equals(expected.sha256, ignoreCase = true)) { "APK SHA-256 mismatch" }

        val packageInfo = archivePackageInfo(context, apk)
            ?: throw SecurityException("Downloaded file is not a valid APK")
        require(packageInfo.packageName == expected.packageName) { "APK package name mismatch" }
        require(packageInfo.longVersionCodeCompat() == expected.versionCode) { "APK version mismatch" }

        val signerDigests = packageInfo.signerCertificates().map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate).toHexString()
        }
        require(signerDigests.any { it.equals(expected.certificateSha256, ignoreCase = true) }) {
            "APK signing certificate mismatch"
        }

        val installed = context.packageManager.getPackageInfoCompat(context.packageName)
        val installedDigests = installed.signerCertificates().map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate).toHexString()
        }
        require(installedDigests.any { it.equals(expected.certificateSha256, ignoreCase = true) }) {
            "Installed application uses an incompatible signing certificate"
        }
        require(expected.versionCode > installed.longVersionCodeCompat()) { "APK is not newer" }
    }

    @Suppress("DEPRECATION")
    private fun archivePackageInfo(context: Context, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerCertificates(): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = signingInfo ?: return emptyList()
            val signers = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            signers.orEmpty().map { it.toByteArray() }
        } else {
            signatures.orEmpty().map { it.toByteArray() }
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
