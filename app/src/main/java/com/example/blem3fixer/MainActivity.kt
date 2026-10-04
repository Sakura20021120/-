package com.example.blem3fixer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.OutputStreamWriter

/** Root companion for the Magisk BLE-M3 remapper. */
class MainActivity : Activity() {
    private lateinit var defaultX: EditText
    private lateinit var defaultY: EditText
    private lateinit var targetX: EditText
    private lateinit var targetY: EditText
    private lateinit var tolerance: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        content.addView(title("BLE-M3 控制器"))
        content.addView(note("需要已安装 BLE-M3 Magisk 模块及 Magisk Root 授权。坐标范围为 0–4095。"))
        content.addView(title("中心键点击位置"))
        defaultX = field(content, "中心键原始 X", "1012")
        defaultY = field(content, "中心键原始 Y", "1740")
        targetX = field(content, "目标点击 X", "2048")
        targetY = field(content, "目标点击 Y", "3370")
        tolerance = field(content, "识别误差", "5")

        content.addView(Button(this).apply {
            text = "应用中心键设置"
            setOnClickListener { applyCenterConfig() }
        })
        content.addView(Button(this).apply {
            text = "读取遥控器最近原始坐标"
            setOnClickListener { readState() }
        })
        content.addView(title("方向键滑动（校准中）"))
        content.addView(note("上、下、左、右尚需先记录各自的原始坐标。点击“读取最近原始坐标”后，按一次方向键并回到本页，即可获得校准数据。后续版本会将这些数据分别映射为可编辑的滑动起点、终点和时长。"))
        status = note("状态：尚未读取模块配置")
        content.addView(status)
        loadConfig()
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 21f; setTextColor(Color.WHITE)
        setPadding(0, 22, 0, 12)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.LTGRAY)
        setPadding(0, 6, 0, 16)
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText {
        parent.addView(TextView(this).apply { text = label; textSize = 15f })
        return EditText(this).also {
            it.setText(value); it.inputType = 2
            parent.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun value(input: EditText): Int? = input.text.toString().toIntOrNull()?.takeIf { it in 0..4095 }

    private fun applyCenterConfig() {
        val dx = value(defaultX); val dy = value(defaultY); val tx = value(targetX); val ty = value(targetY)
        val tol = tolerance.text.toString().toIntOrNull()?.takeIf { it in 0..100 }
        if (listOf(dx, dy, tx, ty, tol).any { it == null }) { status.text = "状态：请输入有效的 0–4095 坐标。"; return }
        val config = "# Managed by BLE-M3 控制器\n" +
            "default_x=$dx\ndefault_y=$dy\ntarget_x=$tx\ntarget_y=$ty\ntolerance=$tol\n"
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > /data/adb/ble-m3-remapper.conf && chmod 0600 /data/adb/ble-m3-remapper.conf"))
        OutputStreamWriter(process.outputStream).use { it.write(config) }
        if (process.waitFor() == 0) status.text = "状态：已写入。下一次遥控器事件立即使用新坐标。"
        else status.text = "状态：未获得 Root 授权或模块未安装。"
    }

    private fun rootRead(path: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
        val output = BufferedReader(process.inputStream.reader()).use { it.readText() }
        if (process.waitFor() == 0) output else null
    } catch (_: Throwable) { null }

    private fun loadConfig() {
        val config = rootRead("/data/adb/ble-m3-remapper.conf") ?: return
        val values = config.lineSequence().mapNotNull {
            val split = it.split("=", limit = 2); if (split.size == 2) split[0] to split[1] else null
        }.toMap()
        defaultX.setText(values["default_x"] ?: "1012"); defaultY.setText(values["default_y"] ?: "1740")
        targetX.setText(values["target_x"] ?: "2048"); targetY.setText(values["target_y"] ?: "3370")
        tolerance.setText(values["tolerance"] ?: "5")
        status.text = "状态：已读取模块配置。"
    }

    private fun readState() {
        val state = rootRead("/data/adb/ble-m3-remapper.state")
        status.text = if (state.isNullOrBlank()) "状态：尚未收到遥控器事件。先按一次 BLE-M3 按键。" else "最近原始输入：\n$state"
    }
}
