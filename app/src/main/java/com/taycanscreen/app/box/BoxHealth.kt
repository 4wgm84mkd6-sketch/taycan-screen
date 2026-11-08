package com.taycanscreen.app.box

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import com.taycanscreen.app.log.AppLog
import java.io.File

/**
 * "Box health": what's installed on the Carlinkit box, what starts at boot, memory and storage.
 * Used to decide what bloatware to remove (owner reviews with Claude before disabling anything).
 */
object BoxHealth {
    data class App(
        val label: String,
        val pkg: String,
        val preinstalled: Boolean,
        val enabled: Boolean,
        val startsAtBoot: Boolean,
        val persistent: Boolean,
        val sizeBytes: Long,
        val version: String,
    )

    data class Summary(
        val uptimeSec: Long,
        val ramTotalMb: Long,
        val ramFreeMb: Long,
        val storageTotalGb: Double,
        val storageFreeGb: Double,
        val appCount: Int,
        val addedLater: Int,
        val bootStarters: Int,
    )

    fun apps(context: Context): List<App> {
        val pm = context.packageManager
        val packages: List<PackageInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }
        return packages.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val size = (listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty())
                .sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
            App(
                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(p.packageName),
                pkg = p.packageName,
                preinstalled = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                enabled = ai.enabled,
                startsAtBoot = p.requestedPermissions?.contains("android.permission.RECEIVE_BOOT_COMPLETED") == true,
                persistent = ai.flags and ApplicationInfo.FLAG_PERSISTENT != 0,
                sizeBytes = size,
                version = p.versionName ?: "",
            )
        }.sortedWith(compareBy<App>({ it.preinstalled }, { !it.startsAtBoot }, { it.label.lowercase() }))
    }

    fun summary(context: Context, apps: List<App>): Summary {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val st = StatFs(context.filesDir.absolutePath)
        val gb = 1024.0 * 1024 * 1024
        return Summary(
            uptimeSec = SystemClock.elapsedRealtime() / 1000,
            ramTotalMb = mi.totalMem / (1024 * 1024),
            ramFreeMb = mi.availMem / (1024 * 1024),
            storageTotalGb = st.totalBytes / gb,
            storageFreeGb = st.availableBytes / gb,
            appCount = apps.size,
            addedLater = apps.count { !it.preinstalled },
            bootStarters = apps.count { it.startsAtBoot && it.enabled },
        )
    }

    /** Writes the full inventory into the log so it reaches Claude with Send log. */
    fun logInventory(context: Context) {
        val apps = apps(context)
        val s = summary(context, apps)
        AppLog.info(
            "box",
            "Summary: up ${s.uptimeSec} s, RAM ${s.ramFreeMb}/${s.ramTotalMb} MB free, storage " +
                "%.1f/%.1f GB free, %d apps (%d added later), %d start at boot".format(
                    s.storageFreeGb, s.storageTotalGb, s.appCount, s.addedLater, s.bootStarters,
                ),
        )
        apps.forEach { a ->
            AppLog.info(
                "box",
                "app pkg=${a.pkg} label=\"${a.label}\" ${if (a.preinstalled) "preinstalled" else "added"} " +
                    "${if (a.enabled) "enabled" else "DISABLED"} ${if (a.startsAtBoot) "boot" else "-"} " +
                    "${if (a.persistent) "persistent" else "-"} size=${a.sizeBytes / 1024}KB v=${a.version}",
            )
        }
        AppLog.info("box", "Inventory: ${apps.size} apps logged")
    }

    fun uninstallIntent(pkg: String) = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
    fun settingsIntent(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
}
