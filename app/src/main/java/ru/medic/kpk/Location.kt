package ru.medic.kpk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** GPS напрямую через LocationManager: без сервисов Google и без интернета. Слушает, пока приложение на экране. */
object LocationRepo {
    private val _fix = MutableStateFlow<Location?>(null)
    val fix: StateFlow<Location?> = _fix

    private var manager: LocationManager? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _fix.value = location
        }

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (manager != null) return
        val granted = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        manager = m
        runCatching { m.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?.let { if (_fix.value == null) _fix.value = it }
        runCatching {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
        }
    }

    fun stop() {
        manager?.removeUpdates(listener)
        manager = null
    }

    /** Последняя отметка, если она не старше [maxAgeMs]. */
    fun freshFix(maxAgeMs: Long = 5 * 60_000L): Location? {
        val f = _fix.value ?: return null
        return if (System.currentTimeMillis() - f.time <= maxAgeMs) f else null
    }
}
