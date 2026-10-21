package com.v2ray.ang.ui.backup

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupRestoreTransactionTest {
    @Test
    fun failedImportRestoresTheSafetyCopy() = runBlocking {
        var state = "before"
        val restored = restoreWithRollback(
            createSafetyCopy = { true },
            restoreImport = { state = "partially replaced"; false },
            restoreSafetyCopy = { state = "before"; true },
        )

        assertFalse(restored)
        assertEquals("before", state)
    }

    @Test
    fun exceptionDuringImportRestoresTheSafetyCopyAndPropagates() = runBlocking {
        var state = "before"
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                restoreWithRollback(
                    createSafetyCopy = { true },
                    restoreImport = { state = "partially replaced"; error("simulated restore failure") },
                    restoreSafetyCopy = { state = "before"; true },
                )
            }
        }
        assertEquals("before", state)
    }

    @Test
    fun refusesToStartIfSafetyCopyCannotBeCreated() = runBlocking {
        var restoreCalled = false
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                restoreWithRollback(
                    createSafetyCopy = { false },
                    restoreImport = { restoreCalled = true; true },
                    restoreSafetyCopy = { true },
                )
            }
        }
        assertFalse(restoreCalled)
    }

    @Test
    fun successfulImportDoesNotRollback() = runBlocking {
        var rollbackCalled = false
        val restored = restoreWithRollback(
            createSafetyCopy = { true },
            restoreImport = { true },
            restoreSafetyCopy = { rollbackCalled = true; true },
        )

        assertTrue(restored)
        assertFalse(rollbackCalled)
    }
}
