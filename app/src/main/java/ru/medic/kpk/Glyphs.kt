package ru.medic.kpk

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Значки рисуются примитивами: без шрифтов и библиотек, чтобы не раздувать APK. */
enum class Glyph {
    CASUALTY, TOURNIQUET, WOUND, MAP, SETTINGS, DELETE, REMOVE, LOCATE,
    ZOOM_IN, ZOOM_OUT, GRID, PIN, BACK, LAYERS,
    GUNSHOT, FRAGMENT, BLAST, BURN, FRACTURE, AMPUTATION, TBI, OTHER,
    NOTE, PRESSURE, PACKING, HEMOSTATIC, CHEST_SEAL, DECOMPRESSION, AIRWAY, CRIC,
    ANALGESIA, TXA, INFUSION, SPLINT, WARMING,
}

val Procedure.glyph: Glyph
    get() = when (this) {
        Procedure.PRESSURE -> Glyph.PRESSURE
        Procedure.PACKING -> Glyph.PACKING
        Procedure.HEMOSTATIC -> Glyph.HEMOSTATIC
        Procedure.CHEST_SEAL -> Glyph.CHEST_SEAL
        Procedure.DECOMPRESSION -> Glyph.DECOMPRESSION
        Procedure.AIRWAY -> Glyph.AIRWAY
        Procedure.CRIC -> Glyph.CRIC
        Procedure.ANALGESIA -> Glyph.ANALGESIA
        Procedure.TXA -> Glyph.TXA
        Procedure.INFUSION -> Glyph.INFUSION
        Procedure.SPLINT -> Glyph.SPLINT
        Procedure.WARMING -> Glyph.WARMING
    }

val InjuryType.glyph: Glyph
    get() = when (this) {
        InjuryType.GUNSHOT -> Glyph.GUNSHOT
        InjuryType.FRAGMENT -> Glyph.FRAGMENT
        InjuryType.BLAST -> Glyph.BLAST
        InjuryType.BURN -> Glyph.BURN
        InjuryType.FRACTURE -> Glyph.FRACTURE
        InjuryType.AMPUTATION -> Glyph.AMPUTATION
        InjuryType.TBI -> Glyph.TBI
        InjuryType.OTHER -> Glyph.OTHER
    }

/** Значок целиком становится полупрозрачным через слой, чтобы пересечения линий не темнели. */
@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier, color: Color, alpha: Float = 0.45f) {
    Canvas(modifier.alpha(alpha)) { Pen(this, color).draw(glyph) }
}

/** Схема тела с подсвеченной зоной. Раненый смотрит на тебя: его правая сторона слева. */
@Composable
fun BodyGlyph(region: Region?, modifier: Modifier, color: Color, alpha: Float = 0.5f) {
    Canvas(modifier.alpha(alpha)) {
        val dim = Pen(this, color.copy(alpha = 0.35f), 0.035f)
        val hot = Pen(this, color, 0.035f)
        fun pen(r: Region) = if (r == region) hot else dim
        fun on(r: Region) = r == region

        pen(Region.HEAD).circle(0.5f, 0.1f, 0.07f, fill = on(Region.HEAD))
        pen(Region.NECK).rect(0.465f, 0.17f, 0.535f, 0.225f, fill = on(Region.NECK))
        pen(Region.CHEST).rect(0.37f, 0.23f, 0.63f, 0.41f, fill = on(Region.CHEST))
        pen(Region.ABDOMEN).rect(0.38f, 0.41f, 0.62f, 0.53f, fill = on(Region.ABDOMEN))
        pen(Region.PELVIS).rect(0.38f, 0.53f, 0.62f, 0.61f, fill = on(Region.PELVIS))

        val limbW = hot.s * 0.07f
        pen(Region.RIGHT_ARM).line(0.34f, 0.26f, 0.24f, 0.58f, if (on(Region.RIGHT_ARM)) limbW * 1.5f else limbW)
        pen(Region.LEFT_ARM).line(0.66f, 0.26f, 0.76f, 0.58f, if (on(Region.LEFT_ARM)) limbW * 1.5f else limbW)
        pen(Region.RIGHT_LEG).line(0.44f, 0.63f, 0.41f, 0.96f, if (on(Region.RIGHT_LEG)) limbW * 1.5f else limbW)
        pen(Region.LEFT_LEG).line(0.56f, 0.63f, 0.59f, 0.96f, if (on(Region.LEFT_LEG)) limbW * 1.5f else limbW)

        if (region == Region.BACK) {
            val a = hot.p(0.37f, 0.23f)
            val b = hot.p(0.63f, 0.61f)
            clipRect(a.x, a.y, b.x, b.y) {
                var x = 0.1f
                while (x < 0.95f) {
                    hot.line(x, 0.61f, x + 0.38f, 0.23f)
                    x += 0.06f
                }
            }
            hot.rect(0.37f, 0.23f, 0.63f, 0.61f)
        }
    }
}

