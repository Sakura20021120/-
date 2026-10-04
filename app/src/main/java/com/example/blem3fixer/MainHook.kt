package com.example.blem3fixer

import android.view.InputDevice
import android.view.MotionEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_LoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class MainHook : IXposedHookLoadPackage {
    companion object {
        private const val DEFAULT_X = 1012f
        private const val DEFAULT_Y = 1740f
        private const val TOLERANCE = 5f
        private const val TARGET_X = 2048f
        private const val TARGET_Y = 3600f
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.server.input.InputManagerService", lpparam.classLoader
            )
            var hooked = false
            for (method in clazz.declaredMethods) {
                if (method.name != "interceptMotionBeforeQueueing" && method.name != "dispatchInputEvent") continue
                if (method.parameterTypes.none { MotionEvent::class.java.isAssignableFrom(it) }) continue
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val index = param.args.indexOfFirst { it is MotionEvent }
                            if (index < 0) return
                            val event = param.args[index] as MotionEvent
                            if (event.actionMasked != MotionEvent.ACTION_DOWN) return
                            if ((event.source and InputDevice.SOURCE_MOUSE) == 0) return
                            if (kotlin.math.abs(event.x - DEFAULT_X) > TOLERANCE ||
                                kotlin.math.abs(event.y - DEFAULT_Y) > TOLERANCE) return
                            val replacement = MotionEvent.obtain(event)
                            replacement.setLocation(TARGET_X, TARGET_Y)
                            param.args[index] = replacement
                            XposedBridge.log("BLE-M3: redirect (${event.x},${event.y}) -> ($TARGET_X,$TARGET_Y)")
                        } catch (t: Throwable) {
                            XposedBridge.log("BLE-M3: hook error: $t")
                        }
                    }
                })
                hooked = true
            }
            XposedBridge.log("BLE-M3: InputManagerService hooked=$hooked")
        } catch (t: Throwable) {
            XposedBridge.log("BLE-M3: unable to hook InputManagerService: $t")
        }
    }
}
