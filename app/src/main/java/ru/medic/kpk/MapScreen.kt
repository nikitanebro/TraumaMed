package ru.medic.kpk

import android.graphics.BitmapFactory
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sinh

private const val TILE_DP = 256f

/** Нормированные координаты Web Mercator: x, y ∈ [0, 1], y растёт вниз. */
private fun lonToX(lon: Double) = (lon + 180.0) / 360.0
private fun latToY(lat: Double): Double {
    val s = sin(Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878)))
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}
private fun xToLon(x: Double) = x * 360.0 - 180.0
private fun yToLat(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

private fun tilePx(density: Float) = TILE_DP * min(density, 2f)

/** Текущий вид карты: центр, масштаб и размер экрана. Пересчитывает точки в обе стороны. */
private class View(val cx: Double, val cy: Double, val zoom: Double, val w: Float, val h: Float, density: Float) {
    val worldPx = tilePx(density) * 2.0.pow(zoom)

    fun toScreen(lat: Double, lon: Double): Offset {
        var dx = lonToX(lon) - cx
        if (dx > 0.5) dx -= 1.0
        if (dx < -0.5) dx += 1.0
        return Offset((w / 2 + dx * worldPx).toFloat(), (h / 2 + (latToY(lat) - cy) * worldPx).toFloat())
    }

    fun toLatLon(x: Float, y: Float): DoubleArray =
        doubleArrayOf(yToLat(cy + (y - h / 2) / worldPx), xToLon(cx + (x - w / 2) / worldPx))
}

/** Кэш тайлов: читает из SQLite в отдельном потоке, отдаёт готовые картинки в отрисовку. */
private class TileCache(private val source: TileSource) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<Long, ImageBitmap>(96)
    private val pending = HashSet<Long>()
    private val missing = HashSet<Long>()
    var version by mutableIntStateOf(0)

    @Volatile
    private var closed = false

    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val key = (z.toLong() shl 52) or (x.toLong() shl 26) or y.toLong()
        cache.get(key)?.let { return it }
        if (closed || key in pending || key in missing) return null
        pending.add(key)
        executor.execute {
            val bmp = if (closed) null else runCatching {
                source.tile(z, x, y)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
            }.getOrNull()
            main.post {
                pending.remove(key)
                if (bmp != null) cache.put(key, bmp) else missing.add(key)
                version++
            }
        }
        return null
    }

    fun close() {
        closed = true
        executor.execute { runCatching { source.close() } }
        executor.shutdown()
    }
}

private val GRID_STEPS = doubleArrayOf(100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0, 50000.0, 100000.0)

