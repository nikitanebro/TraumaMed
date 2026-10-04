package ru.medic.kpk

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0E110C)
private val Panel = Color(0xFF1A1E17)
private val PanelSel = Color(0xFF242B1E)
private val Line = Color(0xFF353D2F)
private val Fg = Color(0xFFE4E8DC)
private val Fg2 = Color(0xFFA3AB99)
private val Olive = Color(0xFFA3BE6E)
private val Amber = Color(0xFFE6A84B)
private val Red = Color(0xFFEF5B4F)

private val Colors = darkColorScheme(
    primary = Olive,
    onPrimary = Bg,
    secondary = Amber,
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
    var showSettings by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = Colors) {
        Surface(modifier = Modifier.fillMaxSize(), color = Bg) {
            if (showSettings) {
                BackHandler { showSettings = false }
                SettingsScreen(state.settings, activity) { showSettings = false }
            } else {
                MainScreen(state, now) { showSettings = true }
            }
        }
    }
}

@Composable
private fun MainScreen(state: AppState, now: Long, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val warnMs = state.settings.warnMinutes * 60_000L
    val critMs = state.settings.critMinutes * 60_000L
    val activeCount = state.casualties.sumOf { c -> c.tourniquets.count { it.active } }

    var limbForNew by remember { mutableStateOf<Long?>(null) }
    var limbChange by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirmRemove by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Раненые: ${state.casualties.size}", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (activeCount == 0) "активных жгутов нет" else "активных жгутов: $activeCount",
                    color = if (activeCount > 0) Amber else Fg2,
                    fontSize = 15.sp,
                )
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.height(52.dp)) {
                Text("Настройки", fontSize = 16.sp)
            }
        }

        if (state.casualties.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Пока никого.\nНажми «+ Раненый» или удержи кнопку метки.",
                    color = Fg2,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.casualties.reversed(), key = { it.id }) { c ->
                    CasualtyCard(
                        c = c,
                        selected = c.id == state.selectedId,
                        now = now,
                        warnMs = warnMs,
                        critMs = critMs,
                        onSelect = { Repo.select(c.id) },
                        onAddTq = { limbForNew = c.id },
                        onLimb = { t -> limbChange = c.id to t.id },
                        onRemove = { t -> confirmRemove = c.id to t.id },
                        onDelete = { confirmDelete = c.id },
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BigButton("+ Раненый", Modifier.weight(1f), Olive) {
                Repo.addCasualty()
                Haptics.ok(ctx)
            }
            BigButton("+ Жгут", Modifier.weight(1f), Amber) {
                val selected = state.selectedId?.takeIf { id -> state.casualties.any { it.id == id } }
                limbForNew = selected ?: Repo.addCasualty()
            }
        }
    }

    limbForNew?.let { cid ->
        val number = state.casualties.firstOrNull { it.id == cid }?.number
        LimbDialog(
            title = if (number != null) "Жгут: раненый №$number" else "Жгут",
            onPick = { limb ->
                Repo.applyTourniquet(cid, limb)
                TimerService.sync(ctx)
                Haptics.ok(ctx)
                limbForNew = null
            },
            onDismiss = { limbForNew = null },
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
            onConfirm = {
                Repo.removeTourniquet(cid, tid)
                TimerService.sync(ctx)
                Haptics.ok(ctx)
                confirmRemove = null
            },
            onDismiss = { confirmRemove = null },
        )
    }

    confirmDelete?.let { cid ->
        val c = state.casualties.firstOrNull { it.id == cid }
        val hasActive = c?.tourniquets?.any { it.active } == true
        ConfirmDialog(
            text = "Удалить раненого №${c?.number ?: ""}?" +
                if (hasActive) "\nУ него есть активные жгуты, их таймеры пропадут." else "",
            confirmLabel = "Удалить",
            onConfirm = {
                Repo.deleteCasualty(cid)
                TimerService.sync(ctx)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun CasualtyCard(
    c: Casualty,
    selected: Boolean,
    now: Long,
    warnMs: Long,
    critMs: Long,
    onSelect: () -> Unit,
    onAddTq: () -> Unit,
    onLimb: (Tourniquet) -> Unit,
    onRemove: (Tourniquet) -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val active = c.tourniquets.filter { it.active }
    val removed = c.tourniquets.filter { !it.active }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) PanelSel else Panel, shape)
            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Olive else Line), shape)
            .clickable { onSelect() }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "№ ${c.number}",
                color = Fg,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text("с ${formatClock(c.createdAt)}", color = Fg2, fontSize = 15.sp)
        }

        for (t in active) {
            val elapsed = now - t.startedAt
            val color = when {
                elapsed >= critMs -> Red
                elapsed >= warnMs -> Amber
                else -> Fg
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { onLimb(t) },
                    modifier = Modifier
                        .height(56.dp)
                        .widthIn(min = 72.dp),
                ) {
                    Text(t.limb.abbr, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        formatElapsed(elapsed),
                        color = color,
                        fontSize = 30.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("наложен в ${formatClock(t.startedAt)}", color = Fg2, fontSize = 13.sp)
                }
                OutlinedButton(onClick = { onRemove(t) }, modifier = Modifier.height(56.dp)) {
                    Text("Снять", fontSize = 16.sp)
                }
            }
        }

        for (t in removed) {
            val end = t.removedAt ?: 0L
            Text(
                "${t.limb.abbr}: снят в ${formatClock(end)}, стоял ${formatElapsed(end - t.startedAt)}",
                color = Fg2,
                fontSize = 14.sp,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onAddTq,
                modifier = Modifier
                    .height(52.dp)
                    .weight(1f),
            ) {
                Text("+ Жгут", fontSize = 17.sp)
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.height(52.dp)) {
                Text("Удалить", fontSize = 15.sp, color = Fg2)
            }
        }
    }
}

@Composable
private fun BigButton(text: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(68.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Bg),
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LimbDialog(title: String, onPick: (Limb) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(title, color = Fg) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LimbButton(Limb.LEFT_ARM, Modifier.weight(1f), onPick)
                    LimbButton(Limb.RIGHT_ARM, Modifier.weight(1f), onPick)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LimbButton(Limb.LEFT_LEG, Modifier.weight(1f), onPick)
                    LimbButton(Limb.RIGHT_LEG, Modifier.weight(1f), onPick)
                }
                LimbButton(Limb.UNKNOWN, Modifier.fillMaxWidth(), onPick)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", fontSize = 16.sp) }
        },
    )
}

@Composable
private fun LimbButton(limb: Limb, modifier: Modifier, onPick: (Limb) -> Unit) {
    Button(
        onClick = { onPick(limb) },
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PanelSel, contentColor = Fg),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(limb.abbr, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(limb.full, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ConfirmDialog(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        text = { Text(text, color = Fg, fontSize = 18.sp) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Bg),
            ) { Text(confirmLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(52.dp)) { Text("Отмена", fontSize = 16.sp) }
        },
    )
}

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
            Text(
                "Настройки",
                color = Fg,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.height(52.dp)) { Text("Готово", fontSize = 16.sp) }
        }

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
        Toggle("Экран не гаснет, пока приложение открыто", s.keepScreenOn) { v -> Repo.setSettings { it.copy(keepScreenOn = v) } }

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
                color = if (learning) Amber else Fg2,
                fontSize = 14.sp,
            )
        }
        OutlinedButton(onClick = onLearn, modifier = Modifier.height(52.dp)) { Text("Назначить", fontSize = 15.sp) }
    }
}
