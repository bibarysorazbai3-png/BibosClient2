package com.example.autotap

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var shizukuInfo: TextView
    private lateinit var cpsInput: EditText

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { refreshStatus() }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus() }
    }
    private val deadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val prefs = getSharedPreferences(ClickerService.PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Bibos Client"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        })

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "Скорость (кликов в секунду, ${ClickerService.MIN_CPS}–${ClickerService.MAX_CPS}):"
            textSize = 15f
        })

        cpsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt(ClickerService.KEY_CPS, 10).toString())
        }
        root.addView(cpsInput)

        addButton(root, "Применить КПС") {
            val v = cpsInput.text.toString().toIntOrNull()
                ?.coerceIn(ClickerService.MIN_CPS, ClickerService.MAX_CPS)
            if (v == null) {
                toast("Введите число")
            } else {
                prefs.edit().putInt(ClickerService.KEY_CPS, v).apply()
                cpsInput.setText(v.toString())
                toast("Установлено: $v КПС")
            }
        }

        root.addView(TextView(this).apply {
            text = "\n1. Включите Bibos Client в «Специальных возможностях».\n\n" +
                "2. Появятся кнопка-триггер, красный прицел и кружок «B» (меню). " +
                "Перетащите прицел туда, куда нужно кликать. " +
                "Нажмите на триггер, чтобы начать, и ещё раз, чтобы остановить."
            textSize = 15f
        })

        addButton(root, "Открыть специальные возможности") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        root.addView(TextView(this).apply {
            text = "\nРежим Shizuku (вход по отладке Wi-Fi, как в Panda Touch Pro)"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "1. Установите Shizuku из Google Play.\n" +
                "2. В настройках разработчика включите «Отладка по Wi-Fi». " +
                "В Shizuku нажмите «Сопряжение», затем «Запустить».\n" +
                "3. Нажмите «Разрешить доступ Shizuku» ниже.\n" +
                "На Xiaomi ещё включите «Отладка по USB (настройки безопасности)»."
            textSize = 14f
        })

        shizukuInfo = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(shizukuInfo)

        addButton(root, "Настройки разработчика") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                toast("Не удалось открыть настройки разработчика")
            }
        }
        addButton(root, "Открыть Shizuku") { openShizuku() }
        addButton(root, "Разрешить доступ Shizuku") { requestShizuku() }

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = if (ClickerService.instance != null)
            "Сервис включён ✅" else "Сервис выключен ❌"
        shizukuInfo.text = try {
            if (!Shizuku.pingBinder()) {
                "Shizuku не запущен ❌"
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "Shizuku готов ✅"
            } else {
                "Shizuku запущен, нужен доступ ⚠️"
            }
        } catch (e: Throwable) {
            "Shizuku не найден ❌"
        }
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Сначала запустите Shizuku")
                openShizuku()
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("Доступ уже есть")
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("Доступ отклонён: разрешите его в приложении Shizuku")
            } else {
                Shizuku.requestPermission(100)
            }
        } catch (e: Throwable) {
            toast("Shizuku недоступен")
        }
    }

    private fun openShizuku() {
        val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        if (i != null) {
            startActivity(i)
        } else {
            toast("Shizuku не установлен: поставьте его из Google Play")
        }
    }

    private fun addButton(root: LinearLayout, title: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = title
            setOnClickListener { onClick() }
        })
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