/** Подпись линии сетки как на топокарте: две последние цифры километра. */
private fun gridLabel(v: Double, step: Double): String {
    val m = v.roundToLong()
    val km = (m / 1000) % 100
    return if (step >= 1000) String.format(Locale.US, "%02d", km)
    else String.format(Locale.US, "%02d.%d", km, (m % 1000) / 100)
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun MapScreen(state: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = state.settings
    val fix by LocationRepo.fix.collectAsState()

    val source = remember(s.activeMap) {
        s.activeMap?.let { p -> runCatching { TileSource.open(File(p)) }.getOrNull() }
    }
    val cache = remember(source) { source?.let { TileCache(it) } }
    DisposableEffect(cache) { onDispose { cache?.close() } }

    val minZoom = max(1.0, (source?.minZoom?.toDouble() ?: 3.0) - 1.0)
    val maxZoom = (source?.maxZoom?.toDouble() ?: 17.0) + 3.0

    var cx by remember { mutableDoubleStateOf(lonToX(s.mapLon)) }
    var cy by remember { mutableDoubleStateOf(latToY(s.mapLat)) }
    var zoom by remember { mutableDoubleStateOf(s.mapZoom) }

    DisposableEffect(Unit) {
        onDispose {
            Repo.setSettings { it.copy(mapLat = yToLat(cy), mapLon = xToLon(cx), mapZoom = zoom) }
        }
    }

    var importing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showMaps by remember { mutableStateOf(false) }
    var mapsVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(message) {
        if (message != null) {
            delay(4000)
            message = null
        }
    }

    fun centerOn(lat: Double, lon: Double) {
        cx = lonToX(lon)
        cy = latToY(lat)
    }

    fun activate(file: File) {
        val src = runCatching { TileSource.open(file) }.getOrElse {
            message = "Не открылась: ${it.message ?: it.javaClass.simpleName}"
            return
        }
        val z = (src.maxZoom - 2).coerceAtLeast(src.minZoom).toDouble()
        val lat = src.centerLat
        val lon = src.centerLon
        src.close()
        centerOn(lat, lon)
        zoom = z
        Repo.setSettings { it.copy(activeMap = file.path, mapLat = lat, mapLon = lon, mapZoom = z) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = "Копирую карту…"
        scope.launch {
            try {
                val file = MapFiles.import(ctx, uri) { bytes -> importing = "Копирую карту… ${bytes shr 20} МБ" }
                activate(file)
                mapsVersion++
                message = "Карта загружена: ${file.name}"
            } catch (e: Exception) {
                message = "Не получилось: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                importing = null
            }
        }
    }

    val tm = rememberTextMeasurer()
    val selected = state.casualties.firstOrNull { it.id == state.selectedId }

    Row(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .safeDrawingPadding()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(MapBg)
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(minZoom, maxZoom) {
                        detectTransformGestures { centroid, pan, zoomChange, _ ->
                            val tp = tilePx(density)
                            val hw = size.width / 2f
                            val hh = size.height / 2f
                            val oldS = tp * 2.0.pow(zoom)
                            val wx = cx + (centroid.x - hw) / oldS
                            val wy = cy + (centroid.y - hh) / oldS
                            val nz = (zoom + log2(zoomChange.toDouble())).coerceIn(minZoom, maxZoom)
                            val newS = tp * 2.0.pow(nz)
                            val nx = wx - (centroid.x - hw) / newS - pan.x / newS
                            cx = ((nx % 1.0) + 1.0) % 1.0
                            cy = (wy - (centroid.y - hh) / newS - pan.y / newS).coerceIn(0.0, 1.0)
                            zoom = nz
                        }
                    }
                    .pointerInput(state.casualties, minZoom, maxZoom) {
                        detectTapGestures(
                            onDoubleTap = { zoom = (zoom + 1).coerceIn(minZoom, maxZoom) },
                            onTap = { pos ->
                                val v = View(cx, cy, zoom, size.width.toFloat(), size.height.toFloat(), density)
                                val hit = state.casualties
                                    .filter { it.lat != null && it.lon != null }
                                    .map { it to v.toScreen(it.lat!!, it.lon!!) }
                                    .minByOrNull { (_, p) -> hypot((p.x - pos.x).toDouble(), (p.y - pos.y).toDouble()) }
                                if (hit != null) {
                                    val p = hit.second
                                    val d = hypot((p.x - pos.x).toDouble(), (p.y - pos.y).toDouble())
                                    if (d < 32.dp.toPx()) Repo.select(hit.first.id)
                                }
                            },
                        )
                    }
            ) {
                @Suppress("UNUSED_VARIABLE")
                val tick = cache?.version ?: 0
                val w = size.width
                val h = size.height
                val v = View(cx, cy, zoom, w, h, density)

                // Тайлы
                if (source != null && cache != null) {
                    val zi = floor(zoom).toInt().coerceIn(source.minZoom, source.maxZoom)
                    val n = 1 shl zi
                    val t = tilePx(density) * 2.0.pow(zoom - zi)
                    val tcx = cx * n
                    val tcy = cy * n
                    val x0 = floor(tcx - w / 2 / t).toInt()
                    val x1 = floor(tcx + w / 2 / t).toInt()
                    val y0 = floor(tcy - h / 2 / t).toInt().coerceAtLeast(0)
                    val y1 = floor(tcy + h / 2 / t).toInt().coerceAtMost(n - 1)
                    val sizePx = ceil(t).toInt() + 1
                    if ((x1 - x0 + 1) * (y1 - y0 + 1) <= 150) {
                        for (ty in y0..y1) for (tx in x0..x1) {
                            val wrapped = ((tx % n) + n) % n
                            val img = cache.get(zi, wrapped, ty) ?: continue
                            val left = (w / 2 + (tx - tcx) * t).roundToInt()
                            val top = (h / 2 + (ty - tcy) * t).roundToInt()
                            drawImage(
                                image = img,
                                dstOffset = IntOffset(left, top),
                                dstSize = IntSize(sizePx, sizePx),
                                filterQuality = FilterQuality.Low,
                            )
                        }
                    }
                }

                // Сетка СК-42
                if (s.grid) {
                    val c = v.toLatLon(w / 2, h / 2)
                    val zone = Sk42.fromWgs(c[0], c[1]).zone
                    val probes = listOf(
                        0f to 0f, w to 0f, 0f to h, w to h, w / 2 to 0f, w / 2 to h, 0f to h / 2, w to h / 2,
                    )
                    var minX = Double.MAX_VALUE
                    var maxX = -Double.MAX_VALUE
                    var minY = Double.MAX_VALUE
                    var maxY = -Double.MAX_VALUE
                    for ((px, py) in probes) {
                        val ll = v.toLatLon(px, py)
                        val g = Sk42.fromWgs(ll[0], ll[1], zone)
                        minX = min(minX, g.x); maxX = max(maxX, g.x)
                        minY = min(minY, g.y); maxY = max(maxY, g.y)
                    }
                    val span = max(maxX - minX, maxY - minY)
                    val step = GRID_STEPS.firstOrNull { it >= span / 7 }
                    if (step != null) {
                        val stroke = Stroke(width = 1.2.dp.toPx())
                        val labelStyle = TextStyle(
                            color = GridInk, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            background = Color(0xAA0E110C),
                        )
                        // Линии x = const (идут с запада на восток), подпись слева
                        var gx = ceil(minX / step) * step
                        var guard = 0
                        while (gx <= maxX && guard++ < 80) {
                            val path = Path()
                            var label: Offset? = null
                            for (i in 0..24) {
                                val gy = minY + (maxY - minY) * i / 24
                                val ll = Sk42.toWgs(Gk(gx, gy))
                                val p = v.toScreen(ll[0], ll[1])
                                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                                if (label == null && p.x >= 0f && p.y in 0f..h) label = p
                            }
                            drawPath(path, GridLine, style = stroke)
                            label?.let {
                                drawText(tm, gridLabel(gx, step), topLeft = Offset(4.dp.toPx(), it.y - 16.dp.toPx()), style = labelStyle)
                            }
                            gx += step
                        }
                        // Линии y = const (идут с юга на север), подпись сверху
                        var gy = ceil(minY / step) * step
                        guard = 0
                        while (gy <= maxY && guard++ < 80) {
                            val path = Path()
                            var label: Offset? = null
                            for (i in 0..24) {
                                val gxx = minX + (maxX - minX) * i / 24
                                val ll = Sk42.toWgs(Gk(gxx, gy))
                                val p = v.toScreen(ll[0], ll[1])
                                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                                if (p.y >= 0f && p.x in 0f..w && (label == null || p.y < label.y)) label = p
                            }
                            drawPath(path, GridLine, style = stroke)
                            label?.let {
                                drawText(tm, gridLabel(gy, step), topLeft = Offset(it.x + 3.dp.toPx(), 4.dp.toPx()), style = labelStyle)
                            }
                            gy += step
                        }
                    }
                }

                // Моё место
                fix?.let { f: Location ->
                    val p = v.toScreen(f.latitude, f.longitude)
                    if (f.hasAccuracy()) {
                        val mpp = 40075016.686 * cos(Math.toRadians(f.latitude)) / v.worldPx
                        drawCircle(Olive.copy(alpha = 0.18f), (f.accuracy / mpp).toFloat(), p)
                    }
                    drawCircle(Color.White, 10.dp.toPx(), p)
                    drawCircle(Olive, 7.dp.toPx(), p)
                }

                // Раненые
                val pinStyle = TextStyle(color = Bg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                for (cas in state.casualties) {
                    val lat = cas.lat ?: continue
                    val lon = cas.lon ?: continue
                    val p = v.toScreen(lat, lon)
                    val r = 15.dp.toPx()
                    if (cas.id == state.selectedId) drawCircle(Yellow, r + 7.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                    drawCircle(Yellow, r, p)
                    drawCircle(Bg, r, p, style = Stroke(2.dp.toPx()))
                    if (cas.tourniquets.any { it.active }) {
                        drawCircle(Red, 6.dp.toPx(), Offset(p.x + r * 0.75f, p.y - r * 0.75f))
                    }
                    val layout = tm.measure(cas.number.toString(), style = pinStyle)
                    drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
                }

                // Перекрестие центра
                val g = 8.dp.toPx()
                val l = 22.dp.toPx()
                val cw = 2.dp.toPx()
                drawLine(Color.White, Offset(w / 2 - l, h / 2), Offset(w / 2 - g, h / 2), cw)
                drawLine(Color.White, Offset(w / 2 + g, h / 2), Offset(w / 2 + l, h / 2), cw)
                drawLine(Color.White, Offset(w / 2, h / 2 - l), Offset(w / 2, h / 2 - g), cw)
                drawLine(Color.White, Offset(w / 2, h / 2 + g), Offset(w / 2, h / 2 + l), cw)
            }

            // Координаты центра
            val clat = yToLat(cy)
            val clon = xToLon(cx)
            val cg = Sk42.fromWgs(clat, clon)
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color(0xCC0E110C), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    "X ${Sk42.format(cg.x)}   Y ${Sk42.format(cg.y)}",
                    color = Fg, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                )
                Text(
                    String.format(Locale.US, "СК-42 · WGS-84 %.5f, %.5f", clat, clon),
                    color = Fg2, fontSize = 12.sp,
                )
                Text(gpsStatus(fix), color = if (fix != null) Olive else Fg2, fontSize = 12.sp)
                if (selected != null) {
                    Text(
                        "Выбран раненый №${selected.number}" + if (selected.lat == null) " — без координат" else "",
                        color = Yellow, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (source == null) {
                Text(
                    "Карта не загружена. «Карты» → «Загрузить» и выбери файл .mbtiles или .sqlitedb " +
                        "(SAS.Planet, Locus, RMaps). Сетка СК-42 и метки работают и без неё.",
                    color = Fg2, fontSize = 15.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(Color(0xCC0E110C), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }

            val banner = importing ?: message
            if (banner != null) {
                Text(
                    banner,
                    color = Bg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Yellow, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }

        // Панель кнопок
        Column(
            Modifier
                .width(196.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapButton("Назад", Glyph.BACK, Neutral, Fg, Modifier.weight(1f)) { onBack() }
            MapButton("Моё место", Glyph.LOCATE, Olive, Bg, Modifier.weight(1f)) {
                val f = fix
                if (f == null) message = "Нет сигнала GPS" else centerOn(f.latitude, f.longitude)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapButton("", Glyph.ZOOM_OUT, Neutral, Fg, Modifier.weight(1f)) {
                    zoom = (zoom - 1).coerceIn(minZoom, maxZoom)
                }
                MapButton("", Glyph.ZOOM_IN, Neutral, Fg, Modifier.weight(1f)) {
                    zoom = (zoom + 1).coerceIn(minZoom, maxZoom)
                }
            }
            MapButton(
                if (s.grid) "Сетка: вкл" else "Сетка: выкл", Glyph.GRID,
                if (s.grid) PanelSel else Neutral, Fg, Modifier.weight(1f),
            ) { Repo.setSettings { it.copy(grid = !it.grid) } }
            if (selected != null) {
                MapButton("№${selected.number} сюда", Glyph.PIN, Yellow, Bg, Modifier.weight(1f)) {
                    Repo.setPosition(selected.id, yToLat(cy), xToLon(cx))
                    message = "Раненый №${selected.number}: координаты центра"
                }
            }
            MapButton("Карты", Glyph.LAYERS, Neutral, Fg, Modifier.weight(1f)) { showMaps = true }
        }
    }

    if (showMaps) {
        val files = remember(mapsVersion) { MapFiles.list(ctx) }
        MapsDialog(
            files = files,
            active = s.activeMap,
            onLoad = {
                showMaps = false
                picker.launch(arrayOf("*/*"))
            },
            onSelect = { f ->
                showMaps = false
                activate(f)
            },
            onDelete = { f ->
                if (f.path == s.activeMap) Repo.setSettings { it.copy(activeMap = null) }
                f.delete()
                mapsVersion++
            },
            onDismiss = { showMaps = false },
        )
    }
}

private fun gpsStatus(fix: Location?): String {
    if (fix == null) return "GPS: нет сигнала"
    val age = (System.currentTimeMillis() - fix.time) / 1000
    val acc = if (fix.hasAccuracy()) " ±${fix.accuracy.roundToInt()} м" else ""
    return if (age > 60) "GPS: последняя отметка ${age / 60} мин назад" else "GPS$acc"
}

@Composable
private fun MapButton(label: String, glyph: Glyph, container: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        if (label.isEmpty()) {
            GlyphIcon(glyph, Modifier.size(30.dp), content, alpha = 0.9f)
        } else {
            GlyphIcon(glyph, Modifier.size(30.dp), content, alpha = 0.5f)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MapsDialog(
    files: List<File>,
    active: String?,
    onLoad: () -> Unit,
    onSelect: (File) -> Unit,
    onDelete: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<File?>(null) }
    PanelDialog(onDismiss) {
        DialogHeader("Карты", onDismiss)
        if (files.isEmpty()) {
            Text(
                "Загруженных карт нет. Подойдут .mbtiles и .sqlitedb: например, Генштаб из SAS.Planet " +
                    "(экспорт в MBTiles или RMaps SQLite).",
                color = Fg2, fontSize = 15.sp,
            )
        }
        for (f in files) {
            val isActive = f.path == active
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (isActive) PanelSel else Bg)
                    .border(1.dp, if (isActive) Olive else Line, shape)
                    .clickable { onSelect(f) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlyphIcon(Glyph.MAP, Modifier.size(28.dp), if (isActive) Olive else Fg, alpha = 0.6f)
                Column(Modifier.weight(1f)) {
                    Text(f.name, color = Fg, fontSize = 16.sp, maxLines = 1)
                    Text(
                        "${f.length() shr 20} МБ" + if (isActive) " · открыта" else "",
                        color = Fg2, fontSize = 12.sp,
                    )
                }
                TextButton(onClick = { confirm = f }, modifier = Modifier.height(48.dp)) {
                    GlyphIcon(Glyph.DELETE, Modifier.size(24.dp), Fg2, alpha = 0.8f)
                }
            }
        }
        Button(
            onClick = onLoad,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Olive, contentColor = Bg),
        ) {
            GlyphIcon(Glyph.LAYERS, Modifier.size(28.dp), Bg, alpha = 0.5f)
            Spacer(Modifier.width(8.dp))
            Text("Загрузить карту из файла", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
    confirm?.let { f ->
        ConfirmDialog(
            text = "Удалить карту ${f.name}?",
            confirmLabel = "Удалить",
            accent = Red,
            onConfirm = {
                onDelete(f)
                confirm = null
            },
            onDismiss = { confirm = null },
        )
    }
}
