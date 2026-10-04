package com.example.blem3fixer

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/** Full-screen picker: one tap selects a screen point, the next tap confirms or retries. */
class TargetPickerService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var picker: PickerView

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::picker.isInitialized) showPicker()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { if (::picker.isInitialized) wm.removeView(picker); super.onDestroy() }

    private fun showPicker() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        picker = PickerView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        )
        wm.addView(picker, params)
    }

    private inner class PickerView : View(this@TargetPickerService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var pickedX = -1f
        private var pickedY = -1f
        init { setBackgroundColor(0x12000000) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.textAlign = Paint.Align.CENTER; paint.textSize = 42f; paint.color = Color.WHITE
            val text = if (pickedX < 0) "点按目标位置" else "点下方区域确认；点其他位置重新选择"
            canvas.drawText(text, width / 2f, 100f, paint)
            if (pickedX >= 0) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = Color.YELLOW
                canvas.drawCircle(pickedX, pickedY, 44f, paint); canvas.drawLine(pickedX - 64, pickedY, pickedX + 64, pickedY, paint)
                canvas.drawLine(pickedX, pickedY - 64, pickedX, pickedY + 64, paint); paint.style = Paint.Style.FILL
                paint.color = 0xdd237a35; canvas.drawRect(80f, height - 180f, width - 80f, height - 70f, paint)
                paint.color = Color.WHITE; canvas.drawText("确认此位置", width / 2f, height - 105f, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            if (pickedX >= 0 && event.y > height - 220f) { save(pickedX, pickedY); stopSelf(); return true }
            pickedX = event.x; pickedY = event.y; invalidate(); return true
        }
    }

    private fun save(screenX: Float, screenY: Float) {
        val metrics = resources.displayMetrics
        val rawX = (screenX / metrics.widthPixels * 4095f).roundToInt().coerceIn(0, 4095)
        val rawY = (screenY / metrics.heightPixels * 4095f).roundToInt().coerceIn(0, 4095)
        val command = "sed -i 's/^target_x=.*/target_x=$rawX/; s/^target_y=.*/target_y=$rawY/' /data/adb/ble-m3-remapper.conf"
        Runtime.getRuntime().exec(arrayOf("su", "-c", command))
    }
}
