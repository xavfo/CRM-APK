package com.qsoftware.qcrm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OfflineDb(ctx: Context) :
    SQLiteOpenHelper(ctx, "offline.db", null, 3) {

    companion object {
        const val TABLE_CACHE = "cache"
        const val TABLE_GPS_LOGS = "gps_logs"
        const val TABLE_SYNC_QUEUE = "sync_queue"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE gps_logs(id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL, longitude REAL, accuracy REAL, timestamp INTEGER)")
        // Queue table for pending sync operations
        db.execSQL("""
            CREATE TABLE sync_queue(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                action TEXT,
                key TEXT,
                data TEXT,
                timestamp INTEGER,
                synced INTEGER DEFAULT 0
            )
        """.trimIndent())
    }
    
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS gps_logs(id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL, longitude REAL, accuracy REAL, timestamp INTEGER)")
        }
        if (old < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sync_queue(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    action TEXT,
                    key TEXT,
                    data TEXT,
                    timestamp INTEGER,
                    synced INTEGER DEFAULT 0
                )
            """.trimIndent())
        }
    }

    fun insert(k: String, v: String) = writableDatabase.execSQL(
        "INSERT OR REPLACE INTO cache(k,v) VALUES(?,?)", arrayOf(k, v)
    )

    fun get(k: String): String? = readableDatabase.rawQuery(
        "SELECT v FROM cache WHERE k=?", arrayOf(k)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    /**
     * Get all cache keys (for sync purposes)
     */
    fun getAllKeys(): List<String> {
        val keys = ArrayList<String>()
        readableDatabase.rawQuery("SELECT k FROM cache", null).use { cursor ->
            while (cursor.moveToNext()) {
                keys.add(cursor.getString(0))
            }
        }
        return keys
    }

    /**
     * Queue an action for sync when back online
     */
    fun queueForSync(action: String, key: String, data: String) {
        writableDatabase.execSQL(
            "INSERT INTO sync_queue(action, key, data, timestamp, synced) VALUES(?,?,?,?,0)",
            arrayOf(action, key, data, System.currentTimeMillis())
        )
    }

    /**
     * Get all pending sync items
     */
    fun getPendingSyncItems(): List<SyncItem> {
        val items = ArrayList<SyncItem>()
        readableDatabase.rawQuery(
            "SELECT id, action, key, data, timestamp FROM sync_queue WHERE synced=0 ORDER BY timestamp ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                items.add(SyncItem(
                    id = cursor.getLong(0),
                    action = cursor.getString(1),
                    key = cursor.getString(2),
                    data = cursor.getString(3),
                    timestamp = cursor.getLong(4)
                ))
            }
        }
        return items
    }

    /**
     * Mark sync item as completed
     */
    fun markSynced(id: Long) {
        writableDatabase.execSQL("UPDATE sync_queue SET synced=1 WHERE id=?", arrayOf(id))
    }

    /**
     * Mark all sync items as completed
     */
    fun markAllSynced() {
        writableDatabase.execSQL("UPDATE sync_queue SET synced=1 WHERE synced=0")
    }

    /**
     * Get count of pending sync items
     */
    fun getPendingSyncCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_queue WHERE synced=0", null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    fun logGPS(latitude: Double, longitude: Double, accuracy: Float, timestamp: Long) = writableDatabase.execSQL(
        "INSERT INTO gps_logs(latitude, longitude, accuracy, timestamp) VALUES(?,?,?,?)",
        arrayOf(latitude, longitude, accuracy, timestamp)
    )

    fun getGPSLogsJson(): String {
        val list = ArrayList<String>()
        readableDatabase.rawQuery("SELECT latitude, longitude, accuracy, timestamp FROM gps_logs ORDER BY timestamp ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val lat = cursor.getDouble(0)
                val lng = cursor.getDouble(1)
                val acc = cursor.getFloat(2)
                val ts = cursor.getLong(3)
                list.add("{\"latitude\":$lat,\"longitude\":$lng,\"accuracy\":$acc,\"timestamp\":$ts}")
            }
        }
        return "[${list.joinToString(",")}]"
    }

    fun clearGPSLogs() = writableDatabase.execSQL("DELETE FROM gps_logs")

    data class SyncItem(
        val id: Long,
        val action: String,
        val key: String,
        val data: String,
        val timestamp: Long
    )
}