package io.github.hyperisland.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.hyperisland.xposed.hook.SystemUI.BigIslandMinWidthHook
import io.github.hyperisland.xposed.hook.SystemUI.IslandTopOffsetHook
import io.github.hyperisland.xposed.hook.SystemUI.SmoothIslandHook
import io.github.hyperisland.xposed.hook.BluetoothIslandHook
import io.github.hyperisland.xposed.hook.DownloadHook
import io.github.hyperisland.xposed.hook.FocusNotifStatusBarIconHook
import io.github.hyperisland.xposed.hook.SystemUI.GenericProgressHook
import io.github.hyperisland.xposed.hook.IslandBackgroundHook
import io.github.hyperisland.xposed.hook.IslandDimenHook
import io.github.hyperisland.xposed.hook.IslandDispatcherHook
import io.github.hyperisland.xposed.hook.IslandOuterGlowHook
import io.github.hyperisland.xposed.hook.IslandTextColorHook
import io.github.hyperisland.xposed.hook.KeepIslandHook
import io.github.hyperisland.xposed.hook.MarqueeHook
import io.github.hyperisland.xposed.hook.SettingsHomeEntryHook
import io.github.hyperisland.xposed.hook.TextShadeHook
import io.github.hyperisland.xposed.hook.TempHiddenBehaviorHook
import io.github.hyperisland.xposed.hook.ToastUiInterceptHook
import io.github.hyperisland.xposed.hook.UnlockAllFocusHook
import io.github.hyperisland.xposed.hook.UnlockFocusAuthHook
import io.github.hyperisland.xposed.islanddispatch.IslandDispatcher
import io.github.hyperisland.xposed.islanddispatch.IslandRequest
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModule

class HyperIslandModule : XposedModule() {

    private var configManagerInitialized = false
    private var batteryReceiver: BroadcastReceiver? = null
    private val BATTERY_NOTIF_ID = 9999

    override fun onPackageLoaded(param: PackageLoadedParam) {
        initializeConfigManager()
        log("onPackageLoaded: pkg=${param.packageName}")

        when (param.packageName) {
            "com.android.systemui" -> {
                IslandDispatcherHook.init(this, param)
                GenericProgressHook.init(this, param)
                MarqueeHook.init(this, param)
                BigIslandMinWidthHook.init(this, param)
                UnlockAllFocusHook.init(this, param)
                FocusNotifStatusBarIconHook.init(this, param)
                IslandOuterGlowHook.init(this, param)
                IslandBackgroundHook.init(this, param)
                IslandTextColorHook.init(this, param)
                TextShadeHook.init(this, param)
                IslandDimenHook.init(this, param)
                IslandTopOffsetHook.init(this, param)
                if (ConfigManager.getBoolean("pref_temp_hide_behavior_enabled", false)) {
                    TempHiddenBehaviorHook.init(this, param)
                }
                if (ConfigManager.getBoolean("pref_smooth_island", false)) {
                    SmoothIslandHook.init(this, param)
                }
                ToastUiInterceptHook.init(this, param)
                KeepIslandHook.init(this, param)
                if (ConfigManager.getBoolean("pref_bluetooth_island", false)) {
                    BluetoothIslandHook.init(this, param)
                }

                // 🔋 注册电池广播
                registerBatteryReceiver(param)
            }

            "com.android.providers.downloads",
            "com.xiaomi.android.app.downloadmanager" ->
                DownloadHook.init(this, param)

            "com.xiaomi.xmsf" ->
                UnlockFocusAuthHook.init(this, param)

            "com.android.settings" ->
                if (ConfigManager.getBoolean("pref_settings_home_entry", true)) {
                    SettingsHomeEntryHook.init(this, param)
                }
        }
    }

    // ==================== 🔋 电池功能 ====================

