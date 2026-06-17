package com.margelo.nitro.persistentbackgroundlocation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Append-only SQLite ring buffer for location fixes.
 *
 * Every fix is persisted *before* it is delivered to JS, so a swipe-to-kill or a
 * process death never loses data. The store is the source of truth the
 * [LocationSyncer] drains and the JS `getBufferedLocations()` reads.
 *
 * Concurrency: a process-wide singleton wrapping one [SQLiteOpenHelper].
 * SQLite serializes writers internally; we additionally guard multi-statement
 * operations (trim, batch-take) with a monitor so concurrent IO-dispatcher
 * callers stay consistent. All methods are safe to call off the main thread —
 * and must be, since they touch disk.
 */
internal class LocationBufferStore private constructor(context: Context) {

  private val helper = Helper(context.applicationContext)
  private val lock = Any()

  /** Insert a fix, trimming the oldest rows past [maxRecords]. Returns the new row id. */
  fun insert(fix: LocationFixModel, maxRecords: Int): Long = synchronized(lock) {
    val db = helper.writableDatabase
    val values = ContentValues(2).apply {
      put(COL_TS, fix.timestamp)
      put(COL_BODY, fix.toJson().toString())
    }
    val rowId = db.insert(TABLE, null, values)
    trimToMaxLocked(db, maxRecords)
    rowId
  }

  /** Total number of buffered rows. */
  fun count(): Int = synchronized(lock) {
    helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c ->
      if (c.moveToFirst()) c.getInt(0) else 0
    }
  }

  /** Newest-first read for the JS `getBufferedLocations()` API. `limit <= 0` = all. */
  fun recent(limit: Int): List<LocationFixModel> = synchronized(lock) {
    val sql = buildString {
      append("SELECT $COL_ID, $COL_BODY FROM $TABLE ORDER BY $COL_ID DESC")
      if (limit > 0) append(" LIMIT $limit")
    }
    read(sql)
  }

  /** Oldest-first FIFO batch for sync. Returns the rows *and* their ids so the
   *  caller can delete exactly what the server accepted. */
  fun takeBatch(batchSize: Int): List<Pair<Long, LocationFixModel>> = synchronized(lock) {
    val out = ArrayList<Pair<Long, LocationFixModel>>(batchSize)
    helper.readableDatabase.rawQuery(
      "SELECT $COL_ID, $COL_BODY FROM $TABLE ORDER BY $COL_ID ASC LIMIT ?",
      arrayOf(batchSize.coerceAtLeast(1).toString())
    ).use { c ->
      val idIdx = c.getColumnIndexOrThrow(COL_ID)
      val bodyIdx = c.getColumnIndexOrThrow(COL_BODY)
      while (c.moveToNext()) {
        val id = c.getLong(idIdx)
        val model = parse(c.getString(bodyIdx), id) ?: continue
        out.add(id to model)
      }
    }
    out
  }

  /** Delete the given row ids (post-sync acknowledgement). Returns rows removed. */
  fun deleteIds(ids: List<Long>): Int = synchronized(lock) {
    if (ids.isEmpty()) return 0
    val db = helper.writableDatabase
    val placeholders = ids.joinToString(",") { "?" }
    val args = ids.map { it.toString() }.toTypedArray()
    db.delete(TABLE, "$COL_ID IN ($placeholders)", args)
  }

  /** Drop every row. Returns the number removed. */
  fun clear(): Int = synchronized(lock) {
    val db = helper.writableDatabase
    val n = count()
    db.delete(TABLE, null, null)
    n
  }

  private fun trimToMaxLocked(db: SQLiteDatabase, maxRecords: Int) {
    if (maxRecords <= 0) return
    // Delete everything older than the newest `maxRecords` rows in one statement.
    db.execSQL(
      "DELETE FROM $TABLE WHERE $COL_ID NOT IN " +
        "(SELECT $COL_ID FROM $TABLE ORDER BY $COL_ID DESC LIMIT ?)",
      arrayOf<Any>(maxRecords)
    )
  }

  private fun read(sql: String): List<LocationFixModel> {
    val out = ArrayList<LocationFixModel>()
    helper.readableDatabase.rawQuery(sql, null).use { c ->
      val idIdx = c.getColumnIndexOrThrow(COL_ID)
      val bodyIdx = c.getColumnIndexOrThrow(COL_BODY)
      while (c.moveToNext()) {
        parse(c.getString(bodyIdx), c.getLong(idIdx))?.let(out::add)
      }
    }
    return out
  }

  private fun parse(body: String?, id: Long): LocationFixModel? {
    if (body.isNullOrEmpty()) return null
    return runCatching { LocationFixModel.fromJson(JSONObject(body), id.toString()) }.getOrNull()
  }

  private class Helper(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS $TABLE (" +
          "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
          "$COL_TS INTEGER NOT NULL, " +
          "$COL_BODY TEXT NOT NULL)"
      )
      db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_ts ON $TABLE($COL_TS)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
      // v1 schema only — nothing to migrate yet.
    }
  }

  companion object {
    private const val DB_NAME = "persistent_background_location.db"
    private const val DB_VERSION = 1
    private const val TABLE = "fixes"
    private const val COL_ID = "id"
    private const val COL_TS = "ts"
    private const val COL_BODY = "body"

    @Volatile
    private var instance: LocationBufferStore? = null

    fun get(context: Context): LocationBufferStore =
      instance ?: synchronized(this) {
        instance ?: LocationBufferStore(context).also { instance = it }
      }
  }
}
