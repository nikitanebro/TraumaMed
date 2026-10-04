package ru.medic.kpk

import android.location.Location
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Палитра: раненый — жёлтый, жгут — красный, остальное приглушено.
val Bg = Color(0xFF0E110C)
val Panel = Color(0xFF1A1E17)
val PanelSel = Color(0xFF2A3122)
val Line = Color(0xFF353D2F)
val Fg = Color(0xFFE4E8DC)
val Fg2 = Color(0xFFA3AB99)
val Olive = Color(0xFFA3BE6E)
val Yellow = Color(0xFFF2C94C)
val YellowDim = Color(0xFF2E2914)
val Red = Color(0xFFE5484D)
val RedDim = Color(0xFF4A1C1E)
val Neutral = Color(0xFF39412F)
val MapBg = Color(0xFF151912)
val GridLine = Color(0xCCE07A2E)
val GridInk = Color(0xFFF3A766)

private val Colors = darkColorScheme(
    primary = Olive,
    onPrimary = Bg,
    secondary = Yellow,
    onSecondary = Bg,
    background = Bg,
    onBackground = Fg,
    surface = Panel,
    onSurface = Fg,
    error = Red,
)

fun keyName(code: Int): String = when (code) {
    KeyEvent.KEYCODE_VOLUME_UP -> "громкость вверх"
    KeyEvent.KEYCODE_VOLUME_DOWN -> "громкость вниз"
    else -> "код $code (${KeyEvent.keyCodeToString(code)})"
}

private enum class Screen { MAIN, SETTINGS, MAP }

@Composable
fun AppRoot(activity: MainActivity) {
    val state by Repo.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    var screen by remember { mutableStateOf(Screen.MAIN) }

    MaterialTheme(colorScheme = Colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            when (screen) {
                Screen.MAIN -> MainScreen(
                    state, now,
                    onSettings = { screen = Screen.SETTINGS },
                    onMap = { screen = Screen.MAP },
                )
                Screen.SETTINGS -> {
                    BackHandler { screen = Screen.MAIN }
                    SettingsScreen(state.settings, activity) { screen = Screen.MAIN }
                }
                Screen.MAP -> {
                    BackHandler { screen = Screen.MAIN }
                    MapScreen(state) { screen = Screen.MAIN }
                }
            }
        }
    }
}

