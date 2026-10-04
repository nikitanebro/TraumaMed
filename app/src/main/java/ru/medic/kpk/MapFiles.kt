package ru.medic.kpk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Офлайн-карта из SQLite-файла.
 * MBTILES — стандарт .mbtiles (SAS.Planet, QGIS, MOBAC), строки в схеме TMS.
 * RMAPS — .sqlitedb (RMaps, Locus, OsmAnd, SAS.Planet), z = 17 − масштаб.
 */
class TileSource private constructor(
    val file: File,
    private val db: SQLiteDatabase,
    val kind: Kind,
    val minZoom: Int,
    val maxZoom: Int,
    val centerLat: Double,
    val centerLon: Double,
) : Closeable {

    enum class Kind { MBTILES, RMAPS }

    fun tile(z: Int, x: Int, y: Int): ByteArray? = when (kind) {
        Kind.MBTILES -> blob(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1",
            z, x, (1 shl z) - 1 - y,
        )
        Kind.RMAPS -> blob(
            "SELECT image FROM tiles WHERE x=? AND y=? AND z=? LIMIT 1",
            x, y, 17 - z,
        )
    }

    private fun blob(sql: String, a: Int, b: Int, c: Int): ByteArray? =
        db.rawQuery(sql, arrayOf(a.toString(), b.toString(), c.toString())).use { cur ->
            if (cur.moveToFirst()) cur.getBlob(0) else null
        }

    override fun close() {
        db.close()
    }

    companion object {
        fun open(file: File): TileSource {
            val db = SQLiteDatabase.openDatabase(
                file.path, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            try {
                val names = HashSet<String>()
                db.rawQuery("SELECT name FROM sqlite_master WHERE type IN ('table','view')", null).use { c ->
                    while (c.moveToNext()) names.add(c.getString(0).lowercase())
                }
                require("tiles" in names) { "в файле нет таблицы tiles — это не карта" }

                val cols = HashSet<String>()
                db.rawQuery("PRAGMA table_info(tiles)", null).use { c ->
                    val idx = c.getColumnIndex("name")
                    while (c.moveToNext()) cols.add(c.getString(idx).lowercase())
                }
                val kind = when {
                    "zoom_level" in cols && "tile_data" in cols -> Kind.MBTILES
                    "x" in cols && "y" in cols && "z" in cols && "image" in cols -> Kind.RMAPS
                    else -> throw IllegalArgumentException("незнакомый формат: нужен .mbtiles или .sqlitedb")
                }

                var minZ = -1
                var maxZ = -1
                var center: DoubleArray? = null

                if (kind == Kind.MBTILES && "metadata" in names) {
                    db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                        while (c.moveToNext()) {
                            val k = c.getString(0)?.lowercase() ?: continue
                            val v = c.getString(1) ?: continue
                            when (k) {
                                "minzoom" -> v.trim().toIntOrNull()?.let { minZ = it }
                                "maxzoom" -> v.trim().toIntOrNull()?.let { maxZ = it }
                                "center" -> {
                                    val p = v.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                                    if (p.size >= 2) center = doubleArrayOf(p[1], p[0])
                                }
                                "bounds" -> if (center == null) {
                                    val p = v.split(",").mapNotNull { it.trim().toDoubleOrNull() }
                                    if (p.size == 4) center = doubleArrayOf((p[1] + p[3]) / 2, (p[0] + p[2]) / 2)
                                }
                            }
                        }
                    }
                }

                if (minZ < 0 || maxZ < 0) {
                    val sql = if (kind == Kind.MBTILES) "SELECT MIN(zoom_level), MAX(zoom_level) FROM tiles"
                    else "SELECT MIN(z), MAX(z) FROM tiles"
                    db.rawQuery(sql, null).use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) {
                            val a = c.getInt(0)
                            val b = c.getInt(1)
                            if (kind == Kind.MBTILES) {
                                minZ = a; maxZ = b
                            } else {
                                minZ = 17 - b; maxZ = 17 - a
                            }
                        }
                    }
                }
                require(minZ in 0..maxZ) { "в карте нет тайлов" }

                if (center == null) {
                    val sql = if (kind == Kind.MBTILES) "SELECT tile_column, tile_row FROM tiles WHERE zoom_level=? LIMIT 1"
                    else "SELECT x, y FROM tiles WHERE z=? LIMIT 1"
                    val zArg = if (kind == Kind.MBTILES) maxZ else 17 - maxZ
                    db.rawQuery(sql, arrayOf(zArg.toString())).use { c ->
                        if (c.moveToFirst()) {
                            val tx = c.getInt(0)
                            var ty = c.getInt(1)
                            if (kind == Kind.MBTILES) ty = (1 shl maxZ) - 1 - ty
                            center = tileCenter(maxZ, tx, ty)
                        }
                    }
                }
                val ctr = center ?: doubleArrayOf(55.7512, 37.6184)
                return TileSource(file, db, kind, minZ, maxZ, ctr[0], ctr[1])
            } catch (e: Exception) {
                db.close()
                throw e
            }
        }

        private fun tileCenter(z: Int, x: Int, y: Int): DoubleArray {
            val n = (1 shl z).toDouble()
            val lon = (x + 0.5) / n * 360.0 - 180.0
            val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * (y + 0.5) / n))))
            return doubleArrayOf(lat, lon)
        }
    }
}

object MapFiles {
    fun dir(ctx: Context): File = File(ctx.filesDir, "maps").also { it.mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith("-journal") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /** Копирует выбранный файл к себе (SQLite нужен настоящий путь) и проверяет, что это карта. */
    suspend fun import(ctx: Context, uri: Uri, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val name = displayName(ctx, uri) ?: "map.mbtiles"
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        val target = uniqueFile(dir(ctx), safe)
        val tmp = File(target.path + ".part")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("не удалось открыть файл")
            input.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    var reported = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total - reported >= (4L shl 20)) {
                            reported = total
                            progress(total)
                        }
                    }
                }
            }
            check(tmp.renameTo(target)) { "не удалось сохранить файл" }
            TileSource.open(target).close()
            target
        } catch (e: Exception) {
            tmp.delete()
            target.delete()
            throw e
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? =
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }
}
