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

enum class Limb(val abbr: String, val full: String) {
    UNKNOWN("?", "не указана"),
    LEFT_ARM("ЛР", "левая рука"),
    RIGHT_ARM("ПР", "правая рука"),
    LEFT_LEG("ЛН", "левая нога"),
    RIGHT_LEG("ПН", "правая нога"),
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

data class Casualty(
    val id: Long,
    val number: Int,
    val createdAt: Long,
    val tourniquets: List<Tourniquet> = emptyList(),
)

data class Settings(
    val warnMinutes: Int = 60,
    val critMinutes: Int = 120,
    val nightMode: Boolean = false,
    val keepScreenOn: Boolean = true,
    val keysEnabled: Boolean = true,
    val tqKey: Int = KeyEvent.KEYCODE_VOLUME_UP,
    val markKey: Int = KeyEvent.KEYCODE_VOLUME_DOWN,
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

    fun addCasualty(): Long {
        val now = System.currentTimeMillis()
        val id = newId()
        update { s ->
            val c = Casualty(id = id, number = s.nextNumber, createdAt = now)
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
        o.put("settings", st)
        val arr = JSONArray()
        for (c in s.casualties) {
            val co = JSONObject()
            co.put("id", c.id)
            co.put("number", c.number)
            co.put("createdAt", c.createdAt)
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
            arr.put(co)
        }
        o.put("casualties", arr)
        return o
    }

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
            list.add(
                Casualty(
                    id = c.getLong("id"),
                    number = c.getInt("number"),
                    createdAt = c.getLong("createdAt"),
                    tourniquets = tqs,
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
