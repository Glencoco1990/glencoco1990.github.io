package io.github.glencoco.spyscan

import android.Manifest
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class Level { HIGH, MEDIUM, LOW, OK }

data class Finding(val text: String, val points: Int)

data class AppReport(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val installer: String?,
    val findings: List<Finding>,
) {
    val score: Int get() = findings.sumOf { it.points }
    val level: Level
        get() = when {
            score >= 60 -> Level.HIGH
            score >= 30 -> Level.MEDIUM
            score > 0 -> Level.LOW
            else -> Level.OK
        }
}

data class DeviceCheck(val title: String, val detail: String, val level: Level, val settingsAction: String?)

data class ScanResult(val apps: List<AppReport>, val device: List<DeviceCheck>, val scannedCount: Int)

/**
 * Fully on-device heuristic scanner. Nothing is uploaded anywhere (the app has no INTERNET permission).
 *
 * It looks for the things commercial stalkerware has to do to work:
 *  - get an Accessibility Service turned on (reads the screen / keystrokes)
 *  - become Device Admin (so it can't be uninstalled easily)
 *  - read notifications (WhatsApp/LINE messages)
 *  - hide its launcher icon, use a fake "system" name, be sideloaded outside an app store
 *  - hold SMS / call log / mic / background location permissions
 */
class Scanner(private val ctx: Context) {

    private val pm = ctx.packageManager

    /** Vendor / product names of known commercial stalkerware & spy apps (matched against package + label). */
    private val knownSpyKeywords = listOf(
        "mspy", "flexispy", "hoverwatch", "cocospy", "spyic", "thetruthspy", "truthspy", "xnspy",
        "spyzie", "ikeymonitor", "highster", "spyera", "kidsguard", "clevguard", "eyezy", "umobix",
        "mobistealth", "spyhuman", "spytomobile", "spymaster", "copy9", "guestspy", "mobilespy",
        "phonesheriff", "spybubble", "letmespy", "spapp", "talklog", "catwatchful", "spyphone",
        "androidmonitor", "easylogger", "snoopza", "fonemonitor", "minspy", "neatspy", "spyine",
        "spylix", "mlite", "onespy", "allspy", "realtimespy", "netspy", "spyfone", "teensafe",
        "mobiletracker", "free.spy", "spy.app", "keylogger", "stalker", "monitor.phone",
        "cerberus", "aispyer", "wtspy", "spyapp", "hidespy",
    )

    /** Words a hidden app uses so the victim thinks it's part of Android. */
    private val fakeSystemNames = listOf(
        "system service", "system services", "system update", "sync service", "update service",
        "wifi service", "wi-fi", "device health", "google service", "android service", "system",
        "settings", "security service", "battery service", "phone services", "process manager",
        "sim service", "framework", "sys", "ระบบ", "อัปเดต", "บริการ",
    )

    /** App stores we trust as the install source. */
    private val trustedInstallers = setOf(
        "com.android.vending", "com.google.android.feedback",
        "com.sec.android.app.samsungapps", "com.huawei.appmarket", "com.xiaomi.market",
        "com.xiaomi.mipicks", "com.heytap.market", "com.oppo.market", "com.bbk.appstore",
        "com.vivo.appstore", "com.amazon.venezia", "com.oneplus.backuprestore",
        "com.samsung.android.app.omcagent", "com.google.android.apps.work.clouddpc",
    )

