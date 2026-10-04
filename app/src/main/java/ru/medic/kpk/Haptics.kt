package ru.medic.kpk

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Подтверждения и тревоги вибрацией: на запястье их чувствуешь, не глядя на экран. */
object Haptics {

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    private fun play(ctx: Context, timings: LongArray) {
        val v = vibrator(ctx)
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(timings, -1))
    }

    /** Одна короткая: принято. */
    fun ok(ctx: Context) = play(ctx, longArrayOf(0, 120))

    /** Две длинные: не понял, повтори. */
    fun error(ctx: Context) = play(ctx, longArrayOf(0, 400, 200, 400))

    /** Три средних: жгут перешёл порог предупреждения. */
    fun warn(ctx: Context) = play(ctx, longArrayOf(0, 300, 150, 300, 150, 300))

    /** Четыре длинных: жгут перешёл критический порог. */
    fun critical(ctx: Context) = play(ctx, longArrayOf(0, 800, 200, 800, 200, 800, 200, 800))
}
