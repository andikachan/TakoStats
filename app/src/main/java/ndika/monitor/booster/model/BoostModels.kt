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
    DEVICE_SPOOFING,
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
 * Categories for Device Spoofing
 */
enum class DeviceProfileType {
    GAMING_FLAGSHIP, // Unlock 90/120 FPS & Ultra Graphics
    POTATO_LEGACY,   // Potato / Low-End resolution & assets for max FPS on modern phones
    NONE             // Default original device identity
}

/**
 * Device Model Spoofing Presets
 */
enum class DeviceSpoofPreset(
    val id: String,
    val displayName: String,
    val category: DeviceProfileType,
    val model: String,
    val manufacturer: String,
    val brand: String,
    val device: String,
    val recommendedDownscale: Float? = null,
    val targetFps: Int = 120,
    val badge: String,
    val description: String
) {
    NONE(
        id = "none",
        displayName = "Original Device (No Spoof)",
        category = DeviceProfileType.NONE,
        model = "",
        manufacturer = "",
        brand = "",
        device = "",
        recommendedDownscale = null,
        targetFps = 120,
        badge = "DEFAULT",
        description = "Use original device model and properties."
    ),

    // Gaming Flagships (Unlock 90/120 FPS & Ultra Graphics)
    ROG_PHONE_8_PRO(
        id = "rog_8_pro",
        displayName = "ASUS ROG Phone 8 Pro",
        category = DeviceProfileType.GAMING_FLAGSHIP,
        model = "ASUS_AI2401",
        manufacturer = "asus",
        brand = "asus",
        device = "ASUS_AI2401",
        recommendedDownscale = null,
        targetFps = 120,
        badge = "120 FPS UNLOCK",
        description = "Unlocks 90/120 FPS in MLBB, PUBG, CODM & Wild Rift."
    ),
    REDMAGIC_9_PRO(
        id = "redmagic_9_pro",
        displayName = "Nubia RedMagic 9 Pro",
        category = DeviceProfileType.GAMING_FLAGSHIP,
        model = "NX769J",
        manufacturer = "nubia",
        brand = "nubia",
        device = "NX769J",
        recommendedDownscale = null,
        targetFps = 144,
        badge = "144 FPS UNLOCK",
        description = "Unlocks 120/144 FPS & Ultra graphics modes."
    ),
    BLACK_SHARK_5_PRO(
        id = "black_shark_5_pro",
        displayName = "Xiaomi Black Shark 5 Pro",
        category = DeviceProfileType.GAMING_FLAGSHIP,
        model = "SHARK KTUS-H0",
        manufacturer = "blackshark",
        brand = "blackshark",
        device = "ktus",
        recommendedDownscale = null,
        targetFps = 120,
        badge = "GAME TURBO",
        description = "Unlocks 120 FPS whitelist on Xiaomi/MIUI game databases."
    ),
    GALAXY_S24_ULTRA(
        id = "galaxy_s24_ultra",
        displayName = "Samsung Galaxy S24 Ultra",
        category = DeviceProfileType.GAMING_FLAGSHIP,
        model = "SM-S928B",
        manufacturer = "samsung",
        brand = "samsung",
        device = "e3q",
        recommendedDownscale = null,
        targetFps = 120,
        badge = "RAY TRACING",
        description = "Unlocks Ray Tracing & Ultra HDR mode in supported games."
    ),

    // Potato & Legacy Phones (Low-Res Rendering for Maximum FPS on Modern Phones)
    REDMI_4A_POTATO(
        id = "redmi_4a_potato",
        displayName = "Xiaomi Redmi 4A (Potato 720p)",
        category = DeviceProfileType.POTATO_LEGACY,
        model = "Redmi 4A",
        manufacturer = "Xiaomi",
        brand = "Xiaomi",
        device = "rolex",
        recommendedDownscale = 0.65f,
        targetFps = 60,
        badge = "POTATO 720P",
        description = "Forces games to load low-poly textures & 720p render scale for ultra-smooth gameplay."
    ),
    GALAXY_J2_PRIME(
        id = "galaxy_j2_prime",
        displayName = "Samsung Galaxy J2 Prime (qHD 540p Extreme)",
        category = DeviceProfileType.POTATO_LEGACY,
        model = "SM-G532G",
        manufacturer = "samsung",
        brand = "samsung",
        device = "grandpplte",
        recommendedDownscale = 0.50f,
        targetFps = 60,
        badge = "EXTREME 540P",
        description = "Extreme low-end render mode. Forces games to absolute minimum asset quality."
    ),
    POCO_M3_LEGACY(
        id = "poco_m3_legacy",
        displayName = "Xiaomi POCO M3 (Budget 720p)",
        category = DeviceProfileType.POTATO_LEGACY,
        model = "M2010J19CG",
        manufacturer = "Xiaomi",
        brand = "POCO",
        device = "citrus",
        recommendedDownscale = 0.70f,
        targetFps = 60,
        badge = "LIGHT 720P",
        description = "Budget legacy profile for rock-solid frametimes on heavy titles."
    )
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
    val deviceSpoofPreset: DeviceSpoofPreset = DeviceSpoofPreset.NONE,
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