    fun scan(): ScanResult {
        val accessibility = enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val notifListeners = enabledComponents("enabled_notification_listeners")
        val admins = activeAdmins()
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        @Suppress("DEPRECATION")
        val packages: List<PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            }
        } catch (e: Exception) {
            emptyList()
        }

        val reports = ArrayList<AppReport>()
        for (p in packages) {
            val ai = p.applicationInfo ?: continue
            if (p.packageName == ctx.packageName) continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { p.packageName }
            val haystack = (p.packageName + " " + label).lowercase(Locale.ROOT)
            val findings = ArrayList<Finding>()

            knownSpyKeywords.firstOrNull { haystack.contains(it) }?.let {
                findings += Finding("ชื่อตรงกับแอปสอดแนมที่รู้จัก (\"$it\")", 100)
            }

            // Pure, untouched system apps are trusted unless they match a known spy name.
            if (isSystem && !isUpdatedSystem) {
                if (findings.isNotEmpty()) reports += AppReport(p.packageName, label, true, null, findings)
                continue
            }

            val installer = installerOf(p.packageName)
            val fromStore = installer != null && installer in trustedInstallers

            if (accessibility.contains(p.packageName)) {
                findings += Finding("เปิด Accessibility (อ่านหน้าจอ/สิ่งที่พิมพ์ได้)", if (fromStore) 20 else 40)
            }
            if (admins.contains(p.packageName)) {
                findings += Finding("เป็นผู้ดูแลอุปกรณ์ (Device Admin) — ลบยาก", if (fromStore) 15 else 30)
            }
            if (notifListeners.contains(p.packageName)) {
                findings += Finding("อ่านการแจ้งเตือนทั้งหมดได้ (เช่น ข้อความ LINE/WhatsApp)", if (fromStore) 8 else 20)
            }
            if (!isSystem && !fromStore) {
                findings += Finding(
                    "ไม่ได้ติดตั้งจาก App Store" + (installer?.let { " (ติดตั้งโดย $it)" } ?: " (ไม่ทราบแหล่งที่มา)"),
                    15,
                )
            }
            if (!isSystem && pm.getLaunchIntentForPackage(p.packageName) == null) {
                findings += Finding("ซ่อนไอคอน — ไม่มีไอคอนในหน้าแอป", if (fromStore) 5 else 25)
            }
            val lowerLabel = label.lowercase(Locale.ROOT).trim()
            if (!isSystem && !fromStore && fakeSystemNames.any { lowerLabel == it || lowerLabel.startsWith("$it ") || lowerLabel.contains(it) && lowerLabel.length < 22 }) {
                findings += Finding("ใช้ชื่อเลียนแบบแอประบบ (\"$label\")", 20)
            }
            if (opAllowed(appOps, AppOpsManager.OPSTR_GET_USAGE_STATS, ai.uid, p.packageName)) {
                findings += Finding("ดูประวัติการใช้งานแอปได้ (Usage access)", if (fromStore) 3 else 10)
            }

            // Granted runtime permissions
            val granted = grantedPermissions(p)
            var sensitive = 0
            fun perm(name: String, text: String, store: Int, side: Int) {
                if (granted.contains(name)) {
                    sensitive++
                    findings += Finding(text, if (fromStore) store else side)
                }
            }
            perm(Manifest.permission.READ_SMS, "อ่าน SMS", 4, 12)
            perm(Manifest.permission.RECEIVE_SMS, "ดักรับ SMS", 2, 6)
            perm(Manifest.permission.READ_CALL_LOG, "อ่านประวัติการโทร", 4, 12)
            perm(Manifest.permission.RECORD_AUDIO, "ใช้ไมโครโฟน", 1, 8)
            perm(Manifest.permission.CAMERA, "ใช้กล้อง", 0, 5)
            perm(Manifest.permission.ACCESS_BACKGROUND_LOCATION, "ติดตามตำแหน่งตลอดเวลา (เบื้องหลัง)", 3, 12)
            perm(Manifest.permission.ACCESS_FINE_LOCATION, "รู้ตำแหน่งแม่นยำ", 0, 4)
            perm(Manifest.permission.READ_CONTACTS, "อ่านรายชื่อผู้ติดต่อ", 0, 4)
            if (sensitive >= 5 && !fromStore) {
                findings += Finding("มีสิทธิ์สอดแนมครบชุด ($sensitive อย่าง)", 15)
            }

            reports += AppReport(p.packageName, label, isSystem, installer, findings)
        }

        reports.sortWith(compareByDescending<AppReport> { it.score }.thenBy { it.label.lowercase(Locale.ROOT) })
        return ScanResult(reports, deviceChecks(), packages.size)
    }

    // ---------------------------------------------------------------- device-level checks

    private fun deviceChecks(): List<DeviceCheck> {
        val out = ArrayList<DeviceCheck>()
        val cr = ctx.contentResolver

        // Root
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk", "/data/adb/magisk")
        val rootHit = suPaths.firstOrNull { File(it).exists() }
        val magisk = isInstalled("com.topjohnwu.magisk")
        out += if (rootHit != null || magisk) {
            DeviceCheck("เครื่องถูก Root", "พบ ${rootHit ?: "Magisk"} — แอปสอดแนมขั้นสูงต้องใช้ Root ถ้าคุณไม่ได้ทำเอง ถือว่าอันตรายมาก", Level.HIGH, null)
        } else {
            DeviceCheck("ไม่พบการ Root", "ระบบไม่ถูกแก้ไขระดับลึก", Level.OK, null)
        }

        // User-installed CA certificates (traffic interception)
        val userCerts = try {
            val ks = KeyStore.getInstance("AndroidCAStore")
            ks.load(null)
            ks.aliases().toList().count { it.startsWith("user:") }
        } catch (e: Exception) { 0 }
        out += if (userCerts > 0) {
            DeviceCheck("มีใบรับรอง (CA) ที่ผู้ใช้ติดตั้ง $userCerts ใบ", "อาจถูกใช้ดักดูข้อมูลอินเทอร์เน็ตที่เข้ารหัส ถ้าไม่ได้ติดตั้งเอง (หรือไม่ใช่ของบริษัท) ให้ลบที่ ตั้งค่า > ความปลอดภัย > ข้อมูลรับรอง", Level.HIGH, Settings.ACTION_SECURITY_SETTINGS)
        } else {
            DeviceCheck("ไม่มีใบรับรองแปลกปลอม", "ไม่พบ CA ที่ผู้ใช้ติดตั้งเพิ่ม", Level.OK, null)
        }

        // VPN
        val vpn = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } catch (e: Exception) { false }
        if (vpn) out += DeviceCheck("มี VPN ทำงานอยู่", "ถ้าคุณไม่ได้เปิดเอง VPN อาจถูกใช้ส่งข้อมูลการใช้งานเน็ตออกไป", Level.MEDIUM, Settings.ACTION_WIRELESS_SETTINGS)

        // Screen lock
        val secure = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        out += if (secure) DeviceCheck("ตั้งรหัสล็อกหน้าจอแล้ว", "ดีมาก — อย่าลืมเปลี่ยนถ้ามีคนอื่นรู้รหัส", Level.OK, null)
        else DeviceCheck("ไม่ได้ตั้งรหัสล็อกหน้าจอ", "ใครหยิบเครื่องไปก็ติดตั้งแอปสอดแนมได้ใน 2 นาที", Level.HIGH, Settings.ACTION_SECURITY_SETTINGS)

        // Unknown sources / USB debugging / developer options
        val adb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        if (adb) out += DeviceCheck("เปิด USB Debugging อยู่", "ใครเสียบสายคอมก็ติดตั้งแอปได้โดยไม่มีไอคอน ถ้าไม่ได้ใช้ให้ปิด", Level.MEDIUM, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        else if (dev) out += DeviceCheck("เปิดโหมดนักพัฒนาอยู่", "ถ้าไม่ได้เปิดเอง ให้ตรวจสอบและปิด", Level.LOW, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

        // Apps allowed to install other apps
        val installers = sideloadCapableApps()
        if (installers.isNotEmpty()) out += DeviceCheck(
            "แอปที่ติดตั้งแอปอื่นได้: ${installers.size} แอป",
            installers.joinToString(", ") + " — ปิดสิทธิ์ \"ติดตั้งแอปที่ไม่รู้จัก\" ถ้าไม่จำเป็น",
            if (installers.size > 2) Level.MEDIUM else Level.LOW,
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        )

        // Security patch age
        val patch = Build.VERSION.SECURITY_PATCH
        try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)
            val yearAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -12) }.time
            out += if (d != null && d.before(yearAgo)) DeviceCheck("แพตช์ความปลอดภัยเก่า ($patch)", "เครื่องที่ไม่อัปเดตเกิน 1 ปีโดนเจาะได้ง่ายขึ้น ให้อัปเดตระบบ", Level.MEDIUM, Settings.ACTION_DEVICE_INFO_SETTINGS)
            else DeviceCheck("แพตช์ความปลอดภัย $patch", "ยังอยู่ในเกณฑ์ปกติ", Level.OK, null)
        } catch (e: Exception) { /* ignore */ }

        // Accessibility services overview (good for user to eyeball)
        val acc = enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (acc.isNotEmpty()) out += DeviceCheck(
            "Accessibility ที่เปิดอยู่: ${acc.size}",
            acc.joinToString(", ") { labelOf(it) } + " — ถ้าไม่รู้จักตัวไหนให้ปิด",
            Level.LOW, Settings.ACTION_ACCESSIBILITY_SETTINGS,
        )

        return out.sortedBy { it.level.ordinal }
    }

    // ---------------------------------------------------------------- helpers

    private fun enabledComponents(key: String): Set<String> {
        val raw = try { Settings.Secure.getString(ctx.contentResolver, key) } catch (e: Exception) { null } ?: return emptySet()
        return raw.split(':').mapNotNull { ComponentName.unflattenFromString(it)?.packageName ?: it.substringBefore('/').ifBlank { null } }.toSet()
    }

    private fun activeAdmins(): Set<String> = try {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
    } catch (e: Exception) { emptySet() }

    @Suppress("DEPRECATION")
    private fun installerOf(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else pm.getInstallerPackageName(pkg)
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun opAllowed(ops: AppOpsManager, op: String, uid: Int, pkg: String): Boolean = try {
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(op, uid, pkg) else ops.checkOpNoThrow(op, uid, pkg)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    private fun grantedPermissions(p: PackageInfo): Set<String> {
        val names = p.requestedPermissions ?: return emptySet()
        val flags = p.requestedPermissionsFlags ?: return emptySet()
        val out = HashSet<String>()
        for (i in names.indices) {
            if (i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) out += names[i]
        }
        return out
    }

    private fun sideloadCapableApps(): List<String> {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val apps = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList() }
        return apps.filter { ai ->
            ai.packageName != ctx.packageName &&
                ai.uid != Process.SYSTEM_UID &&
                opAllowed(ops, "android:request_install_packages", ai.uid, ai.packageName) &&
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        }.map { labelOf(it.packageName) }
    }

    private fun isInstalled(pkg: String): Boolean = try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    fun labelOf(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
}
