package ndika.monitor.booster.model

import android.graphics.drawable.Drawable

/**
 * Step identification enum for the comprehensive Game Booster pipeline.
 */
enum class BoostStepType {
    IDLE,
    FSTRIM_LOGCAT,
    KERNEL_VM_TUNING,
    VENDOR_THROTTLER_DISABLE,
    APP_STANDBY_PURGE,
    THERMAL_OVERRIDE,
    POWER_PERFORMANCE,
    GPU_DEVFREQ_GOVERNOR,
    CPU_UCLAMP_SCHEDTUNE,
    SURFACEFLINGER_GRAPHICS,
    RESOLUTION_DOWNSCALE,
    GAME_MODE_API,
    AOT_DEX_COMPILE,
    CPU_PRIORITY_AFFINITY_OOM,
    TOUCH_RESPONSE_BOOST,
    NETWORK_WIFI_OPTIMIZATION,
    MEMORY_TRIM,
    DND_ACTIVATE,
    COMPLETE,
    ERROR
}

/**
 * Game Booster Profiles
 */
enum class BoostMode {
    STANDARD,
    EXTREME_BEAST
}

/**
 * Resolution Downscaling Presets for massive GPU render load reduction.
 */
enum class ResolutionDownscalePreset(val scale: Float, val displayName: String) {
    NATIVE(1.0f, "Native (100%)"),
    BALANCED_85(0.85f, "85% (Ultra-Smooth)"),
    SPEED_70(0.70f, "70% (Maximum FPS / High-Load)"),
    EXTREME_50(0.50f, "50% (Extreme Frame Boost)")
}

/**
 * Configuration options for the Game Booster optimization pipeline.
 */
data class GameBoostConfig(
    val targetGamePackage: String? = null,
    val targetGameName: String? = null,
    val mode: BoostMode = BoostMode.EXTREME_BEAST,
    val targetFps: Int = 120,
    val targetRefreshRate: Float = 120.0f,
    val downscalePreset: ResolutionDownscalePreset = ResolutionDownscalePreset.NATIVE,
    val downscaleRatio: Float? = null,
    val enableAotCompile: Boolean = true,
    val enableSecondaryDexCompile: Boolean = true,
    val enableThermalBypass: Boolean = true,
    val enablePowerHal: Boolean = true,
    val enableGraphicsTuning: Boolean = true,
    val enableGpuDevfreqGovernor: Boolean = true,
    val enableCpuUclampSchedTune: Boolean = true,
    val enableCpuPinning: Boolean = true,
    val enableOomShield: Boolean = true,
    val enableKernelVmTuning: Boolean = true,
    val enableNetworkLock: Boolean = true,
    val enableWifiLowLatency: Boolean = true,
    val enableTouchBoost: Boolean = true,
    val enableDnd: Boolean = true,
    val enableVirtualRamDisable: Boolean = true,
    val enableKillVendorThrottlers: Boolean = true,
    val enableBackgroundPurge: Boolean = true,
    val enableAppStandbyFreeze: Boolean = true,
    val enableStorageTrim: Boolean = true
)

/**
 * Represent an installed game or app for targeted optimization.
 */
data class GameAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val isGameCategory: Boolean = false
)

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
        val isThermalBypassed: Boolean,
        val isAotCompiled: Boolean,
        val isGraphicsTuned: Boolean,
        val isCpuPinned: Boolean,
        val isNetworkOptimized: Boolean,
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

    val VENDOR_THROTTLER_PACKAGES = setOf(
        "com.xiaomi.joyose",                   // Xiaomi / Poco Thermal Throttler & FPS Limiter
        "com.samsung.android.game.gos",        // Samsung Game Optimizing Service
        "com.sec.android.app.game.gametools",  // Samsung Game Tools Limiter
        "com.oplus.games",                     // Oppo / Realme Game Space throttler service
        "com.vivo.gamewatch"                   // Vivo Game Watcher
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
