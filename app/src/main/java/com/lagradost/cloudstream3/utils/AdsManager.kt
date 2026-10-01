package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.lagradost.cloudstream3.BuildConfig
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Banner-only ads. No startup, interstitial, rewarded, or player ads are used. */
object AdsManager {
    private const val BANNER_ID = "ca-app-pub-2587658864543168/6179958553"
    private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"

    @Volatile private var initialized = false
    @Volatile private var consentRequested = false
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingContainers = mutableListOf<WeakReference<FrameLayout>>()

    fun requestConsent(activity: Activity) {
        if (consentRequested) return
        consentRequested = true
        val information = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        information.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                if (error != null) Log.w("KAPlayAds", "Consent form unavailable: ${error.message}")
                finishConsent(activity.applicationContext, information)
            }
        }, { error ->
            Log.w("KAPlayAds", "Consent update failed: ${error.message}")
            // If the consent endpoint is temporarily unavailable, only request ads
            // when UMP says it is already permitted.
            finishConsent(activity.applicationContext, information)
        })
    }

    private fun finishConsent(context: Context, information: ConsentInformation) {
        if (information.canRequestAds()) {
            initialize(context)
            val containers = synchronized(pendingContainers) {
                val ready = pendingContainers.mapNotNull { it.get() }
                pendingContainers.clear()
                ready
            }
            containers.forEach { loadBanner(context, it) }
        }
    }

    private fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        // Keep SDK class loading and initialization off the Activity startup path.
        sdkScope.launch {
            try {
                MobileAds.initialize(context.applicationContext) {
                    Log.d("KAPlayAds", "Mobile Ads SDK initialized")
                }
            } catch (error: Throwable) {
                initialized = false
                Log.w("KAPlayAds", "Mobile Ads SDK initialization failed", error)
            }
        }
    }

    fun detachBanner(container: FrameLayout) {
        synchronized(pendingContainers) {
            pendingContainers.removeAll { it.get() == null || it.get() === container }
        }
        container.findAdViews().forEach { it.destroy() }
        container.removeAllViews()
        container.visibility = View.GONE
    }

    fun attachBanner(context: Context, container: FrameLayout) {
        val information = UserMessagingPlatform.getConsentInformation(context)
        if (!information.canRequestAds()) {
            synchronized(pendingContainers) {
                pendingContainers += WeakReference(container)
            }
            return
        }
        initialize(context)
        // AdView and loadAd must be created on the main thread.
        container.post { loadBanner(context, container) }
    }

    private fun FrameLayout.findAdViews(): List<AdView> =
        (0 until childCount).mapNotNull { getChildAt(it) as? AdView }

    private fun loadBanner(context: Context, container: FrameLayout) {
        if (container.visibility == View.VISIBLE && container.childCount > 0) return
        val adView = AdView(context).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, 360))
            adUnitId = if (BuildConfig.DEBUG) TEST_BANNER_ID else BANNER_ID
            adListener = object : com.google.android.gms.ads.AdListener() {
                override fun onAdLoaded() {
                    Log.i("KAPlayAds", "Banner loaded: $adUnitId")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w("KAPlayAds", "Banner failed: ${error.code} ${error.message}")
                    container.visibility = View.GONE
                }
            }
            loadAd(AdRequest.Builder().build())
        }
        container.removeAllViews()
        container.addView(adView, FrameLayout.LayoutParams(-1, -2))
        container.visibility = View.VISIBLE
    }
}
