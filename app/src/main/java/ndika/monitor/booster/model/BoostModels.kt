package ndika.monitor.booster.model

import android.graphics.drawable.Drawable

enum class BoostStep {
    IDLE,
    CHECKING_SHIZUKU,
    EXECUTING_FSTRIM,
    PURGING_BACKGROUND_APPS,
    SETTING_PERFORMANCE_MODE,
    COMPLETED,
    RESTORED,
    ERROR
}

data class BoostProgress(
    val step: BoostStep,
    val message: String,
    val progressPercent: Int
)

data class GameAppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable
)

object TargetPackages {
    /**
     * Common third-party bloatware, social media, shopping, and heavy background apps
     * that are safe to force-stop before launching games.
     */
    val PURGE_CANDIDATES: Set<String> = hashSetOf(
        // Social Media & Video Streaming
        "com.zhiliaoapp.musically",       // TikTok Global
        "com.ss.android.ugc.trill",       // TikTok SEA
        "com.ss.android.ugc.aweme",       // TikTok / Douyin
        "com.instagram.android",          // Instagram
        "com.facebook.katana",            // Facebook
        "com.facebook.lite",              // Facebook Lite
        "com.facebook.orca",              // Messenger
        "com.twitter.android",            // Twitter / X
        "com.snapchat.android",           // Snapchat
        "com.pinterest",                  // Pinterest
        "tv.danmaku.bili",                // Bilibili
        "com.bilibili.app.in",            // Bilibili HD
        "com.google.android.youtube",     // YouTube
        "com.netflix.mediaclient",        // Netflix
        "com.spotify.music",              // Spotify
        "com.discord",                    // Discord
        "org.telegram.messenger",         // Telegram

        // E-Commerce & Delivery Apps
        "com.shopee.id",                  // Shopee ID
        "com.shopee.ph",                  // Shopee PH
        "com.shopee.my",                  // Shopee MY
        "com.shopee.sg",                  // Shopee SG
        "com.tokopedia.tkpd",             // Tokopedia
        "com.lazada.android",             // Lazada
        "com.bukalapak.android",          // Bukalapak
        "com.blibli.android",             // Blibli
        "com.gojek.app",                  // Gojek
        "com.grabtaxi.passenger"          // Grab
    )

    /**
     * Essential system components and critical communication packages
     * that must NEVER be terminated under any circumstances.
     */
    val CRITICAL_WHITELIST: Set<String> = hashSetOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.settings",
        "com.google.android.gms",
        "com.google.android.gsf",
        "moe.shizuku.privileged.api",
        "com.whatsapp",                   // Urgent notifications
        "com.whatsapp.w4b",
        "ndika.monitor"                   // Self package
    )
}
