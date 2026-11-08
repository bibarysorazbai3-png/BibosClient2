package com.example.autotap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class ClickerService : AccessibilityService() {

    companion object {
        var instance: ClickerService? = null
        const val PREFS = "autotap"
        const val KEY_CPS = "cps"
        const val KEY_SIZE = "size_dp"
        const val KEY_ALPHA = "alpha_pct"
        const val KEY_PRESS = "press_ms"
        const val MIN_CPS = 1
        const val MAX_CPS = 100
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var trigger: TriggerView? = null
    private var target: TargetView? = null
    private var bubble: BubbleView? = null
    private var panel: LinearLayout? = null
    private lateinit var triggerParams: WindowManager.LayoutParams
    private lateinit var targetParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var cpsValue: TextView
    private lateinit var pressValue: TextView
    private lateinit var sizeValue: TextView
    private lateinit var alphaValue: TextView
    private lateinit var modeLabel: TextView

    private var running = false
    private var hidden = false
    private var panelOpen = false
    private var cps = 10
    private var pressMs = 15
    private var sizeDp = 72
    private var alphaPct = 100
    private var nextTime = 0.0
    private var gesture: GestureDescription? = null
    private var tapX = 0f
    private var tapY = 0f
    private var useShizuku = false
    private var shizukuFailed = false
    private var touching = 0
    private var lastW = 0
    private var lastH = 0

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_CPS) {
            val v = prefs.getInt(KEY_CPS, 10).coerceIn(MIN_CPS, MAX_CPS)
            if (v != cps) applyCps(v, false)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (useShizuku) {
                shizukuTap()
            } else if (touching == 0) {
                // пока палец на нашем меню, клики не шлём, чтобы меню работало
                gesture?.let { dispatchGesture(it, null, null) }
            }
            nextTime += 1000.0 / cps
            val now = SystemClock.uptimeMillis().toDouble()
            if (nextTime < now) nextTime = now
            handler.postAtTime(this, nextTime.toLong())
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        cps = prefs.getInt(KEY_CPS, 10).coerceIn(MIN_CPS, MAX_CPS)
        pressMs = prefs.getInt(KEY_PRESS, 15).coerceIn(1, 50)
        sizeDp = prefs.getInt(KEY_SIZE, 72).coerceIn(32, 200)
        alphaPct = prefs.getInt(KEY_ALPHA, 100).coerceIn(20, 100)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        val (w, h) = screenSize()
        lastW = w
        lastH = h
        addTarget(w, h)
        addTrigger()
        addBubble(w)
        buildPanel()
        refreshLabels()
        updateAlphas()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        if (running) toggle()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ relayout() }, 250)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ShizukuInput.releaseIfDown()
        handler.removeCallbacksAndMessages(null)
        running = false
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        closePanel()
        trigger?.let { wm.removeView(it) }
        target?.let { wm.removeView(it) }
        bubble?.let { wm.removeView(it) }
        trigger = null
        target = null
        bubble = null
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- Настройки ----------

    private fun refreshLabels() {
        cpsValue.text = "$cps"
        pressValue.text = "$pressMs мс"
        sizeValue.text = "$sizeDp"
        alphaValue.text = "$alphaPct%"
        trigger?.label = cps.toString()
    }

    private fun applyCps(value: Int, save: Boolean = true) {
        cps = value.coerceIn(MIN_CPS, MAX_CPS)
        if (save) prefs.edit().putInt(KEY_CPS, cps).apply()
        refreshLabels()
        if (running) buildGesture()
    }

    private fun applyPress(value: Int) {
        pressMs = value.coerceIn(1, 50)
        prefs.edit().putInt(KEY_PRESS, pressMs).apply()
        refreshLabels()
        if (running) buildGesture()
    }

    private fun applySize(value: Int) {
        val newDp = value.coerceIn(32, 200)
        if (newDp == sizeDp) return
        val oldPx = triggerParams.width
        val newPx = dp(newDp)
        val cx = triggerParams.x + oldPx / 2
        val cy = triggerParams.y + oldPx / 2
        sizeDp = newDp
        triggerParams.width = newPx
        triggerParams.height = newPx
        triggerParams.x = cx - newPx / 2
        triggerParams.y = cy - newPx / 2
        trigger?.let { wm.updateViewLayout(it, triggerParams) }
        prefs.edit().putInt(KEY_SIZE, sizeDp).apply()
        refreshLabels()
    }

    private fun applyAlpha(value: Int) {
        alphaPct = value.coerceIn(20, 100)
        prefs.edit().putInt(KEY_ALPHA, alphaPct).apply()
        updateAlphas()
        refreshLabels()
    }

    private fun updateAlphas() {
        trigger?.alpha = alphaPct / 100f
        bubble?.alpha = (alphaPct / 100f) * (if (hidden) 0.5f else 1f)
    }

    // ---------- Запуск / остановка ----------

    private fun buildGesture() {
        val g = target ?: return
        val loc = IntArray(2)
        g.getLocationOnScreen(loc)
        tapX = loc[0] + g.width / 2f
        tapY = loc[1] + g.height / 2f
        val path = Path().apply { moveTo(tapX, tapY) }
        val interval = (1000.0 / cps).toLong()
        val dur = pressMs.toLong().coerceAtLeast(1L).coerceAtMost(maxOf(1L, interval - 2))
        gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
    }

    private fun toggle() {
        if (hidden) return
        running = !running
        trigger?.active = running
        if (running) {
            shizukuFailed = false
            useShizuku = ShizukuInput.prepare()
            buildGesture()
            nextTime = SystemClock.uptimeMillis().toDouble()
            applyTargetState()
            handler.post(tick)
        } else {
            handler.removeCallbacks(tick)
            applyTargetState()
        }
        refreshMode()
    }

    // Тап через Shizuku: касание вводится системой, как настоящее
    private fun shizukuTap() {
        val x = tapX
        val y = tapY
        val t = ShizukuInput.down(x, y)
        if (t < 0) {
            useShizuku = false
            shizukuFailed = true
            refreshMode()
            return
        }
        val interval = (1000.0 / cps).toLong()
        val hold = pressMs.toLong().coerceAtLeast(1L).coerceAtMost(maxOf(1L, interval - 2))
        handler.postDelayed({ ShizukuInput.up(x, y, t) }, hold)
    }

    private fun refreshMode() {
        modeLabel.text = when {
            running && useShizuku -> "Режим: Shizuku ✅"
            shizukuFailed -> "Shizuku: ошибка ввода. На Xiaomi включите «Отладка по USB (настройки безопасности)». Пока работаю через спец. возможности."
            ShizukuInput.prepare() -> "Режим: Shizuku ✅ (готов)"
            ShizukuInput.hasBinder() -> "Shizuku запущен, но нет доступа. Откройте Bibos Client и нажмите «Разрешить доступ Shizuku»."
            else -> "Режим: спец. возможности (Shizuku не запущен)"
        }
    }

    private fun hideAll() {
        if (running) toggle()
        hidden = true
        closePanel()
        applyTargetState()
        applyTriggerState()
        updateAlphas()
    }

    private fun showAll() {
        hidden = false
        applyTargetState()
        applyTriggerState()
        updateAlphas()
    }

    private fun onBubbleTap() {
        if (hidden) showAll() else if (panelOpen) closePanel() else openPanel()
    }

    // Цель кликов не должна перехватывать нажатия, пока кликер работает
    private fun applyTargetState() {
        val g = target ?: return
        val pass = running || hidden
        targetParams.flags = if (pass)
            targetParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else
            targetParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        g.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        g.alpha = if (running) 0.5f else 1f
        wm.updateViewLayout(g, targetParams)
    }

    private fun applyTriggerState() {
        val t = trigger ?: return
        triggerParams.flags = if (hidden)
            triggerParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else
            triggerParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        t.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        wm.updateViewLayout(t, triggerParams)
    }

    // ---------- Размер экрана и поворот ----------

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun relayout() {
        val t = trigger ?: return
        val g = target ?: return
        val b = bubble ?: return
        val (w, h) = screenSize()
        if (lastW == 0 || lastH == 0 || (w == lastW && h == lastH)) return
        closePanel()
        remap(triggerParams, w, h)
        remap(targetParams, w, h)
        remap(bubbleParams, w, h)
        wm.updateViewLayout(t, triggerParams)
        wm.updateViewLayout(g, targetParams)
        wm.updateViewLayout(b, bubbleParams)
        lastW = w
        lastH = h
        if (running) g.post { buildGesture() }
    }

    private fun remap(p: WindowManager.LayoutParams, w: Int, h: Int) {
        val fx = (p.x + p.width / 2f) / lastW
        val fy = (p.y + p.height / 2f) / lastH
        p.x = (fx * w - p.width / 2f).toInt().coerceIn(0, maxOf(0, w - p.width))
        p.y = (fy * h - p.height / 2f).toInt().coerceIn(0, maxOf(0, h - p.height))
    }

    // ---------- Окна ----------

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun addTarget(w: Int, h: Int) {
        val size = dp(44)
        val v = TargetView(this)
        targetParams = baseParams(size, size).apply {
            x = (w - size) / 2
            y = (h - size) / 2
        }
        v.setOnTouchListener(TapDragListener(targetParams) { })
        wm.addView(v, targetParams)
        target = v
    }

    private fun addTrigger() {
        val size = dp(sizeDp)
        val v = TriggerView(this)
        v.label = cps.toString()
        triggerParams = baseParams(size, size).apply {
            x = dp(16)
            y = dp(16)
        }
        v.setOnTouchListener(TapDragListener(triggerParams) { toggle() })
        wm.addView(v, triggerParams)
        trigger = v
    }

    private fun addBubble(w: Int) {
        val size = dp(44)
        val v = BubbleView(this)
        bubbleParams = baseParams(size, size).apply {
            x = (w - size) / 2
            y = dp(4)
        }
        v.setOnTouchListener(TapDragListener(bubbleParams) { onBubbleTap() })
        wm.addView(v, bubbleParams)
        bubble = v
    }

    private fun buildPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF2212121.toInt())
                cornerRadius = dp(20).toFloat()
            }
        }
        modeLabel = label("", 12f).apply {
            maxWidth = dp(280)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        root.addView(modeLabel)
        cpsValue = label("", 14f)
        pressValue = label("", 14f)
        sizeValue = label("", 14f)
        alphaValue = label("", 14f)
        root.addView(stepRow("Клики в секунду", cpsValue, { applyCps(cps - 1) }, { applyCps(cps + 1) }))
        root.addView(stepRow("Нажатие", pressValue, { applyPress(pressMs - 1) }, { applyPress(pressMs + 1) }))
        root.addView(stepRow("Размер кнопки", sizeValue, { applySize(sizeDp - 4) }, { applySize(sizeDp + 4) }))
        root.addView(stepRow("Прозрачность", alphaValue, { applyAlpha(alphaPct - 10) }, { applyAlpha(alphaPct + 10) }))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(pressButton("Скрыть", 15f, false) { hideAll() }.apply { minWidth = dp(96) })
        buttons.addView(pressButton("Закрыть", 15f, false) { closePanel() }.apply { minWidth = dp(96) })
        root.addView(buttons)

        panelParams = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        panel = root
    }

    private fun stepRow(
        title: String, value: TextView, minus: () -> Unit, plus: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = label(title, 13f).apply {
            minWidth = dp(120)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        value.minWidth = dp(56)
        row.addView(name)
        row.addView(pressButton("−", 24f, true, minus))
        row.addView(value)
        row.addView(pressButton("+", 24f, true, plus))
        return row
    }

    private fun openPanel() {
        val p = panel ?: return
        if (panelOpen) return
        refreshLabels()
        refreshMode()
        panelParams.x = bubbleParams.x
        panelParams.y = bubbleParams.y + bubbleParams.height + dp(6)
        wm.addView(p, panelParams)
        panelOpen = true
        p.post { positionPanel() }
    }

    private fun positionPanel() {
        val p = panel ?: return
        if (!panelOpen) return
        val (w, h) = screenSize()
        val pw = p.width
        val ph = p.height
        val x = bubbleParams.x + bubbleParams.width / 2 - pw / 2
        var y = bubbleParams.y + bubbleParams.height + dp(6)
        if (y + ph > h) y = bubbleParams.y - ph - dp(6)
        panelParams.x = x.coerceIn(0, maxOf(0, w - pw))
        panelParams.y = y.coerceIn(0, maxOf(0, h - ph))
        wm.updateViewLayout(p, panelParams)
    }

    private fun closePanel() {
        val p = panel ?: return
        if (!panelOpen) return
        wm.removeView(p)
        panelOpen = false
    }

    // ---------- Касания ----------

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(40)
    }

    // Пока палец на нашем меню или кнопке, автоклики стоят на паузе
    private fun guard(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching += 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                handler.postDelayed({
                    if (touching > 0) {
                        touching -= 1
                    }
                }, 250)
        }
    }

    // Кнопка срабатывает сразу при касании; repeat: при удержании листает быстро
    private fun pressButton(
        text: String, size: Float, repeat: Boolean, action: () -> Unit
    ) = label(text, size).apply {
        val rep = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 80)
            }
        }
        setOnTouchListener { _, e ->
            guard(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    if (repeat) handler.postDelayed(rep, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    handler.removeCallbacks(rep)
            }
            true
        }
    }

    // Короткое касание: onTap. Сдвиг пальца: перетаскивание окна
    private inner class TapDragListener(
        private val p: WindowManager.LayoutParams,
        private val onTap: () -> Unit
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private val slop = ViewConfiguration.get(this@ClickerService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            guard(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        val (w, h) = screenSize()
                        p.x = (startX + dx.toInt()).coerceIn(0, maxOf(0, w - v.width))
                        p.y = (startY + dy.toInt()).coerceIn(0, maxOf(0, h - v.height))
                        wm.updateViewLayout(v, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                }
            }
            return true
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- Рисование ----------

    // Круглая кнопка запуска: нажали, и клики идут; нажали ещё раз, и стоп
    private class TriggerView(c: Context) : View(c) {
        var label: String = ""
            set(value) {
                field = value
                invalidate()
            }
