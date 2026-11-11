package com.v2ray.ang.reconfig.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base SQLite locale du module de reconfiguration.
 *
 * SQLite est utilisé via l'API Android standard, sans dépendance
 * supplémentaire : aucune base Room n'est présente dans ce projet et ajouter un
 * processeur d'annotations fragiliserait la chaîne de compilation.
 *
 * Les données sont conservées lors des mises à jour de l'application :
 * [onUpgrade] ne supprime jamais la table, il applique uniquement des
 * migrations additives.
 */
internal class ReconfigurationDatabase(
    context: Context,
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_HASH_INDEX)
        db.execSQL(CREATE_CREATED_INDEX)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        ReconfigurationMigrations.apply(db, oldVersion, newVersion)
    }

    companion object {
        const val DATABASE_NAME = "dams_reconfiguration.db"
        const val DATABASE_VERSION = 1

        const val TABLE = "reconfigured_server"
        const val COLUMN_ID = "id"
        const val COLUMN_HASH = "config_hash"
        const val COLUMN_ORIGINAL_ID = "original_config_id"
        const val COLUMN_ORIGINAL_CONFIG = "original_config"
        const val COLUMN_TRANSFORMED_CONFIG = "transformed_config"
        const val COLUMN_TRANSFORMED_JSON = "transformed_profile_json"
        const val COLUMN_NAME = "name"
        const val COLUMN_PROTOCOL = "protocol"
        const val COLUMN_ORIGINAL_ADDRESS = "original_address"
        const val COLUMN_TRANSFORMED_ADDRESS = "transformed_address"
        const val COLUMN_ORIGINAL_PORT = "original_port"
        const val COLUMN_TRANSFORMED_PORT = "transformed_port"
        const val COLUMN_METHOD = "reconfiguration_method"
        const val COLUMN_TARGET = "target"
        const val COLUMN_CREATED_AT = "created_at"
        const val COLUMN_UPDATED_AT = "updated_at"
        const val COLUMN_PING_MS = "ping_ms"
        const val COLUMN_PING_STATUS = "ping_status"
        const val COLUMN_TEST_STATUS = "test_status"
        const val COLUMN_LAST_PING_AT = "last_ping_at"
        const val COLUMN_LAST_TEST_AT = "last_test_at"
        const val COLUMN_ERROR = "error_message"
        const val COLUMN_IS_VALID = "is_valid"
        const val COLUMN_IS_FUNCTIONAL = "is_functional"

        private val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_HASH TEXT NOT NULL,
                $COLUMN_ORIGINAL_ID TEXT NOT NULL DEFAULT '',
                $COLUMN_ORIGINAL_CONFIG TEXT NOT NULL DEFAULT '',
                $COLUMN_TRANSFORMED_CONFIG TEXT NOT NULL DEFAULT '',
                $COLUMN_TRANSFORMED_JSON TEXT NOT NULL DEFAULT '',
                $COLUMN_NAME TEXT NOT NULL DEFAULT '',
                $COLUMN_PROTOCOL TEXT NOT NULL DEFAULT '',
                $COLUMN_ORIGINAL_ADDRESS TEXT NOT NULL DEFAULT '',
                $COLUMN_TRANSFORMED_ADDRESS TEXT NOT NULL DEFAULT '',
                $COLUMN_ORIGINAL_PORT TEXT NOT NULL DEFAULT '',
                $COLUMN_TRANSFORMED_PORT TEXT NOT NULL DEFAULT '',
                $COLUMN_METHOD TEXT NOT NULL DEFAULT '',
                $COLUMN_TARGET TEXT NOT NULL DEFAULT '',
                $COLUMN_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_UPDATED_AT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PING_MS INTEGER NOT NULL DEFAULT -1,
                $COLUMN_PING_STATUS TEXT NOT NULL DEFAULT 'PENDING',
                $COLUMN_TEST_STATUS TEXT NOT NULL DEFAULT 'PENDING',
                $COLUMN_LAST_PING_AT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_LAST_TEST_AT INTEGER NOT NULL DEFAULT 0,
                $COLUMN_ERROR TEXT,
                $COLUMN_IS_VALID INTEGER NOT NULL DEFAULT 1,
                $COLUMN_IS_FUNCTIONAL INTEGER NOT NULL DEFAULT 0
            )
        """

        private val CREATE_HASH_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_${TABLE}_hash ON $TABLE ($COLUMN_HASH)"

        private val CREATE_CREATED_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_${TABLE}_created ON $TABLE ($COLUMN_CREATED_AT)"
    }
}

/**
 * Migrations additives. Toute évolution ajoute une colonne ou un index et ne
 * supprime jamais les lignes existantes.
 */
internal object ReconfigurationMigrations {
    fun apply(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 : schéma initial créé par onCreate.
        // Les futures migrations (`if (oldVersion < 2) { ... }`) s'ajoutent ici
        // sous forme de colonnes ou d'index, sans jamais supprimer de lignes.
        if (oldVersion >= newVersion) return
    }
}
