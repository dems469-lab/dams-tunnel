package com.v2ray.ang.laboratory.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base SQLite dédiée au laboratoire.
 *
 * Comme le module Reconfiguration, elle utilise l'API Android standard : aucune
 * dépendance Room n'existe dans ce projet. Le fichier porte un nom distinct
 * (`dams_laboratory.db`) et n'est jamais partagé avec les données de production.
 *
 * Les migrations sont uniquement additives : [onUpgrade] ne supprime aucune
 * table et ne touche jamais aux données des autres modules.
 */
internal class LaboratoryDatabase(
    context: Context,
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_SESSION)
        db.execSQL(CREATE_PATH_STAT)
        db.execSQL(CREATE_PATH_STAT_TIME_INDEX)
        db.execSQL(CREATE_RELAY)
        db.execSQL(CREATE_RELAY_TIME_INDEX)
        db.execSQL(CREATE_LOG)
        db.execSQL(CREATE_LOG_TIME_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations additives uniquement. Les versions futures ajouteront des
        // ALTER TABLE / CREATE TABLE sans jamais réinitialiser le laboratoire.
        if (oldVersion < 2) {
            runCatching { db.execSQL(CREATE_LOG) }
            runCatching { db.execSQL(CREATE_LOG_TIME_INDEX) }
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Rien n'est supprimé : l'application reste utilisable.
    }

    companion object {
        const val DATABASE_NAME = "dams_laboratory.db"
        const val DATABASE_VERSION = 2

        const val TABLE_SESSION = "lab_session"
        const val TABLE_PATH_STAT = "lab_path_stat"
        const val TABLE_RELAY = "lab_relay_event"
        const val TABLE_LOG = "lab_event_log"

        private const val CREATE_SESSION = """
            CREATE TABLE IF NOT EXISTS $TABLE_SESSION (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                primary_guid TEXT NOT NULL,
                primary_name TEXT NOT NULL,
                mode TEXT NOT NULL,
                outcome TEXT
            )
        """

        private const val CREATE_PATH_STAT = """
            CREATE TABLE IF NOT EXISTS $TABLE_PATH_STAT (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER,
                recorded_at INTEGER NOT NULL,
                tag TEXT NOT NULL,
                guid TEXT NOT NULL,
                remarks TEXT NOT NULL,
                latency_ms INTEGER NOT NULL,
                loss_pct INTEGER NOT NULL,
                availability_pct INTEGER NOT NULL,
                stability_pct INTEGER NOT NULL,
                quality_score INTEGER NOT NULL,
                throughput_index INTEGER NOT NULL,
                state TEXT NOT NULL
            )
        """

        private const val CREATE_PATH_STAT_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_lab_path_stat_time ON $TABLE_PATH_STAT(recorded_at)"

        private const val CREATE_RELAY = """
            CREATE TABLE IF NOT EXISTS $TABLE_RELAY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER,
                recorded_at INTEGER NOT NULL,
                from_tag TEXT,
                to_tag TEXT NOT NULL,
                reason TEXT NOT NULL,
                from_latency_ms INTEGER NOT NULL,
                to_latency_ms INTEGER NOT NULL
            )
        """

        private const val CREATE_RELAY_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_lab_relay_time ON $TABLE_RELAY(recorded_at)"

        private const val CREATE_LOG = """
            CREATE TABLE IF NOT EXISTS $TABLE_LOG (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recorded_at INTEGER NOT NULL,
                level TEXT NOT NULL,
                category TEXT NOT NULL,
                message TEXT NOT NULL
            )
        """

        private const val CREATE_LOG_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_lab_log_time ON $TABLE_LOG(recorded_at)"
    }
}
