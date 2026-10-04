package com.example.blem3fixer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader

class MainActivity : Activity() {
    private lateinit var result: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 48)
        }
        setContentView(content)
        content.addView(TextView(this).apply { text = "BLE-M3 控制器"; textSize = 26f })
        content.addView(TextView(this).apply {
            text = "先打开相机或任意目标 App，再点击下方按钮。透明准星会覆盖当前界面；直接点击目标位置并确认即可。已按本机实测范围 X=0–1800、Y=0–4100 自动换算。"
            textSize = 16f
            setPadding(0, 32, 0, 28)
        })
        content.addView(Button(this).apply {
            text = "选择中心键点击位置"
            setOnClickListener { startPicker() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(Button(this).apply {
            text = "读取当前设置"
            setOnClickListener { showCurrent() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
        result = TextView(this).apply { textSize = 16f; setPadding(0, 32, 0, 0) }
        content.addView(result)
        showCurrent()
    }

    private fun startPicker() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            result.text = "请允许“显示在其他应用上层”，然后返回此页面再点击一次。"
            return
        }
        try {
            startService(Intent(this, TargetPickerService::class.java))
            moveTaskToBack(true)
        } catch (_: Throwable) {
            result.text = "无法启动选点层，请重新打开应用后再试。"
        }
    }

    private fun showCurrent() {
        result.text = "正在读取 Magisk 配置…"
        Thread {
            val text = rootRead("/data/adb/ble-m3-remapper.conf")
            val x = Regex("target_x=(\\d+)").find(text ?: "")?.groupValues?.get(1)
            val y = Regex("target_y=(\\d+)").find(text ?: "")?.groupValues?.get(1)
            runOnUiThread {
                result.text = if (x != null && y != null) {
                    "当前中心键目标：($x, $y)\n（这是自动换算后的原始坐标）"
                } else {
                    "未读取到 Magisk 模块配置。请确认已安装模块并授予 Root。"
                }
            }
        }.start()
    }

    private fun rootRead(path: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
        val output = BufferedReader(process.inputStream.reader()).use { it.readText() }
        if (process.waitFor() == 0) output else null
    } catch (_: Throwable) { null }
}