@Composable
private fun MainScreen(state: AppState, now: Long, onSettings: () -> Unit, onMap: () -> Unit) {
    val ctx = LocalContext.current
    val fix by LocationRepo.fix.collectAsState()
    val warnMs = state.settings.warnMinutes * 60_000L
    val critMs = state.settings.critMinutes * 60_000L
    val activeCount = state.casualties.sumOf { c -> c.tourniquets.count { it.active } }

    var limbFor by remember { mutableStateOf<Long?>(null) }
    var injuryFor by remember { mutableStateOf<Long?>(null) }
    var limbChange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirmRemove by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirmInjury by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var noteFor by remember { mutableStateOf<Long?>(null) }
    var confirmNote by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    fun target(): Long =
        state.selectedId?.takeIf { id -> state.casualties.any { it.id == id } } ?: Repo.addCasualty()

    Row(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.CASUALTY, Modifier.size(26.dp), Yellow, alpha = 0.6f)
                Spacer(Modifier.width(6.dp))
                Text("Раненые: ${state.casualties.size}", color = Yellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(18.dp))
                GlyphIcon(Glyph.TOURNIQUET, Modifier.size(26.dp), Red, alpha = 0.6f)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Жгуты: $activeCount",
                    color = if (activeCount > 0) Red else Fg2,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                GlyphIcon(Glyph.LOCATE, Modifier.size(20.dp), if (fix != null) Olive else Fg2, alpha = 0.6f)
                Spacer(Modifier.width(4.dp))
                Text(gpsShort(fix), color = if (fix != null) Olive else Fg2, fontSize = 14.sp)
            }

            if (state.casualties.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Пока никого.\nКнопка «Раненый» справа или удержание кнопки метки.",
                        color = Fg2,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 300.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.casualties.reversed(), key = { it.id }) { c ->
                        CasualtyCard(
                            c = c,
                            selected = c.id == state.selectedId,
                            now = now,
                            warnMs = warnMs,
                            critMs = critMs,
                            onSelect = { Repo.select(c.id) },
                            onAddTq = { limbFor = c.id },
                            onAddInjury = { injuryFor = c.id },
                            onLimb = { t -> limbChange = c.id to t.id },
                            onRemoveTq = { t -> confirmRemove = c.id to t.id },
                            onRemoveInjury = { i -> confirmInjury = c.id to i.id },
                            onAddNote = { noteFor = c.id },
                            onRemoveNote = { n -> confirmNote = c.id to n.id },
                            onDelete = { confirmDelete = c.id },
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .width(200.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RailButton("Раненый", Glyph.CASUALTY, Yellow, Bg, Modifier.weight(1f)) {
                Repo.addCasualty()
                Haptics.ok(ctx)
            }
            RailButton("Жгут", Glyph.TOURNIQUET, Red, Color.White, Modifier.weight(1f)) { limbFor = target() }
            RailButton("Ранение", Glyph.WOUND, Neutral, Fg, Modifier.weight(1f)) { injuryFor = target() }
            RailButton("Помощь", Glyph.NOTE, Neutral, Fg, Modifier.weight(1f)) { noteFor = target() }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RailButton("Карта", Glyph.MAP, Olive, Bg, Modifier.weight(1f)) { onMap() }
                RailButton("", Glyph.SETTINGS, Neutral, Fg, Modifier.width(60.dp)) { onSettings() }
            }
        }
    }

    limbFor?.let { cid ->
        val number = state.casualties.firstOrNull { it.id == cid }?.number
        LimbDialog(
            title = if (number != null) "Жгут: раненый №$number" else "Жгут",
            onPick = { limb ->
                Repo.applyTourniquet(cid, limb)
                TimerService.sync(ctx)
                Haptics.ok(ctx)
                limbFor = null
            },
            onDismiss = { limbFor = null },
        )
    }

    injuryFor?.let { cid ->
        val number = state.casualties.firstOrNull { it.id == cid }?.number
        InjuryDialog(
            number = number,
            onDone = { type, region ->
                Repo.addInjury(cid, type, region)
                Haptics.ok(ctx)
                injuryFor = null
            },
            onDismiss = { injuryFor = null },
        )
    }

    noteFor?.let { cid ->
        val c = state.casualties.firstOrNull { it.id == cid }
        NoteDialog(
            number = c?.number,
            onLog = { proc, text ->
                Repo.addNote(cid, proc, text)
                Haptics.ok(ctx)
            },
            onDismiss = { noteFor = null },
        )
    }

    confirmNote?.let { (cid, nid) ->
        val c = state.casualties.firstOrNull { it.id == cid }
        val n = c?.notes?.firstOrNull { it.id == nid }
        ConfirmDialog(
            text = "Убрать запись «${n?.title ?: ""}» у раненого №${c?.number ?: ""}?",
            confirmLabel = "Убрать",
            accent = Yellow,
            onConfirm = {
                Repo.removeNote(cid, nid)
                confirmNote = null
            },
            onDismiss = { confirmNote = null },
        )
    }

    limbChange?.let { (cid, tid) ->
        LimbDialog(
            title = "Какая конечность?",
            onPick = { limb ->
                Repo.setLimb(cid, tid, limb)
                Haptics.ok(ctx)
                limbChange = null
            },
            onDismiss = { limbChange = null },
        )
    }

    confirmRemove?.let { (cid, tid) ->
        val c = state.casualties.firstOrNull { it.id == cid }
        val t = c?.tourniquets?.firstOrNull { it.id == tid }
        ConfirmDialog(
            text = "Снять жгут ${t?.limb?.abbr ?: ""} у раненого №${c?.number ?: ""}?",
            confirmLabel = "Снять",
            accent = Red,
            onConfirm = {
                Repo.removeTourniquet(cid, tid)
                TimerService.sync(ctx)
                Haptics.ok(ctx)
                confirmRemove = null
            },
            onDismiss = { confirmRemove = null },
        )
    }

    confirmInjury?.let { (cid, iid) ->
        val c = state.casualties.firstOrNull { it.id == cid }
        val i = c?.injuries?.firstOrNull { it.id == iid }
        ConfirmDialog(
            text = "Убрать запись «${i?.type?.label ?: ""}, ${i?.region?.label ?: ""}» у раненого №${c?.number ?: ""}?",
            confirmLabel = "Убрать",
            accent = Yellow,
            onConfirm = {
                Repo.removeInjury(cid, iid)
                confirmInjury = null
            },
            onDismiss = { confirmInjury = null },
        )
    }

    confirmDelete?.let { cid ->
        val c = state.casualties.firstOrNull { it.id == cid }
        val hasActive = c?.tourniquets?.any { it.active } == true
        ConfirmDialog(
            text = "Удалить раненого №${c?.number ?: ""}?" +
                if (hasActive) "\nУ него есть активные жгуты, их таймеры пропадут." else "",
            confirmLabel = "Удалить",
            accent = Red,
            onConfirm = {
                Repo.deleteCasualty(cid)
                TimerService.sync(ctx)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

private fun gpsShort(fix: Location?): String {
    if (fix == null) return "GPS нет"
    val age = (System.currentTimeMillis() - fix.time) / 1000
    if (age > 60) return "GPS ${age / 60} мин назад"
    return if (fix.hasAccuracy()) "GPS ±${fix.accuracy.roundToInt()} м" else "GPS"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CasualtyCard(
    c: Casualty,
    selected: Boolean,
    now: Long,
    warnMs: Long,
    critMs: Long,
    onSelect: () -> Unit,
    onAddTq: () -> Unit,
    onAddInjury: () -> Unit,
    onLimb: (Tourniquet) -> Unit,
    onRemoveTq: (Tourniquet) -> Unit,
    onRemoveInjury: (Injury) -> Unit,
    onAddNote: () -> Unit,
    onRemoveNote: (Note) -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val active = c.tourniquets.filter { it.active }
    val removed = c.tourniquets.filter { !it.active }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) YellowDim else Panel, shape)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Yellow else Line), shape)
            .clickable { onSelect() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.CASUALTY, Modifier.size(30.dp), Yellow, alpha = 0.55f)
            Spacer(Modifier.width(6.dp))
            Text("№ ${c.number}", color = Yellow, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("с ${formatClock(c.createdAt)}", color = Fg2, fontSize = 14.sp)
        }
        Text(
            formatSk42(c.lat, c.lon) ?: "координат нет — поставь на карте",
            color = Fg2,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
        )

        for (t in active) {
            TourniquetRow(t, now, warnMs, critMs, onLimb = { onLimb(t) }, onRemove = { onRemoveTq(t) })
        }
        for (t in removed) {
            val end = t.removedAt ?: 0L
            Text(
                "Жгут ${t.limb.abbr}: снят в ${formatClock(end)}, стоял ${formatElapsed(end - t.startedAt)}",
                color = Fg2,
                fontSize = 13.sp,
            )
        }

        if (c.injuries.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (i in c.injuries) InjuryChip(i) { onRemoveInjury(i) }
            }
        }

        if (c.notes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (n in c.notes) NoteLine(n) { onRemoveNote(n) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CardAction("Жгут", Glyph.TOURNIQUET, Red, Modifier.weight(1f), onAddTq)
            CardAction("Ранение", Glyph.WOUND, Fg, Modifier.weight(1f), onAddInjury)
            CardAction("Помощь", Glyph.NOTE, Fg, Modifier.weight(1f), onAddNote)
            CardAction("Удалить", Glyph.DELETE, Fg2, Modifier.weight(0.8f), onDelete)
        }
    }
}

