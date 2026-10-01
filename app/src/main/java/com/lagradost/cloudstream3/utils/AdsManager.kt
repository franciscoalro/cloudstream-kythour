package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.lagradost.cloudstream3.BuildConfig
import java.lang.ref.WeakReference

/** Banner-only ads. No startup, interstitial, rewarded, or player ads are used. */
object AdsManager {
    private const val BANNER_ID = "ca-app-pub-2587658864543168/6179958553"
    private const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"

    @Volatile private var initialized = false
    @Volatile private var consentRequested = false
    private val pendingContainers = mutableListOf<WeakReference<FrameLayout>>()

    fun requestConsent(activity: Activity) {
        if (consentRequested) return
        consentRequested = true
        val information = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().build()
        information.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                finishConsent(activity.applicationContext, information)
            }
        }, { _ ->
            // If the consent endpoint is temporarily unavailable, only request ads
            // when UMP says it is already permitted.
            finishConsent(activity.applicationContext, information)
        })
    }

    private fun finishConsent(context: Context, information: ConsentInformation) {
        if (information.canRequestAds()) {
            initialize(context)
            synchronized(pendingContainers) {
                pendingContainers.mapNotNull { it.get() }.forEach { loadBanner(context, it) }
                pendingContainers.clear()
            }
        }
    }

    private fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        MobileAds.initialize(context)
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
        loadBanner(context, container)
    }

    private fun loadBanner(context: Context, container: FrameLayout) {
        if (container.visibility == View.VISIBLE && container.childCount > 0) return
        val adView = AdView(context).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, 360))
            adUnitId = if (BuildConfig.DEBUG) TEST_BANNER_ID else BANNER_ID
            loadAd(AdRequest.Builder().build())
        }
        container.removeAllViews()
        container.addView(adView, FrameLayout.LayoutParams(-1, -2))
        container.visibility = View.VISIBLE
    }
}
