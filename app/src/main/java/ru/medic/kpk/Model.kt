package ru.medic.kpk

import android.content.Context
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Зоны тела. Право/лево — раненого (на схеме он смотрит на тебя, его правая сторона слева). */
enum class Region(val label: String, val abbr: String) {
    UNKNOWN("не указано", "?"),
    HEAD("голова", "Гол"),
    NECK("шея", "Шея"),
    CHEST("грудь", "Гр"),
    ABDOMEN("живот", "Жив"),
    PELVIS("таз", "Таз"),
    BACK("спина", "Сп"),
    RIGHT_ARM("правая рука", "ПР"),
    LEFT_ARM("левая рука", "ЛР"),
    RIGHT_LEG("правая нога", "ПН"),
    LEFT_LEG("левая нога", "ЛН"),
}

enum class Limb(val abbr: String, val full: String, val region: Region) {
    UNKNOWN("?", "не указана", Region.UNKNOWN),
    RIGHT_ARM("ПР", "правая рука", Region.RIGHT_ARM),
    LEFT_ARM("ЛР", "левая рука", Region.LEFT_ARM),
    RIGHT_LEG("ПН", "правая нога", Region.RIGHT_LEG),
    LEFT_LEG("ЛН", "левая нога", Region.LEFT_LEG),
}

enum class InjuryType(val label: String, val full: String) {
    GUNSHOT("Пулевое", "огнестрельное пулевое"),
    FRAGMENT("Осколочное", "осколочное"),
    BLAST("МВТ", "минно-взрывная травма"),
    BURN("Ожог", "термический ожог"),
    FRACTURE("Перелом", "перелом"),
    AMPUTATION("Ампутация", "травматическая ампутация"),
    TBI("ЧМТ", "черепно-мозговая, контузия"),
    OTHER("Другое", "другое"),
}

/** Манипуляции для быстрой записи. Подробности (препарат, доза, место) — текстом в заметке. */
enum class Procedure(val label: String, val full: String) {
    PRESSURE("Давящая", "давящая повязка"),
    PACKING("Тампонада", "тампонада раны"),
    HEMOSTATIC("Гемостатик", "гемостатическое средство"),
    CHEST_SEAL("Окклюзия", "окклюзионная повязка"),
    DECOMPRESSION("Декомпрессия", "пункционная декомпрессия"),
    AIRWAY("НПВ", "назофарингеальный воздуховод"),
    CRIC("Коникотомия", "коникотомия"),
    ANALGESIA("Обезболивание", "обезболивание"),
    TXA("Транексам", "транексамовая кислота"),
    INFUSION("Инфузия", "инфузия в/в или в/к"),
    SPLINT("Иммобилизация", "шина, иммобилизация"),
    WARMING("Согревание", "профилактика переохлаждения"),
}

data class Tourniquet(
    val id: Long,
    val limb: Limb,
    val startedAt: Long,
    val removedAt: Long? = null,
    val warnFired: Boolean = false,
    val critFired: Boolean = false,
) {
    val active: Boolean get() = removedAt == null
}

data class Injury(
    val id: Long,
    val type: InjuryType,
    val region: Region,
    val at: Long,
)

/** Запись в журнале помощи: манипуляция и/или текст. */
data class Note(
    val id: Long,
    val proc: Procedure?,
    val text: String,
    val at: Long,
) {
    val title: String get() = when {
        proc != null && text.isNotBlank() -> "${proc.full}: $text"
        proc != null -> proc.full
        else -> text
    }
}

data class Casualty(
    val id: Long,
    val number: Int,
    val createdAt: Long,
    val tourniquets: List<Tourniquet> = emptyList(),
    val injuries: List<Injury> = emptyList(),
    val notes: List<Note> = emptyList(),
    val lat: Double? = null,
    val lon: Double? = null,
)

