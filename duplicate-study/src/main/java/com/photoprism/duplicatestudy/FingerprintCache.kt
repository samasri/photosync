package com.photoprism.duplicatestudy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FingerprintCache(context: Context) :
    SQLiteOpenHelper(context, "duplicate_fingerprints.db", null, 1) {

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE fingerprints (
                media_id INTEGER PRIMARY KEY,
                size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                algorithm_version INTEGER NOT NULL,
                pixels BLOB NOT NULL,
                phash INTEGER NOT NULL,
                dhash INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        database.execSQL("DROP TABLE IF EXISTS fingerprints")
        onCreate(database)
    }

    fun get(mediaId: Long, size: Long, modifiedAt: Long): DuplicateMath.Fingerprint? {
        readableDatabase.query(
            "fingerprints",
            arrayOf("pixels", "phash", "dhash"),
            "media_id = ? AND size = ? AND modified_at = ? AND algorithm_version = ?",
            arrayOf(mediaId.toString(), size.toString(), modifiedAt.toString(), VERSION.toString()),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return DuplicateMath.Fingerprint(
                pixels = cursor.getBlob(0),
                phash = cursor.getLong(1),
                dhash = cursor.getLong(2),
            )
        }
    }

    fun put(mediaId: Long, size: Long, modifiedAt: Long, fingerprint: DuplicateMath.Fingerprint) {
        writableDatabase.execSQL(
            """
            INSERT OR REPLACE INTO fingerprints
                (media_id, size, modified_at, algorithm_version, pixels, phash, dhash)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                mediaId,
                size,
                modifiedAt,
                VERSION,
                fingerprint.pixels,
                fingerprint.phash,
                fingerprint.dhash,
            ),
        )
    }

    private companion object {
        const val VERSION = 1
    }
}
