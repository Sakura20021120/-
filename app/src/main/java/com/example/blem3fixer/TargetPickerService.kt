package com.example.blem3fixer

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.roundToInt

/** Full-screen picker: tap a target, then tap the confirmation area. */
class TargetPickerService : Service() {
    companion object {
        // Measured from BLE-M3's actual ABS axes, not from the 0..4095 event example.
        private const val RAW_MAX_X = 1800f
        private const val RAW_MAX_Y = 4100f
    }
    private lateinit var windowManager: WindowManager
    private lateinit var picker: PickerView
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var saving = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::picker.isInitialized) {
            try {
                showPicker()
            } catch (_: Throwable) {
                Toast.makeText(this, "无法显示选点层，请重新允许悬浮窗权限", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::picker.isInitialized && ::windowManager.isInitialized) {
            try {
                windowManager.removeViewImmediate(picker)
            } catch (_: Throwable) {
                // The system may already have detached the overlay.
            }
        }
        super.onDestroy()
    }

    private fun showPicker() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        picker = PickerView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(picker, params)
    }

    private inner class PickerView : View(this@TargetPickerService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pickedX = -1f
        private var pickedY = -1f

        init { setBackgroundColor(0x12000000.toInt()) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 42f
            paint.color = Color.WHITE
            val prompt = if (pickedX < 0) "点击目标位置" else "点击下方确认；点击其他位置可重新选择"
            canvas.drawText(prompt, width / 2f, 100f, paint)
            if (pickedX >= 0) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.color = Color.YELLOW
                canvas.drawCircle(pickedX, pickedY, 44f, paint)
                canvas.drawLine(pickedX - 64, pickedY, pickedX + 64, pickedY, paint)
                canvas.drawLine(pickedX, pickedY - 64, pickedX, pickedY + 64, paint)
                paint.style = Paint.Style.FILL
                paint.color = 0xdd237a35.toInt()
                canvas.drawRect(80f, height - 180f, width - 80f, height - 70f, paint)
                paint.color = Color.WHITE
                canvas.drawText("确认此位置", width / 2f, height - 105f, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP || saving) return true
            if (pickedX >= 0 && event.y > height - 220f) {
                saveAsync(pickedX, pickedY, width, height)
            } else {
                pickedX = event.x
                pickedY = event.y
                invalidate()
            }
            return true
        }
    }

    private fun saveAsync(screenX: Float, screenY: Float, screenWidth: Int, screenHeight: Int) {
        if (screenWidth <= 0 || screenHeight <= 0) return
        saving = true
        Thread {
            val rawX = (screenX / screenWidth * RAW_MAX_X).roundToInt().coerceIn(0, RAW_MAX_X.toInt())
            val rawY = (screenY / screenHeight * RAW_MAX_Y).roundToInt().coerceIn(0, RAW_MAX_Y.toInt())
            val command = "sed -i 's/^target_x=.*/target_x=$rawX/; s/^target_y=.*/target_y=$rawY/' /data/adb/ble-m3-remapper.conf"
            val success = try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                process.waitFor() == 0
            } catch (_: Throwable) { false }
            mainHandler.post {
                val message = if (success) "已保存中心键位置" else "保存失败：请确认 Magisk 模块已安装并授予 Root"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }.start()
    }
}