    private fun registerBatteryReceiver(param: PackageLoadedParam) {
        val context = param.appContext ?: return
        val receiver = object : BroadcastReceiver() {
            private var lastPower: Double? = null
            private var lastLevel: Int? = null
            private var lastTemp: Double? = null

            override fun onReceive(context: Context?, intent: Intent?) {
                // 🔋 读取开关状态
                val enabled = ConfigManager.getBoolean("pref_battery_island", false)
                if (!enabled) {
                    sendBatteryIsland(context, null)
                    return
                }

                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                if (!isCharging) {
                    sendBatteryIsland(context, null)
                    lastPower = null
                    lastLevel = null
                    lastTemp = null
                    return
                }

                val bm = context?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val current = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                val voltage = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_VOLTAGE_NOW) ?: 0
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)

                val cycleCount = try {
                    bm?.getIntProperty(5) ?: -1
                } catch (_: Exception) {
                    -1
                }

                val power = if (voltage > 0 && current != 0) {
                    (kotlin.math.abs(current) / 1000.0) * (voltage / 1000.0)
                } else null

                if (power == lastPower && level == lastLevel && temp == lastTemp) return
                lastPower = power
                lastLevel = level
                lastTemp = temp

                val batteryData = mapOf(
                    "level" to level,
                    "power" to power,
                    "temp" to temp,
                    "voltage" to voltage,
                    "current" to current,
                    "plugged" to plugged,
                    "health" to health,
                    "cycleCount" to cycleCount,
                    "isCharging" to true
                )

                sendBatteryIsland(context, batteryData)
            }
        }

        batteryReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        log("BatteryReceiver registered")
    }

    private fun sendBatteryIsland(context: Context?, data: Map<String, Any>?) {
        if (context == null) return

        if (data == null || data["isCharging"] != true) {
            val cancelIntent = Intent(IslandDispatcher.ACTION_CANCEL).apply {
                putExtra(IslandDispatcher.EXTRA_NOTIF_ID, BATTERY_NOTIF_ID)
            }
            context.sendOrderedBroadcast(cancelIntent, IslandDispatcher.PERM, null, null, 0, null, null)
            log("Battery island hidden")
            return
        }

        val level = data["level"] as? Int ?: 0
        val power = (data["power"] as? Double) ?: 0.0
        val temp = data["temp"] as? Double ?: 0.0
        val voltage = data["voltage"] as? Int ?: 0
        val current = data["current"] as? Int ?: 0
        val plugged = data["plugged"] as? Int ?: -1
        val health = data["health"] as? Int ?: -1
        val cycleCount = data["cycleCount"] as? Int ?: -1

        // 小岛（折叠）显示功率
        val title = if (power > 0) String.format("%.1fW", power) else "充电中"

        // 大岛（展开）显示完整信息
        val pluggedText = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "⚡有线"
            BatteryManager.BATTERY_PLUGGED_USB -> "🔌USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "🔄无线"
            else -> ""
        }

        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "✅良好"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "🔥过热"
            BatteryManager.BATTERY_HEALTH_DEAD -> "💀耗尽"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "⚠️过压"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "❌故障"
            else -> ""
        }

        val content = buildString {
            append("电量 $level%")
            append(" | ${String.format("%.1f", temp)}°C")
            if (power > 0) append(" | ${String.format("%.1f", power)}W")
            if (voltage > 0) append(" | ${String.format("%.2f", voltage / 1000.0)}V")
            if (current != 0) append(" | ${String.format("%.0f", kotlin.math.abs(current) / 1000.0)}mA")
            if (pluggedText.isNotEmpty()) append(" | $pluggedText")
            if (healthText.isNotEmpty()) append(" | $healthText")
            if (cycleCount > 0) append(" | ${cycleCount}次循环")
        }

        val maxLen = 45
        val finalContent = if (content.length > maxLen) content.take(maxLen - 3) + "..." else content

        val iconRes = context.resources?.getIdentifier("battery_icon", "drawable", "com.android.systemui") ?: 0

        val request = IslandRequest(
            title = title,
            content = finalContent,
            icon = iconRes,
            firstFloat = true,
            enableFloat = true,
            clearBeforePost = true,
            highlightColor = "#4CAF50",
            showNotification = false,
            islandOuterGlow = true,
            notifId = BATTERY_NOTIF_ID
        )

        IslandDispatcher.sendBroadcast(context, request)
        log("Battery island updated: $title | $finalContent")
    }

    // ==================== 初始化 ====================

    private fun initializeConfigManager() {
        if (!configManagerInitialized) {
            ConfigManager.init(this)
            configManagerInitialized = true
        }
    }
}
