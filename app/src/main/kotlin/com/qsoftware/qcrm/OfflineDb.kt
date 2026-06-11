package com.qsoftware.qcrm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OfflineDb(ctx: Context) :
    SQLiteOpenHelper(ctx, "offline.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE gps_logs(id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL, longitude REAL, accuracy REAL, timestamp INTEGER)")
    }
    
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS gps_logs(id INTEGER PRIMARY KEY AUTOINCREMENT, latitude REAL, longitude REAL, accuracy REAL, timestamp INTEGER)")
        }
    }

    fun insert(k: String, v: String) = writableDatabase.execSQL(
        "INSERT OR REPLACE INTO cache(k,v) VALUES(?,?)", arrayOf(k, v)
    )

    fun get(k: String): String? = readableDatabase.rawQuery(
        "SELECT v FROM cache WHERE k=?", arrayOf(k)
    ).use { if (it.moveToFirst()) it.getString(0) else null }

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
}