@Composable
private fun TourniquetRow(
    t: Tourniquet,
    now: Long,
    warnMs: Long,
    critMs: Long,
    onLimb: () -> Unit,
    onRemove: () -> Unit,
) {
    val elapsed = now - t.startedAt
    val crit = elapsed >= critMs
    val warn = elapsed >= warnMs
    val bg = when {
        crit -> Red
        warn -> RedDim
        else -> Color.Transparent
    }
    val fg = if (crit) Color.White else Fg
    val accent = if (crit) Color.White else Red
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, shape)
            .border(1.5.dp, Red, shape)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(shape)
                .clickable { onLimb() },
            contentAlignment = Alignment.Center,
        ) {
            BodyGlyph(
                t.limb.region,
                Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                accent,
                alpha = 0.55f,
            )
            Text(t.limb.abbr, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(
                formatElapsed(elapsed),
                color = fg,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    crit -> "КРИТИЧНО · с ${formatClock(t.startedAt)}"
                    warn -> "ПОРОГ · с ${formatClock(t.startedAt)}"
                    else -> "наложен в ${formatClock(t.startedAt)}"
                },
                color = if (crit) Color.White else if (warn) Red else Fg2,
                fontSize = 12.sp,
                fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal,
            )
        }
        OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.height(52.dp),
            border = BorderStroke(1.dp, fg),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            GlyphIcon(Glyph.REMOVE, Modifier.size(22.dp), fg, alpha = 0.6f)
            Spacer(Modifier.width(4.dp))
            Text("Снять", color = fg, fontSize = 15.sp)
        }
    }
}

