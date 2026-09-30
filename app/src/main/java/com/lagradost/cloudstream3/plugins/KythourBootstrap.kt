package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.util.Log
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

/**
 * Installs the Kythour repository and the extensions shipped inside the APK.
 *
 * Bundled extensions are only copied when absent or older than the bundled
 * version. This makes startup idempotent and prevents an APK update from
 * downgrading an extension that has already been updated from the repository.
 */
object KythourBootstrap {
    const val REPOSITORY_URL =
        "https://raw.githubusercontent.com/franciscoalro/kythourcl-dist/main/repo.json"
    private const val REPOSITORY_NAME = "Kythour Repository"
    private const val ASSET_MANIFEST = "kythour/plugins.json"
    private const val ASSET_DIRECTORY = "kythour/plugins"
    private const val TAG = "KythourBootstrap"

    suspend fun installBundledPlugins(activity: Activity) {
        try {
            RepositoryManager.addRepository(RepositoryData(REPOSITORY_NAME, REPOSITORY_URL))

            val manifest = activity.assets.open(ASSET_MANIFEST).bufferedReader().use {
                parseJson<Array<SitePlugin>>(it.readText())
            }

            manifest.sortedBy { it.internalName }.forEach { plugin ->
                val installed = PluginManager.installBundledOnlinePlugin(
                    context = activity,
                    repositoryUrl = REPOSITORY_URL,
                    plugin = plugin,
                    assetPath = "$ASSET_DIRECTORY/${plugin.internalName}.cs3"
                )
                Log.i(TAG, "Bundled extension ${plugin.internalName}: installed=$installed")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to preload Kythour extensions")
            logError(error)
        }
    }
}