data class Settings(
    val warnMinutes: Int = 60,
    val critMinutes: Int = 120,
    val nightMode: Boolean = false,
    val keepScreenOn: Boolean = true,
    val keysEnabled: Boolean = true,
    val tqKey: Int = KeyEvent.KEYCODE_VOLUME_UP,
    val markKey: Int = KeyEvent.KEYCODE_VOLUME_DOWN,
    val activeMap: String? = null,
    val grid: Boolean = true,
    val mapLat: Double = 55.7512,
    val mapLon: Double = 37.6184,
    val mapZoom: Double = 10.0,
)

data class AppState(
    val casualties: List<Casualty> = emptyList(),
    val selectedId: Long? = null,
    val settings: Settings = Settings(),
    val nextNumber: Int = 1,
)

/** Единое состояние приложения. Живёт в процессе, сохраняется в SharedPreferences после каждого изменения. */
object Repo {
    private const val PREFS = "medic_kpk"
    private const val KEY = "state_v1"

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    private var appContext: Context? = null
    private var seq = 0L

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw != null) {
            runCatching { decode(JSONObject(raw)) }.onSuccess { _state.value = it }
        }
    }

    private fun newId(): Long = System.currentTimeMillis() * 1000 + (seq++ % 1000)

    private fun update(transform: (AppState) -> AppState) {
        _state.update(transform)
        persist()
    }

    private fun persist() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, encode(_state.value).toString())
            .apply()
    }

    fun hasActive(): Boolean =
        _state.value.casualties.any { c -> c.tourniquets.any { it.active } }

    /** Новый раненый. Координаты берутся из свежей отметки GPS, если она есть. */
    fun addCasualty(): Long {
        val now = System.currentTimeMillis()
        val id = newId()
        val fix = LocationRepo.freshFix()
        update { s ->
            val c = Casualty(
                id = id,
                number = s.nextNumber,
                createdAt = now,
                lat = fix?.latitude,
                lon = fix?.longitude,
            )
            s.copy(casualties = s.casualties + c, selectedId = id, nextNumber = s.nextNumber + 1)
        }
        return id
    }

    fun select(id: Long) = update { it.copy(selectedId = id) }

    fun deleteCasualty(id: Long) = update { s ->
        s.copy(
            casualties = s.casualties.filterNot { it.id == id },
            selectedId = if (s.selectedId == id) null else s.selectedId,
        )
    }

    fun applyTourniquet(casualtyId: Long, limb: Limb) {
        val t = Tourniquet(id = newId(), limb = limb, startedAt = System.currentTimeMillis())
        update { s ->
            s.copy(
                casualties = s.casualties.map { c ->
                    if (c.id == casualtyId) c.copy(tourniquets = c.tourniquets + t) else c
                },
                selectedId = casualtyId,
            )
        }
    }

    /** Жгут с аппаратной кнопки: на выбранного раненого, а если его нет — на нового. Конечность уточняется потом. */
    fun quickTourniquet() {
        val s = _state.value
        val selected = s.selectedId?.takeIf { id -> s.casualties.any { it.id == id } }
        val target = selected ?: addCasualty()
        applyTourniquet(target, Limb.UNKNOWN)
    }

    fun setLimb(casualtyId: Long, tqId: Long, limb: Limb) =
        mapTourniquet(casualtyId, tqId) { it.copy(limb = limb) }

    fun removeTourniquet(casualtyId: Long, tqId: Long) =
        mapTourniquet(casualtyId, tqId) { it.copy(removedAt = System.currentTimeMillis()) }

    fun markFired(casualtyId: Long, tqId: Long, critical: Boolean) =
        mapTourniquet(casualtyId, tqId) {
            if (critical) it.copy(warnFired = true, critFired = true) else it.copy(warnFired = true)
        }

    fun addInjury(casualtyId: Long, type: InjuryType, region: Region) {
        val i = Injury(id = newId(), type = type, region = region, at = System.currentTimeMillis())
        update { s ->
            s.copy(
                casualties = s.casualties.map { c ->
                    if (c.id == casualtyId) c.copy(injuries = c.injuries + i) else c
                },
                selectedId = casualtyId,
            )
        }
    }

    fun addNote(casualtyId: Long, proc: Procedure?, text: String) {
        val n = Note(id = newId(), proc = proc, text = text.trim(), at = System.currentTimeMillis())
        if (n.proc == null && n.text.isEmpty()) return
        update { s ->
            s.copy(
                casualties = s.casualties.map { c ->
                    if (c.id == casualtyId) c.copy(notes = c.notes + n) else c
                },
                selectedId = casualtyId,
            )
        }
    }

    fun removeNote(casualtyId: Long, noteId: Long) = update { s ->
        s.copy(casualties = s.casualties.map { c ->
            if (c.id == casualtyId) c.copy(notes = c.notes.filterNot { it.id == noteId }) else c
        })
    }

    fun removeInjury(casualtyId: Long, injuryId: Long) = update { s ->
        s.copy(casualties = s.casualties.map { c ->
            if (c.id == casualtyId) c.copy(injuries = c.injuries.filterNot { it.id == injuryId }) else c
        })
    }

    fun setPosition(casualtyId: Long, lat: Double, lon: Double) = update { s ->
        s.copy(casualties = s.casualties.map { c ->
            if (c.id == casualtyId) c.copy(lat = lat, lon = lon) else c
        })
    }

    fun setSettings(transform: (Settings) -> Settings) =
        update { it.copy(settings = transform(it.settings)) }

    private fun mapTourniquet(casualtyId: Long, tqId: Long, f: (Tourniquet) -> Tourniquet) =
        update { s ->
            s.copy(casualties = s.casualties.map { c ->
                if (c.id != casualtyId) c
                else c.copy(tourniquets = c.tourniquets.map { t -> if (t.id == tqId) f(t) else t })
            })
        }

    private fun encode(s: AppState): JSONObject {
        val o = JSONObject()
        o.put("selectedId", s.selectedId ?: JSONObject.NULL)
        o.put("nextNumber", s.nextNumber)
        val st = JSONObject()
        st.put("warn", s.settings.warnMinutes)
        st.put("crit", s.settings.critMinutes)
        st.put("night", s.settings.nightMode)
        st.put("screenOn", s.settings.keepScreenOn)
        st.put("keys", s.settings.keysEnabled)
        st.put("tqKey", s.settings.tqKey)
        st.put("markKey", s.settings.markKey)
        st.put("map", s.settings.activeMap ?: JSONObject.NULL)
        st.put("grid", s.settings.grid)
        st.put("mapLat", s.settings.mapLat)
        st.put("mapLon", s.settings.mapLon)
        st.put("mapZoom", s.settings.mapZoom)
        o.put("settings", st)
        val arr = JSONArray()
        for (c in s.casualties) {
            val co = JSONObject()
            co.put("id", c.id)
            co.put("number", c.number)
            co.put("createdAt", c.createdAt)
            co.put("lat", c.lat ?: JSONObject.NULL)
            co.put("lon", c.lon ?: JSONObject.NULL)
            val tq = JSONArray()
            for (t in c.tourniquets) {
                val to = JSONObject()
                to.put("id", t.id)
                to.put("limb", t.limb.name)
                to.put("start", t.startedAt)
                to.put("removed", t.removedAt ?: JSONObject.NULL)
                to.put("warn", t.warnFired)
                to.put("crit", t.critFired)
                tq.put(to)
            }
            co.put("tq", tq)
            val inj = JSONArray()
            for (i in c.injuries) {
                val io = JSONObject()
                io.put("id", i.id)
                io.put("type", i.type.name)
                io.put("region", i.region.name)
                io.put("at", i.at)
                inj.put(io)
            }
            co.put("inj", inj)
            val notes = JSONArray()
            for (n in c.notes) {
                val no = JSONObject()
                no.put("id", n.id)
                no.put("proc", n.proc?.name ?: JSONObject.NULL)
                no.put("text", n.text)
                no.put("at", n.at)
                notes.put(no)
            }
            co.put("notes", notes)
            arr.put(co)
        }
        o.put("casualties", arr)
        return o
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

    private fun decode(o: JSONObject): AppState {
        val d = Settings()
        val st = o.optJSONObject("settings")
        val settings = if (st == null) d else Settings(
            warnMinutes = st.optInt("warn", d.warnMinutes),
            critMinutes = st.optInt("crit", d.critMinutes),
            nightMode = st.optBoolean("night", d.nightMode),
            keepScreenOn = st.optBoolean("screenOn", d.keepScreenOn),
            keysEnabled = st.optBoolean("keys", d.keysEnabled),
            tqKey = st.optInt("tqKey", d.tqKey),
            markKey = st.optInt("markKey", d.markKey),
            activeMap = if (st.has("map") && !st.isNull("map")) st.getString("map") else null,
            grid = st.optBoolean("grid", d.grid),
            mapLat = st.optDoubleOrNull("mapLat") ?: d.mapLat,
            mapLon = st.optDoubleOrNull("mapLon") ?: d.mapLon,
            mapZoom = st.optDoubleOrNull("mapZoom") ?: d.mapZoom,
        )
        val arr = o.optJSONArray("casualties") ?: JSONArray()
        val list = ArrayList<Casualty>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val tqArr = c.optJSONArray("tq") ?: JSONArray()
            val tqs = ArrayList<Tourniquet>()
            for (j in 0 until tqArr.length()) {
                val t = tqArr.getJSONObject(j)
                tqs.add(
                    Tourniquet(
                        id = t.getLong("id"),
                        limb = runCatching { Limb.valueOf(t.getString("limb")) }.getOrDefault(Limb.UNKNOWN),
                        startedAt = t.getLong("start"),
                        removedAt = if (t.isNull("removed")) null else t.getLong("removed"),
                        warnFired = t.optBoolean("warn", false),
                        critFired = t.optBoolean("crit", false),
                    )
                )
            }
            val noteArr = c.optJSONArray("notes") ?: JSONArray()
            val notes = ArrayList<Note>()
            for (j in 0 until noteArr.length()) {
                val no = noteArr.getJSONObject(j)
                notes.add(
                    Note(
                        id = no.getLong("id"),
                        proc = if (no.isNull("proc")) null
                        else runCatching { Procedure.valueOf(no.getString("proc")) }.getOrNull(),
                        text = no.optString("text", ""),
                        at = no.getLong("at"),
                    )
                )
            }
            val injArr = c.optJSONArray("inj") ?: JSONArray()
            val injuries = ArrayList<Injury>()
            for (j in 0 until injArr.length()) {
                val io = injArr.getJSONObject(j)
                injuries.add(
                    Injury(
                        id = io.getLong("id"),
                        type = runCatching { InjuryType.valueOf(io.getString("type")) }.getOrDefault(InjuryType.OTHER),
                        region = runCatching { Region.valueOf(io.getString("region")) }.getOrDefault(Region.UNKNOWN),
                        at = io.getLong("at"),
                    )
                )
            }
            list.add(
                Casualty(
                    id = c.getLong("id"),
                    number = c.getInt("number"),
                    createdAt = c.getLong("createdAt"),
                    tourniquets = tqs,
                    injuries = injuries,
                    notes = notes,
                    lat = c.optDoubleOrNull("lat"),
                    lon = c.optDoubleOrNull("lon"),
                )
            )
        }
        return AppState(
            casualties = list,
            selectedId = if (o.isNull("selectedId")) null else o.getLong("selectedId"),
            settings = settings,
            nextNumber = o.optInt("nextNumber", list.size + 1),
        )
    }
}

fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatClock(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(Date(epochMs))

/** «X 6 181 832  Y 7 413 366» или null, если координат нет. */
fun formatSk42(lat: Double?, lon: Double?): String? {
    if (lat == null || lon == null) return null
    val g = Sk42.fromWgs(lat, lon)
    return "X ${Sk42.format(g.x)}  Y ${Sk42.format(g.y)}"
}