/** Рисует в квадрате 1×1, вписанном в область по центру. */
private class Pen(val scope: DrawScope, val color: Color, strokeK: Float = 0.08f) {
    val s = min(scope.size.width, scope.size.height)
    val ox = (scope.size.width - s) / 2f
    val oy = (scope.size.height - s) / 2f
    val sw = s * strokeK

    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = sw) =
        scope.drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = w, cap = StrokeCap.Round)

    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        if (fill) scope.drawCircle(color, r * s, p(x, y))
        else scope.drawCircle(color, r * s, p(x, y), style = Stroke(sw))

    fun rect(x1: Float, y1: Float, x2: Float, y2: Float, fill: Boolean = false) =
        scope.drawRect(
            color,
            topLeft = p(x1, y1),
            size = Size((x2 - x1) * s, (y2 - y1) * s),
            style = if (fill) Fill else Stroke(sw, join = StrokeJoin.Round),
        )

    fun poly(vararg pts: Float, fill: Boolean = false, close: Boolean = true) {
        val path = Path()
        path.moveTo(ox + pts[0] * s, oy + pts[1] * s)
        var i = 2
        while (i + 1 < pts.size) {
            path.lineTo(ox + pts[i] * s, oy + pts[i + 1] * s)
            i += 2
        }
        if (close) path.close()
        scope.drawPath(
            path, color,
            style = if (fill) Fill else Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }

    /** Капля: контур для ожога и гемостатика. */
    fun drop() {
        val path = Path()
        path.moveTo(ox + 0.5f * s, oy + 0.06f * s)
        path.cubicTo(ox + 0.86f * s, oy + 0.42f * s, ox + 0.84f * s, oy + 0.92f * s, ox + 0.5f * s, oy + 0.92f * s)
        path.cubicTo(ox + 0.16f * s, oy + 0.92f * s, ox + 0.14f * s, oy + 0.42f * s, ox + 0.5f * s, oy + 0.06f * s)
        path.close()
        scope.drawPath(path, color, style = Stroke(sw, join = StrokeJoin.Round))
    }

    fun rays(cx: Float, cy: Float, r1: Float, r2: Float, count: Int, phase: Float = 0f) {
        for (k in 0 until count) {
            val a = phase + k * 2 * PI.toFloat() / count
            line(cx + r1 * cos(a), cy + r1 * sin(a), cx + r2 * cos(a), cy + r2 * sin(a))
        }
    }

    fun draw(glyph: Glyph) {
        when (glyph) {
            Glyph.CASUALTY -> {
                circle(0.5f, 0.3f, 0.15f)
                scope.drawArc(
                    color, 180f, 180f, false,
                    topLeft = p(0.18f, 0.52f), size = Size(0.64f * s, 0.76f * s),
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
            Glyph.TOURNIQUET -> {
                rect(0.08f, 0.42f, 0.92f, 0.62f)
                line(0.3f, 0.2f, 0.7f, 0.84f, sw * 1.4f)
                rect(0.74f, 0.28f, 0.9f, 0.42f, fill = true)
            }
            Glyph.WOUND -> {
                rays(0.5f, 0.5f, 0.16f, 0.44f, 8)
                circle(0.5f, 0.5f, 0.07f, fill = true)
            }
            Glyph.MAP -> {
                poly(0.1f, 0.2f, 0.37f, 0.12f, 0.63f, 0.2f, 0.9f, 0.12f, 0.9f, 0.8f, 0.63f, 0.88f, 0.37f, 0.8f, 0.1f, 0.88f)
                line(0.37f, 0.12f, 0.37f, 0.8f)
                line(0.63f, 0.2f, 0.63f, 0.88f)
            }
            Glyph.SETTINGS -> {
                circle(0.5f, 0.5f, 0.14f)
                circle(0.5f, 0.5f, 0.3f)
                rays(0.5f, 0.5f, 0.3f, 0.44f, 8, phase = (PI / 8).toFloat())
            }
            Glyph.DELETE -> {
                rect(0.26f, 0.3f, 0.74f, 0.9f)
                line(0.14f, 0.3f, 0.86f, 0.3f)
                poly(0.4f, 0.3f, 0.4f, 0.16f, 0.6f, 0.16f, 0.6f, 0.3f, close = false)
                line(0.42f, 0.45f, 0.42f, 0.76f)
                line(0.58f, 0.45f, 0.58f, 0.76f)
            }
            Glyph.REMOVE -> {
                circle(0.3f, 0.76f, 0.12f)
                circle(0.7f, 0.76f, 0.12f)
                line(0.38f, 0.67f, 0.76f, 0.1f)
                line(0.62f, 0.67f, 0.24f, 0.1f)
            }
            Glyph.LOCATE -> {
                circle(0.5f, 0.5f, 0.28f)
                circle(0.5f, 0.5f, 0.08f, fill = true)
                line(0.5f, 0.04f, 0.5f, 0.2f)
                line(0.5f, 0.8f, 0.5f, 0.96f)
                line(0.04f, 0.5f, 0.2f, 0.5f)
                line(0.8f, 0.5f, 0.96f, 0.5f)
            }
            Glyph.ZOOM_IN -> {
                line(0.5f, 0.15f, 0.5f, 0.85f, sw * 1.4f)
                line(0.15f, 0.5f, 0.85f, 0.5f, sw * 1.4f)
            }
            Glyph.ZOOM_OUT -> line(0.15f, 0.5f, 0.85f, 0.5f, sw * 1.4f)
            Glyph.GRID -> {
                rect(0.1f, 0.1f, 0.9f, 0.9f)
                line(0.37f, 0.1f, 0.37f, 0.9f)
                line(0.63f, 0.1f, 0.63f, 0.9f)
                line(0.1f, 0.37f, 0.9f, 0.37f)
                line(0.1f, 0.63f, 0.9f, 0.63f)
            }
            Glyph.PIN -> {
                circle(0.5f, 0.38f, 0.22f)
                circle(0.5f, 0.38f, 0.07f, fill = true)
                poly(0.31f, 0.5f, 0.5f, 0.94f, 0.69f, 0.5f, close = false)
            }
            Glyph.BACK -> {
                line(0.82f, 0.5f, 0.18f, 0.5f)
                line(0.18f, 0.5f, 0.44f, 0.24f)
                line(0.18f, 0.5f, 0.44f, 0.76f)
            }
            Glyph.LAYERS -> {
                poly(0.5f, 0.12f, 0.9f, 0.34f, 0.5f, 0.56f, 0.1f, 0.34f)
                poly(0.1f, 0.52f, 0.5f, 0.74f, 0.9f, 0.52f, close = false)
                poly(0.1f, 0.68f, 0.5f, 0.9f, 0.9f, 0.68f, close = false)
            }
            Glyph.GUNSHOT -> {
                val path = Path()
                path.moveTo(ox + 0.36f * s, oy + 0.86f * s)
                path.lineTo(ox + 0.36f * s, oy + 0.42f * s)
                path.cubicTo(ox + 0.36f * s, oy + 0.22f * s, ox + 0.45f * s, oy + 0.12f * s, ox + 0.5f * s, oy + 0.08f * s)
                path.cubicTo(ox + 0.55f * s, oy + 0.12f * s, ox + 0.64f * s, oy + 0.22f * s, ox + 0.64f * s, oy + 0.42f * s)
                path.lineTo(ox + 0.64f * s, oy + 0.86f * s)
                path.close()
                scope.drawPath(path, color, style = Stroke(sw, join = StrokeJoin.Round))
                line(0.36f, 0.66f, 0.64f, 0.66f)
            }
            Glyph.FRAGMENT -> poly(
                0.2f, 0.3f, 0.55f, 0.12f, 0.86f, 0.38f, 0.7f, 0.54f, 0.82f, 0.86f, 0.42f, 0.74f, 0.14f, 0.86f, 0.3f, 0.56f,
            )
            Glyph.BLAST -> {
                val pts = FloatArray(32)
                for (k in 0 until 16) {
                    val a = k * PI.toFloat() / 8 - PI.toFloat() / 2
                    val r = if (k % 2 == 0) 0.45f else 0.22f
                    pts[2 * k] = 0.5f + r * cos(a)
                    pts[2 * k + 1] = 0.52f + r * sin(a)
                }
                poly(*pts)
            }
            Glyph.BURN -> {
                drop()
                circle(0.5f, 0.7f, 0.1f, fill = true)
            }
            Glyph.FRACTURE -> {
                line(0.22f, 0.78f, 0.44f, 0.56f, sw * 1.5f)
                line(0.56f, 0.44f, 0.78f, 0.22f, sw * 1.5f)
                poly(0.44f, 0.56f, 0.53f, 0.55f, 0.47f, 0.47f, 0.56f, 0.44f, close = false)
                circle(0.16f, 0.76f, 0.07f, fill = true)
                circle(0.24f, 0.84f, 0.07f, fill = true)
                circle(0.76f, 0.16f, 0.07f, fill = true)
                circle(0.84f, 0.24f, 0.07f, fill = true)
            }
            Glyph.AMPUTATION -> {
                line(0.5f, 0.08f, 0.5f, 0.52f, sw * 2f)
                line(0.18f, 0.66f, 0.3f, 0.66f)
                line(0.42f, 0.66f, 0.58f, 0.66f)
                line(0.7f, 0.66f, 0.82f, 0.66f)
            }
            Glyph.TBI -> {
                circle(0.5f, 0.48f, 0.34f)
                poly(0.46f, 0.24f, 0.58f, 0.44f, 0.44f, 0.52f, 0.56f, 0.72f, close = false)
            }
            Glyph.NOTE -> {
                rect(0.22f, 0.1f, 0.78f, 0.9f)
                line(0.32f, 0.32f, 0.68f, 0.32f)
                line(0.32f, 0.5f, 0.68f, 0.5f)
                line(0.32f, 0.68f, 0.56f, 0.68f)
            }
            Glyph.PRESSURE -> {
                rect(0.38f, 0.56f, 0.92f, 0.74f)
                circle(0.36f, 0.44f, 0.24f)
                circle(0.36f, 0.44f, 0.07f, fill = true)
            }
            Glyph.PACKING -> {
                circle(0.5f, 0.66f, 0.22f)
                poly(0.28f, 0.1f, 0.72f, 0.2f, 0.28f, 0.3f, 0.72f, 0.4f, 0.5f, 0.56f, close = false)
            }
            Glyph.HEMOSTATIC -> {
                drop()
                line(0.5f, 0.5f, 0.5f, 0.78f)
                line(0.36f, 0.64f, 0.64f, 0.64f)
            }
            Glyph.CHEST_SEAL -> {
                rect(0.14f, 0.14f, 0.86f, 0.86f)
                circle(0.5f, 0.5f, 0.17f)
                circle(0.5f, 0.5f, 0.05f, fill = true)
            }
            Glyph.DECOMPRESSION -> {
                line(0.22f, 0.78f, 0.6f, 0.4f, sw * 2.2f)
                line(0.6f, 0.4f, 0.9f, 0.1f, sw * 0.7f)
                line(0.1f, 0.7f, 0.3f, 0.9f)
            }
            Glyph.AIRWAY -> {
                val path = Path()
                path.moveTo(ox + 0.3f * s, oy + 0.14f * s)
                path.cubicTo(ox + 0.3f * s, oy + 0.62f * s, ox + 0.5f * s, oy + 0.86f * s, ox + 0.88f * s, oy + 0.86f * s)
                scope.drawPath(path, color, style = Stroke(sw * 1.6f, cap = StrokeCap.Round))
                line(0.14f, 0.14f, 0.46f, 0.14f)
            }
            Glyph.CRIC -> {
                line(0.14f, 0.86f, 0.52f, 0.48f, sw * 1.7f)
                poly(0.52f, 0.48f, 0.88f, 0.12f, 0.7f, 0.52f, fill = true)
            }
            Glyph.ANALGESIA -> {
                rect(0.22f, 0.38f, 0.72f, 0.62f)
                line(0.08f, 0.5f, 0.22f, 0.5f)
                line(0.08f, 0.36f, 0.08f, 0.64f)
                line(0.72f, 0.5f, 0.95f, 0.5f, sw * 0.7f)
                line(0.38f, 0.38f, 0.38f, 0.48f)
                line(0.54f, 0.38f, 0.54f, 0.48f)
            }
            Glyph.TXA -> {
                rect(0.3f, 0.32f, 0.7f, 0.9f)
                rect(0.36f, 0.12f, 0.64f, 0.3f, fill = true)
                line(0.3f, 0.58f, 0.7f, 0.58f)
            }
            Glyph.INFUSION -> {
                rect(0.26f, 0.08f, 0.74f, 0.52f)
                rect(0.43f, 0.6f, 0.57f, 0.74f)
                line(0.5f, 0.52f, 0.5f, 0.6f)
                line(0.5f, 0.74f, 0.5f, 0.94f)
            }
            Glyph.SPLINT -> {
                line(0.36f, 0.08f, 0.36f, 0.92f, sw * 1.4f)
                line(0.64f, 0.08f, 0.64f, 0.92f, sw * 1.4f)
                line(0.24f, 0.3f, 0.76f, 0.3f)
                line(0.24f, 0.7f, 0.76f, 0.7f)
            }
            Glyph.WARMING -> {
                for (x in floatArrayOf(0.28f, 0.5f, 0.72f)) {
                    poly(x, 0.9f, x - 0.08f, 0.76f, x + 0.08f, 0.62f, x - 0.08f, 0.48f, x + 0.08f, 0.34f, x, 0.18f, close = false)
                }
            }
            Glyph.OTHER -> {
                circle(0.5f, 0.5f, 0.38f)
                circle(0.32f, 0.5f, 0.05f, fill = true)
                circle(0.5f, 0.5f, 0.05f, fill = true)
                circle(0.68f, 0.5f, 0.05f, fill = true)
            }
        }
    }
}