@Composable
private fun InjuryChip(i: Injury, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .clip(shape)
            .background(Neutral)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(i.type.glyph, Modifier.size(20.dp), Fg, alpha = 0.6f)
        Spacer(Modifier.width(5.dp))
        Text("${i.type.label} · ${i.region.label} · ${formatClock(i.at)}", color = Fg, fontSize = 13.sp)
    }
}

@Composable
private fun NoteLine(n: Note, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatClock(n.at), color = Fg2, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(6.dp))
        GlyphIcon(n.proc?.glyph ?: Glyph.NOTE, Modifier.size(18.dp), Fg, alpha = 0.55f)
        Spacer(Modifier.width(6.dp))
        Text(n.title, color = Fg, fontSize = 14.sp)
    }
}

/** Кнопка карточки: значок сверху, подпись снизу, чтобы четыре влезали в узкую карточку. */
@Composable
private fun CardAction(label: String, glyph: Glyph, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        border = BorderStroke(1.dp, if (color == Red) Red else Line),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlyphIcon(glyph, Modifier.size(24.dp), color, alpha = 0.6f)
            Text(label, color = color, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun RailButton(label: String, glyph: Glyph, container: Color, content: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (label.isEmpty()) {
            GlyphIcon(glyph, Modifier.size(34.dp), content, alpha = 0.9f)
        } else {
            GlyphIcon(glyph, Modifier.size(42.dp), content, alpha = 0.45f)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

// ---------- Диалоги ----------

@Composable
internal fun PanelDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun DialogHeader(
    title: String,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    closeLabel: String = "Отмена",
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) {
            OutlinedButton(onClick = onBack, modifier = Modifier.height(48.dp)) {
                GlyphIcon(Glyph.BACK, Modifier.size(22.dp), Fg, alpha = 0.8f)
            }
        }
        Text(title, color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        TextButton(onClick = onClose, modifier = Modifier.height(48.dp)) { Text(closeLabel, fontSize = 16.sp) }
    }
}

@Composable
internal fun ConfirmDialog(
    text: String,
    confirmLabel: String,
    accent: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    PanelDialog(onDismiss) {
        Text(text, color = Fg, fontSize = 18.sp)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss, modifier = Modifier.height(52.dp)) { Text("Отмена", fontSize = 16.sp) }
            Button(
                onClick = onConfirm,
                modifier = Modifier.height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = if (accent == Yellow) Bg else Color.White,
                ),
            ) { Text(confirmLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Большая кнопка выбора: полупрозрачный значок на фоне, подпись снизу. */
@Composable
private fun ChoiceButton(
    title: String,
    subtitle: String?,
    modifier: Modifier,
    accent: Color,
    onClick: () -> Unit,
    titleSize: TextUnit = 18.sp,
    icon: @Composable (Modifier, Color) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .clip(shape)
            .background(PanelSel)
            .border(1.dp, Line, shape)
            .clickable { onClick() }
            .padding(6.dp),
    ) {
        icon(
            Modifier
                .fillMaxSize()
                .padding(bottom = 22.dp),
            accent,
        )
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Fg, fontSize = titleSize, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
            if (subtitle != null) Text(subtitle, color = Fg2, fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun LimbDialog(title: String, onPick: (Limb) -> Unit, onDismiss: () -> Unit) {
    PanelDialog(onDismiss) {
        DialogHeader(title, onDismiss)
        Row(
            Modifier
                .fillMaxWidth()
                .height(140.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (limb in listOf(Limb.RIGHT_ARM, Limb.LEFT_ARM, Limb.RIGHT_LEG, Limb.LEFT_LEG, Limb.UNKNOWN)) {
                ChoiceButton(
                    limb.abbr, limb.full,
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    Red,
                    onClick = { onPick(limb) },
                ) { m, col -> BodyGlyph(limb.region, m, col, alpha = 0.6f) }
            }
        }
        Text("Схема: раненый лицом к тебе, его правая сторона слева.", color = Fg2, fontSize = 12.sp)
    }
}

@Composable
private fun InjuryDialog(number: Int?, onDone: (InjuryType, Region) -> Unit, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf<InjuryType?>(null) }
    PanelDialog(onDismiss) {
        val chosen = type
        if (chosen == null) {
            DialogHeader("Ранение" + (number?.let { ": раненый №$it" } ?: "") + " — вид", onDismiss)
            for (row in InjuryType.entries.chunked(4)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(108.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (ty in row) {
                        ChoiceButton(
                            ty.label, ty.full,
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            Fg,
                            onClick = { type = ty },
                        ) { m, col -> GlyphIcon(ty.glyph, m, col, alpha = 0.4f) }
                    }
                }
            }
        } else {
            DialogHeader("${chosen.label} — где?", onDismiss, onBack = { type = null })
            val regions = listOf(
                Region.HEAD, Region.NECK, Region.CHEST, Region.ABDOMEN, Region.PELVIS, Region.BACK,
                Region.RIGHT_ARM, Region.LEFT_ARM, Region.RIGHT_LEG, Region.LEFT_LEG, Region.UNKNOWN,
            )
            for (row in regions.chunked(6)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(108.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (r in row) {
                        ChoiceButton(
                            r.abbr, r.label,
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            Yellow,
                            onClick = { onDone(chosen, r) },
                        ) { m, col -> BodyGlyph(r, m, col, alpha = 0.6f) }
                    }
                    repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Журнал помощи. Тап по манипуляции сразу записывает её со временем, диалог остаётся открытым,
 * чтобы отметить несколько подряд. Текст из поля (препарат, доза, место) уходит в ту же запись.
 */
@Composable
private fun NoteDialog(number: Int?, onLog: (Procedure?, String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<String?>(null) }

    fun log(proc: Procedure?) {
        if (proc == null && text.isBlank()) return
        onLog(proc, text)
        last = (proc?.full ?: text.trim()) + " · " + formatClock(System.currentTimeMillis())
        text = ""
    }

    PanelDialog(onDismiss) {
        DialogHeader("Помощь" + (number?.let { ": раненый №$it" } ?: ""), onDismiss, closeLabel = "Готово")
        last?.let {
            Text("Записано: $it", color = Olive, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Подробности или своя заметка: препарат, доза, место…", fontSize = 14.sp) },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { log(null) }),
            )
            Button(
                onClick = { log(null) },
                enabled = text.isNotBlank(),
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Olive, contentColor = Bg),
            ) {
                GlyphIcon(Glyph.NOTE, Modifier.size(24.dp), Bg, alpha = 0.6f)
                Spacer(Modifier.width(6.dp))
                Text("Записать", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        for (row in Procedure.entries.chunked(6)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(92.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (p in row) {
                    ChoiceButton(
                        p.label, null,
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        Olive,
                        onClick = { log(p) },
                        titleSize = 14.sp,
                    ) { m, col -> GlyphIcon(p.glyph, m, col, alpha = 0.45f) }
                }
            }
        }
        Text(
            "Тап по манипуляции — запись с текущим временем. Если в поле есть текст, он добавится к ней.",
            color = Fg2, fontSize = 12.sp,
        )
    }
}

// ---------- Настройки ----------

@Composable
private fun SettingsScreen(s: Settings, activity: MainActivity, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.SETTINGS, Modifier.size(30.dp), Fg, alpha = 0.5f)
            Spacer(Modifier.width(8.dp))
            Text(
                "Настройки",
                color = Fg,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.height(52.dp)) { Text("Готово", fontSize = 16.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionTitle("Пороги жгута")
                Stepper("Предупреждение, мин", s.warnMinutes, step = 5, min = 5, max = 600) { v ->
                    Repo.setSettings { it.copy(warnMinutes = v) }
                }
                Stepper("Критично, мин", s.critMinutes, step = 5, min = 5, max = 720) { v ->
                    Repo.setSettings { it.copy(critMinutes = v) }
                }
                Text("Выставь пороги по своему протоколу.", color = Fg2, fontSize = 14.sp)

                SectionTitle("Экран")
                Toggle("Ночной режим: минимальная яркость", s.nightMode) { v -> Repo.setSettings { it.copy(nightMode = v) } }
                Toggle("Экран не гаснет, пока приложение открыто", s.keepScreenOn) { v ->
                    Repo.setSettings { it.copy(keepScreenOn = v) }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionTitle("Аппаратные кнопки")
                Toggle("Кнопки включены", s.keysEnabled) { v -> Repo.setSettings { it.copy(keysEnabled = v) } }
                KeyRow("Жгут на выбранного (удержание)", s.tqKey, activity.learning == "tq") { activity.learning = "tq" }
                KeyRow("Новый раненый (удержание)", s.markKey, activity.learning == "mark") { activity.learning = "mark" }
                Text("Последняя нажатая кнопка: ${activity.lastKey}", color = Fg2, fontSize = 14.sp)
                Text(
                    "Кнопки работают, пока приложение на экране. Если кнопка XCover здесь не появляется, " +
                        "её перехватывает Samsung: назначь ей в настройках телефона запуск этого приложения.",
                    color = Fg2,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Olive, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun Stepper(label: String, value: Int, step: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            modifier = Modifier.size(56.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("−", fontSize = 24.sp) }
        Text(
            "$value",
            color = Fg,
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        OutlinedButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            modifier = Modifier.size(56.dp),
            contentPadding = PaddingValues(0.dp),
        ) { Text("+", fontSize = 24.sp) }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Fg, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun KeyRow(label: String, code: Int, learning: Boolean, onLearn: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Fg, fontSize = 17.sp)
            Text(
                if (learning) "Нажми нужную кнопку…" else keyName(code),
                color = if (learning) Yellow else Fg2,
                fontSize = 14.sp,
            )
        }
        OutlinedButton(onClick = onLearn, modifier = Modifier.height(52.dp)) { Text("Назначить", fontSize = 15.sp) }
    }
}
