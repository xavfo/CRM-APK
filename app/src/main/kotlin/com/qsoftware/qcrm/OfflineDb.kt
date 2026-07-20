package com.qsoftware.qcrm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OfflineDb(ctx: Context) :
    SQLiteOpenHelper(ctx, "offline.db", null, 4) {

    companion object {
        const val TABLE_CACHE = "cache"
        const val TABLE_GPS_LOGS = "gps_logs"
        const val TABLE_SYNC_QUEUE = "sync_queue"
        const val TABLE_FORM_ENTRIES = "form_entries"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE gps_logs(id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL, longitude REAL, accuracy REAL, timestamp INTEGER)")
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
        db.execSQL("""
            CREATE TABLE form_entries(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                payload TEXT NOT NULL,
                photo_path TEXT,
                latitude REAL,
                longitude REAL,
                accuracy REAL,
                created_at INTEGER NOT NULL,
                synced INTEGER DEFAULT 0,
                sync_message TEXT
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
        if (old < 4) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS form_entries(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    payload TEXT NOT NULL,
                    photo_path TEXT,
                    latitude REAL,
                    longitude REAL,
                    accuracy REAL,
                    created_at INTEGER NOT NULL,
                    synced INTEGER DEFAULT 0,
                    sync_message TEXT
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

    fun getAllKeys(): List<String> {
        val keys = ArrayList<String>()
        readableDatabase.rawQuery("SELECT k FROM cache", null).use { cursor ->
            while (cursor.moveToNext()) {
                keys.add(cursor.getString(0))
            }
        }
        return keys
    }

    fun queueForSync(action: String, key: String, data: String) {
        writableDatabase.execSQL(
            "INSERT INTO sync_queue(action, key, data, timestamp, synced) VALUES(?,?,?,?,0)",
            arrayOf(action, key, data, System.currentTimeMillis())
        )
    }

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

    fun markSynced(id: Long) {
        writableDatabase.execSQL("UPDATE sync_queue SET synced=1 WHERE id=?", arrayOf(id))
    }

    fun markAllSynced() {
        writableDatabase.execSQL("UPDATE sync_queue SET synced=1 WHERE synced=0")
    }

    fun getPendingSyncCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_queue WHERE synced=0", null).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getInt(0)
        }
        return 0
    }

    fun insertPendingForm(
        payload: String,
        photoPath: String?,
        latitude: Double?,
        longitude: Double?,
        accuracy: Double?,
        createdAt: Long
    ) {
        writableDatabase.execSQL(
            "INSERT INTO form_entries(payload, photo_path, latitude, longitude, accuracy, created_at, synced, sync_message) VALUES(?,?,?,?,?,?,0,?)",
            arrayOf(payload, photoPath, latitude, longitude, accuracy, createdAt, "pendiente")
        )
    }

    fun getPendingForms(): List<FormEntry> {
        val entries = ArrayList<FormEntry>()
        readableDatabase.rawQuery(
            "SELECT id, payload, photo_path, latitude, longitude, accuracy, created_at, synced FROM form_entries WHERE synced=0 ORDER BY created_at ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(
                    FormEntry(
                        id = cursor.getLong(0),
                        payload = cursor.getString(1),
                        photoPath = cursor.getString(2),
                        latitude = cursor.getDouble(3),
                        longitude = cursor.getDouble(4),
                        accuracy = cursor.getDouble(5),
                        createdAt = cursor.getLong(6),
                        synced = cursor.getInt(7)
                    )
                )
            }
        }
        return entries
    }

    fun markPendingFormSynced(id: Long) {
        writableDatabase.execSQL("UPDATE form_entries SET synced=1, sync_message='sincronizado' WHERE id=?", arrayOf(id))
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

    data class FormEntry(
        val id: Long,
        val payload: String,
        val photoPath: String?,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Double,
        val createdAt: Long,
        val synced: Int
    )
}