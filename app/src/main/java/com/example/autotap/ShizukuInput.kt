package com.example.autotap

import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

// Ввод касаний через Shizuku (он запускается по беспроводной отладке).
object ShizukuInput {

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private var pendingDown = -1L
    private var pendingX = 0f
    private var pendingY = 0f

    fun hasBinder(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun prepare(): Boolean = isReady() && init()

    @Synchronized
    private fun init(): Boolean {
        if (injectMethod != null) return true
        return try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val m = im!!.javaClass.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            inputManager = im
            injectMethod = m
            true
        } catch (e: Throwable) {
            false
        }
    }

    // Палец опустился в точке (x, y). Возвращает время касания или -1 при ошибке.
    fun down(x: Float, y: Float): Long {
        val t = SystemClock.uptimeMillis()
        if (!inject(MotionEvent.ACTION_DOWN, x, y, t, t)) return -1L
        pendingDown = t
        pendingX = x
        pendingY = y
        return t
    }

    fun up(x: Float, y: Float, downTime: Long) {
        inject(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
        if (pendingDown == downTime) pendingDown = -1L
    }

    fun releaseIfDown() {
        if (pendingDown >= 0) up(pendingX, pendingY, pendingDown)
    }

    private fun inject(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long): Boolean {
        if (!init()) return false
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return try {
            (injectMethod!!.invoke(inputManager, ev, 0) as? Boolean) == true
        } catch (e: Throwable) {
            injectMethod = null
            inputManager = null
            false
        } finally {
            ev.recycle()
        }
    }
}
