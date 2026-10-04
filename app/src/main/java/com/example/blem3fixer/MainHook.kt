package com.example.blem3fixer

import android.content.res.Resources
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.view.InputEvent
import android.view.MotionEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.abs

/**
 * Redirects BLE-M3's fixed absolute-position click in system_server.
 *
 * The hardware reports coordinates in 0..4095, whereas MotionEvent coordinates are display
 * pixels. Therefore both matching and the target are calculated as fractions of the display.
 */
class MainHook : IXposedHookLoadPackage {
    companion object {
        private const val DEVICE_MAX = 4095f
        private const val DEFAULT_X = 1012f
        private const val DEFAULT_Y = 1740f
        private const val TARGET_X = 2048f
        private const val TARGET_Y = 3600f
        private const val NORMALIZED_TOLERANCE = 0.008f
        @Volatile private var installed = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        try {
            val serviceClass = XposedHelpers.findClass(
                "com.android.server.input.InputManagerService", lpparam.classLoader
            )
            XposedBridge.hookAllConstructors(serviceClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // InputManagerService is still being initialized in its constructor. Wait until
                    // the system-server main loop can safely enable the input filter.
                    Handler(Looper.getMainLooper()).postDelayed({
                        installFilter(param.thisObject, lpparam.classLoader)
                    }, 2_000L)
                }
            })
            XposedBridge.log("BLE-M3: waiting to install InputManagerService input filter")
        } catch (t: Throwable) {
            XposedBridge.log("BLE-M3: unable to hook InputManagerService constructor: $t")
        }
    }

    private fun installFilter(service: Any, loader: ClassLoader) {
        if (installed) return
        try {
            val existing = XposedHelpers.getObjectField(service, "mInputFilter")
            if (existing != null) {
                XposedBridge.log("BLE-M3: another system input filter is active; refusing to replace it")
                return
            }

            val filterInterface = Class.forName("android.view.IInputFilter", false, loader)
            val handler = BleInputFilterHandler()
            val filter = Proxy.newProxyInstance(loader, arrayOf(filterInterface), handler)
            val setInputFilter = service.javaClass.getDeclaredMethod("setInputFilter", filterInterface)
            setInputFilter.isAccessible = true
            setInputFilter.invoke(service, filter)
            installed = true
            XposedBridge.log("BLE-M3: system input filter installed")
        } catch (t: Throwable) {
            XposedBridge.log("BLE-M3: unable to install input filter: $t")
        }
    }

    private class BleInputFilterHandler : InvocationHandler {
        private val binder = Binder()
        @Volatile private var host: Any? = null

        override fun invoke(proxy: Any, method: Method, args: Array<Any?>?): Any? = when (method.name) {
            "asBinder" -> binder
            "install" -> {
                host = args?.getOrNull(0)
                XposedBridge.log("BLE-M3: input filter connected")
                null
            }
            "uninstall" -> {
                host = null
                XposedBridge.log("BLE-M3: input filter disconnected")
                null
            }
            "filterInputEvent" -> {
                val event = args?.getOrNull(0) as? InputEvent
                val policyFlags = args?.getOrNull(1) as? Int ?: 0
                forward(event, policyFlags)
                null
            }
            "toString" -> "BleM3InputFilter"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> null
        }

        private fun forward(event: InputEvent?, policyFlags: Int) {
            if (event == null) return
            try {
                val output = transform(event)
                val currentHost = host ?: return
                val send = currentHost.javaClass.getMethod(
                    "sendInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType!!
                )
                send.isAccessible = true
                send.invoke(currentHost, output, policyFlags)
            } catch (t: Throwable) {
                // Never swallow a real input event because the redirect feature failed.
                XposedBridge.log("BLE-M3: forwarding error: $t")
                try {
                    val currentHost = host ?: return
                    val send = currentHost.javaClass.getMethod(
                        "sendInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType!!
                    )
                    send.isAccessible = true
                    send.invoke(currentHost, event, policyFlags)
                } catch (ignored: Throwable) {
                    XposedBridge.log("BLE-M3: fallback forwarding failed: $ignored")
                }
            }
        }

        private fun transform(event: InputEvent): InputEvent {
            val motion = event as? MotionEvent ?: return event
            if (motion.actionMasked != MotionEvent.ACTION_DOWN) return event

            val metrics = Resources.getSystem().displayMetrics
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()
            if (width <= 0f || height <= 0f) return event

            val normalizedX = motion.x / width
            val normalizedY = motion.y / height
            val defaultX = DEFAULT_X / DEVICE_MAX
            val defaultY = DEFAULT_Y / DEVICE_MAX
            if (abs(normalizedX - defaultX) > NORMALIZED_TOLERANCE ||
                abs(normalizedY - defaultY) > NORMALIZED_TOLERANCE) return event

            val replacement = MotionEvent.obtain(motion)
            replacement.setLocation(TARGET_X / DEVICE_MAX * width, TARGET_Y / DEVICE_MAX * height)
            XposedBridge.log(
                "BLE-M3: redirect (${motion.x},${motion.y}) -> " +
                    "(${replacement.x},${replacement.y}), source=${motion.source}"
            )
            return replacement
        }
    }
}
