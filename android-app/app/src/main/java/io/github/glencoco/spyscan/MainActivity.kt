package io.github.glencoco.spyscan

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0E1116")
    private val card = Color.parseColor("#181C24")
    private val text = Color.parseColor("#E8ECF2")
    private val muted = Color.parseColor("#8A93A3")
    private val green = Color.parseColor("#3DDC97")
    private val red = Color.parseColor("#FF5C5C")
    private val amber = Color.parseColor("#FFB547")
    private val blue = Color.parseColor("#6AA8FF")

    private lateinit var root: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var scanBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var lastResult: ScanResult? = null
    private var showAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(28))
        }
        scroll.addView(root, MATCH_PARENT, WRAP_CONTENT)
        setContentView(scroll)

        root.addView(label("SpyScan", 30f, text, bold = true))
        root.addView(label("ตรวจหาแอปสอดแนม & สิ่งแปลกปลอมในเครื่อง\nสแกนในเครื่อง 100% — แอปนี้ไม่มีสิทธิ์ใช้อินเทอร์เน็ต", 14f, muted).apply {
            setPadding(0, dp(4), 0, dp(22))
        })

        scanBtn = Button(this).apply {
            text = "🔍  สแกนเลย"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(bg)
            isAllCaps = false
            background = rounded(green, 18f)
            setPadding(0, dp(18), 0, dp(18))
            setOnClickListener { startScan() }
        }
        root.addView(scanBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
        })
        status = label("", 14f, muted).apply { setPadding(0, dp(10), 0, 0); gravity = Gravity.CENTER_HORIZONTAL }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    override fun onResume() {
        super.onResume()
        // Re-scan silently after the user comes back from uninstalling / changing a setting.
        if (lastResult != null) startScan()
    }

    private fun startScan() {
        scanBtn.isEnabled = false
        scanBtn.alpha = 0.6f
        progress.visibility = View.VISIBLE
        status.text = "กำลังตรวจแอปและการตั้งค่า…"
        Thread {
            val r = try { Scanner(this).scan() } catch (e: Exception) { null }
            runOnUiThread {
                scanBtn.isEnabled = true
                scanBtn.alpha = 1f
                scanBtn.text = "🔄  สแกนอีกครั้ง"
                progress.visibility = View.GONE
                if (r == null) {
                    status.text = "สแกนไม่สำเร็จ ลองใหม่อีกครั้ง"
                } else {
                    lastResult = r
                    render(r)
                }
            }
        }.start()
    }

    private fun render(r: ScanResult) {
        results.removeAllViews()
        val high = r.apps.count { it.level == Level.HIGH }
        val med = r.apps.count { it.level == Level.MEDIUM }
        val devBad = r.device.count { it.level == Level.HIGH }

        status.text = "ตรวจแล้ว ${r.scannedCount} แพ็กเกจ"

        // ---- Verdict
        val (vColor, vTitle, vBody) = when {
            high > 0 || devBad > 0 -> Triple(red, "⚠️ พบสิ่งที่น่าสงสัยมาก", "ดูรายการสีแดงด้านล่าง ถ้าไม่รู้จักแอปนั้น ให้ถอนการติดตั้ง")
            med > 0 -> Triple(amber, "🟠 มีบางอย่างควรตรวจสอบ", "ไม่ได้แปลว่าเป็นสปายเสมอไป แต่ควรเช็กว่ารู้จักแอปเหล่านี้")
            else -> Triple(green, "✅ ไม่พบสัญญาณสปายแวร์", "ไม่พบแอปที่มีพฤติกรรมสอดแนมจากการตรวจแบบนี้")
        }
        results.addView(cardView(vColor).apply {
            addView(label(vTitle, 20f, vColor, bold = true))
            addView(label(vBody, 14f, text).apply { setPadding(0, dp(6), 0, 0) })
            addView(label("เสี่ยงสูง $high  •  ควรตรวจ $med  •  ตั้งค่าเครื่องเสี่ยง $devBad", 13f, muted).apply { setPadding(0, dp(10), 0, 0) })
        })

        // ---- Apps
        results.addView(section("แอปที่ติดตั้ง"))
        val visible = r.apps.filter { showAll || it.level == Level.HIGH || it.level == Level.MEDIUM }
        if (visible.isEmpty()) {
            results.addView(label("ไม่มีแอปที่น่าสงสัย 🎉", 14f, muted).apply { setPadding(dp(4), 0, 0, dp(8)) })
        }
        visible.forEach { results.addView(appCard(it)) }

        results.addView(Button(this).apply {
            text = if (showAll) "ซ่อนแอปที่ปกติ" else "แสดงแอปทั้งหมด (${r.apps.size})"
            isAllCaps = false
            setTextColor(blue)
            background = rounded(card, 14f)
            setOnClickListener { showAll = !showAll; render(r) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })

        // ---- Device
        results.addView(section("การตั้งค่าเครื่อง"))
        r.device.forEach { results.addView(deviceCard(it)) }

        // ---- Safety note
        results.addView(section("ถ้าสงสัยว่ามีคนแอบติดตั้ง"))
        results.addView(cardView(blue).apply {
            addView(label(
                "• ถ้าคิดว่าคนใกล้ตัว (แฟน/อดีตแฟน/คนในบ้าน) ติดตั้ง การลบแอปอาจทำให้เขารู้ตัว วางแผนความปลอดภัยก่อน\n" +
                    "• เก็บภาพหน้าจอผลสแกนไว้เป็นหลักฐานก่อนลบ\n" +
                    "• เปลี่ยนรหัส Google / LINE / Facebook / ธนาคาร จาก \"อีกเครื่อง\" และเปิด 2FA\n" +
                    "• ถ้าลบแอปไม่ได้ ให้ไปปิด Device Admin ก่อน แล้วค่อยถอนการติดตั้ง\n" +
                    "• วิธีที่ชัวร์ที่สุดคือสำรองรูป/รายชื่อ แล้ว Factory Reset\n" +
                    "• ขอความช่วยเหลือ: ศูนย์ช่วยเหลือสังคม โทร 1300 (24 ชม.)",
                14f, text,
            ))
            addView(label("หมายเหตุ: แอปนี้ตรวจจากพฤติกรรมและสิทธิ์ของแอป ไม่ใช่แอนตี้ไวรัส ผลที่ได้คือ \"สัญญาณ\" ไม่ใช่คำตัดสิน", 12f, muted).apply { setPadding(0, dp(10), 0, 0) })
        })
    }

    private fun appCard(a: AppReport): View {
        val c = when (a.level) { Level.HIGH -> red; Level.MEDIUM -> amber; Level.LOW -> blue; Level.OK -> green }
        val tag = when (a.level) { Level.HIGH -> "เสี่ยงสูง"; Level.MEDIUM -> "ควรตรวจ"; Level.LOW -> "ต่ำ"; Level.OK -> "ปกติ" }
        return cardView(c).apply {
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(label(a.label, 17f, text, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(label("$tag · ${a.score}", 12f, bg, bold = true).apply {
                background = rounded(c, 10f); setPadding(dp(10), dp(4), dp(10), dp(4))
            })
            addView(head)
            addView(label(a.packageName, 12f, muted).apply { setPadding(0, dp(2), 0, dp(8)); setTextIsSelectable(true) })
            a.findings.sortedByDescending { it.points }.forEach {
                addView(label("• ${it.text}", 14f, text).apply { setPadding(0, dp(1), 0, dp(1)) })
            }
            if (a.findings.isEmpty()) addView(label("ไม่พบสิ่งผิดปกติ", 13f, muted))

            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            row.addView(smallBtn("ข้อมูลแอป", blue) { openAppInfo(a.packageName) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
            if (!a.isSystem) row.addView(smallBtn("ถอนการติดตั้ง", red) { uninstall(a.packageName) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(row)
        }
    }

    private fun deviceCard(d: DeviceCheck): View {
        val c = when (d.level) { Level.HIGH -> red; Level.MEDIUM -> amber; Level.LOW -> blue; Level.OK -> green }
        val icon = when (d.level) { Level.HIGH -> "⛔"; Level.MEDIUM -> "⚠️"; Level.LOW -> "ℹ️"; Level.OK -> "✅" }
        return cardView(c).apply {
            addView(label("$icon  ${d.title}", 16f, text, bold = true))
            addView(label(d.detail, 13f, muted).apply { setPadding(0, dp(4), 0, 0) })
            d.settingsAction?.let { action ->
                addView(smallBtn("เปิดการตั้งค่า", c) { openSettings(action) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun openAppInfo(pkg: String) = safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    @Suppress("DEPRECATION")
    private fun uninstall(pkg: String) {
        Toast.makeText(this, "ถ้าลบไม่ได้ ให้ปิด Device Admin ของแอปนี้ก่อน", Toast.LENGTH_LONG).show()
        safeStart(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg")))
    }

    private fun openSettings(action: String) {
        if (!safeStart(Intent(action))) safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    private fun safeStart(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (e: Exception) {
        Toast.makeText(this, "เปิดหน้านี้ไม่ได้บนเครื่องรุ่นนี้", Toast.LENGTH_SHORT).show(); false
    }

    // ---------------------------------------------------------------- tiny view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun section(title: String) = label(title.uppercase(), 13f, muted, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(26), 0, dp(10))
    }

    private fun cardView(accent: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(card, 16f, Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent)))
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }

    private fun smallBtn(t: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 14f
        setTextColor(color)
        background = rounded(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)), 12f)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }
}
