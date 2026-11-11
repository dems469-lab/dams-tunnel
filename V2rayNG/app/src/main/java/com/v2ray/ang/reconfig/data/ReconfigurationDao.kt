package com.v2ray.ang.reconfig.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ReconfigurationInsertSummary(
    val inserted: Int,
    val duplicates: Int,
) {
    val total: Int get() = inserted + duplicates
}

/**
 * Accès SQLite aux serveurs préparés.
 *
 * Toutes les écritures passent par un verrou unique : deux lots simultanés ne
 * peuvent pas créer d'état incohérent. Les doublons sont refusés par l'index
 * unique sur l'empreinte du résultat.
 */
internal class ReconfigurationDao(
    context: Context,
    private val database: ReconfigurationDatabase = ReconfigurationDatabase(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val writeMutex = Mutex()

    suspend fun insertAll(servers: List<ReconfiguredServer>): ReconfigurationInsertSummary =
        withContext(ioDispatcher) {
            if (servers.isEmpty()) return@withContext ReconfigurationInsertSummary(0, 0)
            writeMutex.withLock {
                val db = database.writableDatabase
                var inserted = 0
                var duplicates = 0
                db.beginTransaction()
                try {
                    servers.forEach { server ->
                        val rowId = db.insertWithOnConflict(
                            ReconfigurationDatabase.TABLE,
                            null,
                            toValues(server),
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                        if (rowId == -1L) duplicates++ else inserted++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                ReconfigurationInsertSummary(inserted, duplicates)
            }
        }

    suspend fun loadAll(): List<ReconfiguredServer> = withContext(ioDispatcher) {
        database.readableDatabase.query(
            ReconfigurationDatabase.TABLE,
            null,
            null,
            null,
            null,
            null,
            "${ReconfigurationDatabase.COLUMN_CREATED_AT} DESC, ${ReconfigurationDatabase.COLUMN_ID} DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toServer())
            }
        }
    }

    suspend fun findByIds(ids: Set<Long>): List<ReconfiguredServer> = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        database.readableDatabase.query(
            ReconfigurationDatabase.TABLE,
            null,
            "${ReconfigurationDatabase.COLUMN_ID} IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toServer())
            }
        }
    }

    suspend fun updatePing(id: Long, state: ReconfigPingState, latencyMs: Long, at: Long) =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                database.writableDatabase.update(
                    ReconfigurationDatabase.TABLE,
                    ContentValues().apply {
                        put(ReconfigurationDatabase.COLUMN_PING_STATUS, state.name)
                        put(ReconfigurationDatabase.COLUMN_PING_MS, latencyMs)
                        put(ReconfigurationDatabase.COLUMN_LAST_PING_AT, at)
                        put(ReconfigurationDatabase.COLUMN_UPDATED_AT, at)
                    },
                    "${ReconfigurationDatabase.COLUMN_ID} = ?",
                    arrayOf(id.toString()),
                )
            }
        }

    suspend fun updateTest(
        id: Long,
        state: ReconfigTestState,
        functional: Boolean,
        at: Long,
        error: String?,
    ) = withContext(ioDispatcher) {
        writeMutex.withLock {
            database.writableDatabase.update(
                ReconfigurationDatabase.TABLE,
                ContentValues().apply {
                    put(ReconfigurationDatabase.COLUMN_TEST_STATUS, state.name)
                    put(ReconfigurationDatabase.COLUMN_IS_FUNCTIONAL, if (functional) 1 else 0)
                    put(ReconfigurationDatabase.COLUMN_LAST_TEST_AT, at)
                    put(ReconfigurationDatabase.COLUMN_UPDATED_AT, at)
                    put(ReconfigurationDatabase.COLUMN_ERROR, error)
                },
                "${ReconfigurationDatabase.COLUMN_ID} = ?",
                arrayOf(id.toString()),
            )
        }
    }

    suspend fun delete(ids: Set<Long>): Int = withContext(ioDispatcher) {
        if (ids.isEmpty()) return@withContext 0
        writeMutex.withLock {
            val placeholders = ids.joinToString(",") { "?" }
            database.writableDatabase.delete(
                ReconfigurationDatabase.TABLE,
                "${ReconfigurationDatabase.COLUMN_ID} IN ($placeholders)",
                ids.map { it.toString() }.toTypedArray(),
            )
        }
    }

    private fun toValues(server: ReconfiguredServer) = ContentValues().apply {
        put(ReconfigurationDatabase.COLUMN_HASH, server.configHash)
        put(ReconfigurationDatabase.COLUMN_ORIGINAL_ID, server.originalConfigId)
        put(ReconfigurationDatabase.COLUMN_ORIGINAL_CONFIG, server.originalConfig)
        put(ReconfigurationDatabase.COLUMN_TRANSFORMED_CONFIG, server.transformedConfig)
        put(ReconfigurationDatabase.COLUMN_TRANSFORMED_JSON, server.transformedProfileJson)
        put(ReconfigurationDatabase.COLUMN_NAME, server.name)
        put(ReconfigurationDatabase.COLUMN_PROTOCOL, server.protocol)
        put(ReconfigurationDatabase.COLUMN_ORIGINAL_ADDRESS, server.originalAddress)
        put(ReconfigurationDatabase.COLUMN_TRANSFORMED_ADDRESS, server.transformedAddress)
        put(ReconfigurationDatabase.COLUMN_ORIGINAL_PORT, server.originalPort)
        put(ReconfigurationDatabase.COLUMN_TRANSFORMED_PORT, server.transformedPort)
        put(ReconfigurationDatabase.COLUMN_METHOD, server.reconfigurationMethod)
        put(ReconfigurationDatabase.COLUMN_TARGET, server.target)
        put(ReconfigurationDatabase.COLUMN_CREATED_AT, server.createdAt)
        put(ReconfigurationDatabase.COLUMN_UPDATED_AT, server.updatedAt)
        put(ReconfigurationDatabase.COLUMN_PING_MS, server.pingMs)
        put(ReconfigurationDatabase.COLUMN_PING_STATUS, server.pingState.name)
        put(ReconfigurationDatabase.COLUMN_TEST_STATUS, server.testState.name)
        put(ReconfigurationDatabase.COLUMN_LAST_PING_AT, server.lastPingAt)
        put(ReconfigurationDatabase.COLUMN_LAST_TEST_AT, server.lastTestAt)
        put(ReconfigurationDatabase.COLUMN_ERROR, server.errorMessage)
        put(ReconfigurationDatabase.COLUMN_IS_VALID, if (server.isValid) 1 else 0)
        put(ReconfigurationDatabase.COLUMN_IS_FUNCTIONAL, if (server.isFunctional) 1 else 0)
    }

    private fun Cursor.toServer(): ReconfiguredServer {
        fun text(column: String): String = getString(getColumnIndexOrThrow(column)) ?: ""
        fun long(column: String): Long = getLong(getColumnIndexOrThrow(column))
        fun bool(column: String): Boolean = getInt(getColumnIndexOrThrow(column)) != 0
        val ping = runCatching { ReconfigPingState.valueOf(text(ReconfigurationDatabase.COLUMN_PING_STATUS)) }
            .getOrDefault(ReconfigPingState.PENDING)
        val test = runCatching { ReconfigTestState.valueOf(text(ReconfigurationDatabase.COLUMN_TEST_STATUS)) }
            .getOrDefault(ReconfigTestState.PENDING)
        val errorIndex = getColumnIndexOrThrow(ReconfigurationDatabase.COLUMN_ERROR)
        return ReconfiguredServer(
            id = long(ReconfigurationDatabase.COLUMN_ID),
            configHash = text(ReconfigurationDatabase.COLUMN_HASH),
            originalConfigId = text(ReconfigurationDatabase.COLUMN_ORIGINAL_ID),
            originalConfig = text(ReconfigurationDatabase.COLUMN_ORIGINAL_CONFIG),
            transformedConfig = text(ReconfigurationDatabase.COLUMN_TRANSFORMED_CONFIG),
            transformedProfileJson = text(ReconfigurationDatabase.COLUMN_TRANSFORMED_JSON),
            name = text(ReconfigurationDatabase.COLUMN_NAME),
            protocol = text(ReconfigurationDatabase.COLUMN_PROTOCOL),
            originalAddress = text(ReconfigurationDatabase.COLUMN_ORIGINAL_ADDRESS),
            transformedAddress = text(ReconfigurationDatabase.COLUMN_TRANSFORMED_ADDRESS),
            originalPort = text(ReconfigurationDatabase.COLUMN_ORIGINAL_PORT),
            transformedPort = text(ReconfigurationDatabase.COLUMN_TRANSFORMED_PORT),
            reconfigurationMethod = text(ReconfigurationDatabase.COLUMN_METHOD),
            target = text(ReconfigurationDatabase.COLUMN_TARGET),
            createdAt = long(ReconfigurationDatabase.COLUMN_CREATED_AT),
            updatedAt = long(ReconfigurationDatabase.COLUMN_UPDATED_AT),
            pingMs = long(ReconfigurationDatabase.COLUMN_PING_MS),
            // Un état « en cours » n'a plus de sens après un redémarrage.
            pingState = if (ping == ReconfigPingState.RUNNING) ReconfigPingState.PENDING else ping,
            testState = if (test == ReconfigTestState.RUNNING) ReconfigTestState.PENDING else test,
            lastPingAt = long(ReconfigurationDatabase.COLUMN_LAST_PING_AT),
            lastTestAt = long(ReconfigurationDatabase.COLUMN_LAST_TEST_AT),
            errorMessage = if (isNull(errorIndex)) null else getString(errorIndex),
            isValid = bool(ReconfigurationDatabase.COLUMN_IS_VALID),
            isFunctional = bool(ReconfigurationDatabase.COLUMN_IS_FUNCTIONAL),
        )
    }
}
