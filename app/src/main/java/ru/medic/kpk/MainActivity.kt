package ru.medic.kpk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** "tq" или "mark", пока ждём нажатия кнопки для назначения; иначе null. */
    var learning by mutableStateOf<String?>(null)

    /** Последняя нажатая аппаратная кнопка — чтобы узнать коды кнопок XCover. */
    var lastKey by mutableStateOf("—")

    private val handler = Handler(Looper.getMainLooper())
    private var pendingKey: Int? = null
    private val longPress = Runnable {
        val code = pendingKey
        pendingKey = null
        if (code != null) fire(code)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        TimerService.sync(this)
        setContent { AppRoot(this) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Repo.state.collect { applyWindow(it.settings) }
            }
        }
    }

    private fun applyWindow(s: Settings) {
        val lp = window.attributes
        lp.screenBrightness =
            if (s.nightMode) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Кнопки перехватываются здесь, до интерфейса. Срабатывает только удержание (~0,6 с),
     * чтобы случайное нажатие в кармане ничего не создавало.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            lastKey = keyName(code)
        }

        val target = learning
        if (target != null) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (code != KeyEvent.KEYCODE_BACK) {
                    Repo.setSettings { if (target == "tq") it.copy(tqKey = code) else it.copy(markKey = code) }
                    Haptics.ok(this)
                }
                learning = null
            }
            return true
        }

        val s = Repo.state.value.settings
        if (s.keysEnabled && (code == s.tqKey || code == s.markKey)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    pendingKey = code
                    handler.removeCallbacks(longPress)
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                }
                KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    pendingKey = null
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun fire(code: Int) {
        val s = Repo.state.value.settings
        when (code) {
            s.tqKey -> {
                Repo.quickTourniquet()
                TimerService.sync(this)
                Haptics.ok(this)
            }
            s.markKey -> {
                Repo.addCasualty()
                Haptics.ok(this)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(longPress)
        super.onDestroy()
    }

    private companion object {
        const val LONG_PRESS_MS = 600L
    }
}
