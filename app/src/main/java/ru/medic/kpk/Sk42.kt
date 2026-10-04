package ru.medic.kpk

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Прямоугольные координаты Гаусса–Крюгера в СК-42.
 * x — на север (метры от экватора), y — на восток с номером зоны впереди (7 412 345 → зона 7).
 */
data class Gk(val x: Double, val y: Double) {
    val zone: Int get() = floor(y / 1_000_000.0).toInt()
}

/**
 * Пересчёт WGS-84 (GPS) ⇄ СК-42.
 * Датум: семь параметров по ГОСТ 32453-2017 (СК-42 → WGS-84), запись «position vector», как towgs84 в PROJ.
 * Проекция: Гаусс–Крюгер на эллипсоиде Красовского, зоны по 6°, масштаб 1, ложное смещение 500 км.
 */
object Sk42 {
    private const val SEC = PI / 648000.0

    private const val WGS_A = 6378137.0
    private const val WGS_F = 1.0 / 298.257223563
    private const val KR_A = 6378245.0
    private const val KR_F = 1.0 / 298.3

    private const val TX = 23.57
    private const val TY = -140.95
    private const val TZ = -79.8
    private const val RX = 0.0
    private const val RY = 0.35 * SEC
    private const val RZ = 0.79 * SEC
    private const val DS = -0.22e-6

    private val N = KR_F / (2 - KR_F)
    private val A = KR_A / (1 + N) * (1 + N * N / 4 + N.pow(4) / 64)
    private val E = sqrt(KR_F * (2 - KR_F))

    private val ALPHA = doubleArrayOf(
        N / 2 - 2.0 / 3 * N * N + 5.0 / 16 * N.pow(3) + 41.0 / 180 * N.pow(4),
        13.0 / 48 * N * N - 3.0 / 5 * N.pow(3) + 557.0 / 1440 * N.pow(4),
        61.0 / 240 * N.pow(3) - 103.0 / 140 * N.pow(4),
        49561.0 / 161280 * N.pow(4),
    )
    private val BETA = doubleArrayOf(
        N / 2 - 2.0 / 3 * N * N + 37.0 / 96 * N.pow(3) - 1.0 / 360 * N.pow(4),
        1.0 / 48 * N * N + 1.0 / 15 * N.pow(3) - 437.0 / 1440 * N.pow(4),
        17.0 / 480 * N.pow(3) - 37.0 / 840 * N.pow(4),
        4397.0 / 161280 * N.pow(4),
    )
    private val DELTA = doubleArrayOf(
        2 * N - 2.0 / 3 * N * N - 2 * N.pow(3) + 116.0 / 45 * N.pow(4),
        7.0 / 3 * N * N - 8.0 / 5 * N.pow(3) - 227.0 / 45 * N.pow(4),
        56.0 / 15 * N.pow(3) - 136.0 / 35 * N.pow(4),
        4279.0 / 630 * N.pow(4),
    )

    /** WGS-84 (градусы) → прямоугольные СК-42. Зону можно задать, чтобы сетка не прыгала на стыке зон. */
    fun fromWgs(lat: Double, lon: Double, zone: Int? = null): Gk {
        val g = wgsToSk42Geo(lat, lon)
        return geoToGk(g[0], g[1], zone ?: zoneForLon(g[1]))
    }

    /** Прямоугольные СК-42 → WGS-84: [широта, долгота] в градусах. */
    fun toWgs(gk: Gk): DoubleArray {
        val g = gkToGeo(gk)
        return sk42GeoToWgs(g[0], g[1])
    }

    /** 6123456.7 → «6 123 457» (неразрывные пробелы). */
    fun format(v: Double): String {
        val s = v.roundToLong().toString()
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
            sb.append(ch)
        }
        return sb.toString()
    }

    fun zoneForLon(lon: Double): Int {
        val l = ((lon % 360.0) + 360.0) % 360.0
        return floor(l / 6.0).toInt() + 1
    }

    fun wgsToSk42Geo(lat: Double, lon: Double): DoubleArray {
        val p = ecef(lat, lon, WGS_A, WGS_F)
        val k = 1 + DS
        val x = (p[0] - TX) / k
        val y = (p[1] - TY) / k
        val z = (p[2] - TZ) / k
        val xs = x + RZ * y - RY * z
        val ys = -RZ * x + y + RX * z
        val zs = RY * x - RX * y + z
        return geodetic(xs, ys, zs, KR_A, KR_F)
    }

    fun sk42GeoToWgs(lat: Double, lon: Double): DoubleArray {
        val p = ecef(lat, lon, KR_A, KR_F)
        val k = 1 + DS
        val xt = TX + k * (p[0] - RZ * p[1] + RY * p[2])
        val yt = TY + k * (RZ * p[0] + p[1] - RX * p[2])
        val zt = TZ + k * (-RY * p[0] + RX * p[1] + p[2])
        return geodetic(xt, yt, zt, WGS_A, WGS_F)
    }

    fun geoToGk(lat: Double, lon: Double, zone: Int): Gk {
        val phi = Math.toRadians(lat)
        val dl = (((lon - (zone * 6 - 3)) + 540.0) % 360.0) - 180.0
        val lam = Math.toRadians(dl)
        val t = sinh(atanh(sin(phi)) - E * atanh(E * sin(phi)))
        val xiP = atan2(t, cos(lam))
        val etaP = atanh(sin(lam) / sqrt(1 + t * t))
        var xi = xiP
        var eta = etaP
        for (j in 1..4) {
            xi += ALPHA[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += ALPHA[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        return Gk(x = A * xi, y = zone * 1_000_000.0 + 500_000.0 + A * eta)
    }

    fun gkToGeo(gk: Gk): DoubleArray {
        val zone = gk.zone
        val xi = gk.x / A
        val eta = (gk.y - zone * 1_000_000.0 - 500_000.0) / A
        var xiP = xi
        var etaP = eta
        for (j in 1..4) {
            xiP -= BETA[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= BETA[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xiP) / cosh(etaP))
        var phi = chi
        for (j in 1..4) phi += DELTA[j - 1] * sin(2 * j * chi)
        val lam = atan2(sinh(etaP), cos(xiP))
        return doubleArrayOf(Math.toDegrees(phi), zone * 6 - 3 + Math.toDegrees(lam))
    }

    private fun ecef(latDeg: Double, lonDeg: Double, a: Double, f: Double): DoubleArray {
        val e2 = f * (2 - f)
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
        return doubleArrayOf(n * cos(lat) * cos(lon), n * cos(lat) * sin(lon), n * (1 - e2) * sin(lat))
    }

    private fun geodetic(x: Double, y: Double, z: Double, a: Double, f: Double): DoubleArray {
        val e2 = f * (2 - f)
        val lon = atan2(y, x)
        val p = sqrt(x * x + y * y)
        var lat = atan2(z, p * (1 - e2))
        var h = 0.0
        repeat(8) {
            val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
            h = p / cos(lat) - n
            lat = atan2(z, p * (1 - e2 * n / (n + h)))
        }
        return doubleArrayOf(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }
}
