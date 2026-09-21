package ndika.monitor.booster.model

/**
 * Step identification enum for the Game Booster pipeline.
 */
enum class BoostStepType {
    IDLE,
    FSTRIM,
    APP_PURGE,
    FIXED_PERFORMANCE,
    COMPLETE,
    ERROR
}

/**
 * Status and progress data emitted during optimization.
 */
sealed class BoostProgress {
    object Idle : BoostProgress()

    data class InProgress(
        val step: BoostStepType,
        val progressPercentage: Int,
        val message: String
    ) : BoostProgress()

    data class Success(
        val isStorageTrimmed: Boolean,
        val purgedAppsCount: Int,
        val isPerformanceModeActive: Boolean,
        val freedRamMb: Long,
        val summaryLogs: List<String>
    ) : BoostProgress()

    data class Error(
        val errorMessage: String,
        val cause: Throwable? = null
    ) : BoostProgress()
}

/**
 * Default list of resource-heavy third party applications that are safe to force-stop before gaming.
 */
object BoostConstants {
    val DEFAULT_PURGEABLE_PACKAGES = setOf(
        // Social Media & Messaging bloat
        "com.zhiliaoapp.musically",       // TikTok Global
        "com.ss.android.ugc.trill",       // TikTok Asia
        "com.bytedance.tiktok",          // TikTok Regional
        "com.instagram.android",         // Instagram
        "com.facebook.katana",           // Facebook
        "com.facebook.orca",             // Facebook Messenger
        "com.facebook.lite",             // Facebook Lite
        "com.twitter.android",           // Twitter / X
        "com.snapchat.android",          // Snapchat
        "com.pinterest",                 // Pinterest
        "com.reddit.frontpage",          // Reddit
        "com.kwai.video",                // SnackVideo / Kwai
        "threads.net",                   // Threads by Instagram
        "com.vkontakte.android",         // VK

        // E-Commerce & Shopping (Heavy background sync & push services)
        "com.shopee.id",                 // Shopee ID
        "com.shopee.my",                 // Shopee MY
        "com.shopee.ph",                 // Shopee PH
        "com.shopee.th",                 // Shopee TH
        "com.tokopedia.tkpd",            // Tokopedia
        "com.lazada.android",            // Lazada
        "com.blibli.app",                // Blibli
        "com.bukalapak.android",         // Bukalapak
        "com.amazon.mShop.android.shopping", // Amazon Shopping
        "com.alibaba.aliexpresshd",      // AliExpress

        // Streaming & Media Players
        "com.spotify.music",             // Spotify
        "com.netflix.mediaclient",       // Netflix
        "com.disney.disneyplus",         // Disney+ Hotstar
        "com.viu.pad",                   // Viu
        "com.tencent.qqlivei18n",        // WeTV
        "com.bilibili.app.in"            // Bilibili
    )

    val ESSENTIAL_WHITELIST_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.telephony",
        "com.android.bluetooth",
        "com.android.nfc",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.play.games",
        "moe.shizuku.privileged.api",
        "rikka.shizuku",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.plus",
        "com.discord",
        "com.google.android.dialer",
        "com.google.android.apps.messaging"
    )
}
